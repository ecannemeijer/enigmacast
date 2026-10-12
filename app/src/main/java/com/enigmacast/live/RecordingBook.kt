package com.enigmacast.live

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Environment
import com.enigmacast.R
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.data.openwebif.OpenWebifJson
import com.enigmacast.data.recording.DataStoreRecordingStorage
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.RecordingTarget
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.recording.RecordAsk
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.recording.RecordingPlace
import com.enigmacast.domain.recording.RecordingStatus
import com.enigmacast.domain.recording.receiverTimers
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recordings started from the guide. On the TV a wekker starts a capture of the same stream
 * the player uses; on the receiver OpenWebif sets a timer. Both stay in this list.
 */
@Singleton
class RecordingBook @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: DataStoreRecordingStorage,
    private val settingsRepository: SettingsRepository,
    private val api: OpenWebifApi,
    private val endpoints: OpenWebifEndpoints,
    private val streamTargets: StreamTargets,
    private val playback: LivePlaybackController,
    private val series: dagger.Lazy<SeriesRecorder>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Mutex()
    private val _items = MutableStateFlow<List<Recording>>(emptyList())
    val items: StateFlow<List<Recording>> = _items.asStateFlow()
    private val _nowPlaying = MutableStateFlow<Recording?>(null)
    val nowPlaying: StateFlow<Recording?> = _nowPlaying.asStateFlow()
    private var loaded = false

    init {
        scope.launch { ensureArmed() }
    }

    /** Load saved recordings and arm anything that should start. Safe to call more than once. */
    suspend fun ensureArmed() {
        withContext(Dispatchers.IO) { awaitLoaded() }
        arm()
    }

    /** Replace the in-memory list with what is stored. A stop can finish while My Items is closed. */
    suspend fun reload() = gate.withLock {
        _items.value = storage.load().sortedByDescending { it.beginEpochSec }
        loaded = true
    }

    fun enqueue(id: String?, done: () -> Unit) {
        scope.launch {
            try {
                awaitLoaded()
                if (id.isNullOrBlank()) arm() else startCapture(id)
            } finally {
                done()
            }
        }
    }

    /** The confirm step. A programme that has already ended is refused. */
    suspend fun prepare(event: GuideEvent, channelName: String): PrepareResult {
        val now = nowSec()
        if (event.endEpochSec <= now) return PrepareResult.Message(UiText.res(R.string.record_already_ended))
        val settings = settingsRepository.current()
        val place = settings.playback.recordingTarget
        awaitLoaded()
        val existing = _items.value.firstOrNull {
            com.enigmacast.domain.tuner.NowEvents.key(it.reference) == com.enigmacast.domain.tuner.NowEvents.key(event.reference) &&
                it.beginEpochSec == event.beginEpochSec && it.isActive()
        }
        val upcoming = runCatching { series.get().upcoming(event).size }.getOrDefault(0)
        // Already set: a series can still get "Record all upcoming" for the other episodes.
        if (existing != null) {
            return if (upcoming > 0) PrepareResult.Ask(RecordAsk(event, channelName, place, upcoming = upcoming, onlyAll = true))
            else PrepareResult.Message(UiText.res(R.string.record_already_set))
        }
        return PrepareResult.Ask(RecordAsk(event, channelName, place, upcoming = upcoming))
    }

    suspend fun schedule(ask: RecordAsk): UiText {
        if (ask.allUpcoming) return series.get().recordAll(ask)
        val settings = settingsRepository.current()
        val receiver = settings.activeReceiver() ?: return UiText.res(R.string.conn_no_receiver_configured)
        val event = ask.event
        val now = nowSec()
        val recording = Recording(
            id = Recording.newId(),
            title = event.title.ifBlank { ask.channelName },
            channelName = ask.channelName,
            reference = event.reference,
            eventId = event.eventId,
            beginEpochSec = event.beginEpochSec,
            endEpochSec = event.endEpochSec,
            place = if (ask.place == RecordingTarget.RECEIVER) RecordingPlace.RECEIVER else RecordingPlace.TV,
            status = RecordingStatus.SCHEDULED,
            receiverId = receiver.id,
        )
        return if (recording.place == RecordingPlace.RECEIVER) {
            scheduleOnReceiver(receiver, recording)
        } else {
            save(recording)
            if (recording.beginEpochSec <= now + START_SLOP_SEC) startCapture(recording.id)
            else armOne(recording)
            val whenText = if (event.beginEpochSec <= now) {
                UiText.res(R.string.record_started_now, recording.title)
            } else {
                UiText.res(R.string.record_scheduled_tv, recording.title)
            }
            whenText
        }
    }

    suspend fun stop(id: String) {
        awaitLoaded()
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.place == RecordingPlace.TV) {
            context.startService(RecordingCaptureService.stopIntent(context, id))
            return
        }
        val receiver = receiverFor(item) ?: return
        runCatching { api.deleteTimer(receiver, item.reference, item.beginEpochSec, item.endEpochSec) }
        val movie = runCatching { findMovie(receiver, item) }.getOrNull()
        if (movie != null) {
            update(item.id) { it.copy(status = RecordingStatus.READY, fileName = movie.filename, error = "") }
        } else {
            update(item.id) { it.copy(status = RecordingStatus.READY, error = "") }
        }
    }

    suspend fun delete(id: String) {
        awaitLoaded()
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.place == RecordingPlace.TV) {
            context.startService(RecordingCaptureService.stopIntent(context, id))
            fileFor(item).delete()
        } else {
            val receiver = receiverFor(item)
            if (receiver != null) {
                // The box stores the timer with its margins: delete it by the box's own begin/end.
                val timer = runCatching { matchTimer(api.timers(receiver), item) }.getOrNull()
                runCatching {
                    if (timer != null) api.deleteTimer(receiver, timer.reference, timer.begin, timer.end)
                    else api.deleteTimer(receiver, item.reference, item.beginEpochSec, item.endEpochSec)
                }
                if (item.fileName.isNotBlank()) {
                    runCatching { api.deleteMovie(receiver, movieRef(item.fileName)) }
                }
            }
        }
        cancelAlarm(item.id)
        remove(item.id)
    }

    /** Opens the file in the player. False when there is nothing to play yet. */
    suspend fun play(id: String, startMs: Long = 0L): Boolean {
        awaitLoaded()
        var item = _items.value.firstOrNull { it.id == id } ?: return false
        if (item.place == RecordingPlace.RECEIVER && item.fileName.isBlank()) {
            refreshReceiver()
            item = _items.value.firstOrNull { it.id == id } ?: return false
        }
        if (item.place == RecordingPlace.TV) {
            val file = fileFor(item)
            if (!file.exists() || file.length() < MIN_PLAY_BYTES) return false
            _nowPlaying.value = item
            playback.playRecording(android.net.Uri.fromFile(file).toString(), null, durationMs(item), startMs)
            return true
        }
        if (item.fileName.isBlank()) return false
        val receiver = receiverFor(item) ?: return false
        _nowPlaying.value = item
        val url = endpoints.recordedFile(receiver, item.fileName)
        playback.playRecording(url, endpoints.streamAuthorization(receiver), durationMs(item), startMs)
        return true
    }

    fun clearNowPlaying() {
        _nowPlaying.value = null
    }

    private fun durationMs(item: Recording): Long {
        return ((item.endEpochSec - item.beginEpochSec).coerceAtLeast(0L)) * 1000L
    }

    /** What the box holds that was not started from this app: recordings and its timers. */
    data class BoxItems(val movies: List<Recording>, val timers: List<Recording>)

    suspend fun boxItems(): BoxItems? {
        awaitLoaded()
        val receiver = settingsRepository.current().activeReceiver() ?: return null
        val movies = runCatching { api.movies(receiver) }.getOrNull() ?: return null
        val timers = runCatching { api.timers(receiver) }.getOrDefault(emptyList())
        val ours = _items.value.receiverTimers(receiver.id)
        val ourFiles = ours.map { it.fileName }.filter { it.isNotBlank() }.toSet()
        val boxMovies = movies.filter { it.filename !in ourFiles }.sortedByDescending { it.begin }.map { m ->
            Recording(
                id = BOX_MOVIE + m.filename,
                title = m.name.ifBlank { m.filename.substringAfterLast('/') },
                channelName = m.serviceName,
                reference = "",
                eventId = -1,
                beginEpochSec = m.begin,
                endEpochSec = m.begin + m.lengthSec,
                place = RecordingPlace.RECEIVER,
                status = RecordingStatus.READY,
                receiverId = receiver.id,
                fileName = m.filename,
            )
        }
        val now = nowSec()
        val boxTimers = timers.filter { t -> t.end > now && t.state < 3 && ours.none { matchTimer(listOf(t), it) != null } }.sortedBy { it.begin }.map { t ->
            Recording(
                id = BOX_TIMER + t.reference + "|" + t.begin,
                title = t.name,
                channelName = "",
                reference = t.reference,
                eventId = t.eventId,
                beginEpochSec = t.begin,
                endEpochSec = t.end,
                place = RecordingPlace.RECEIVER,
                status = if (t.state == 2) RecordingStatus.RECORDING else RecordingStatus.SCHEDULED,
                receiverId = receiver.id,
            )
        }
        return BoxItems(boxMovies, boxTimers)
    }

    /** Streams a box recording: OpenWebif /file?file=<path>. */
    fun playBox(item: Recording, startMs: Long = 0L): Boolean {
        if (item.fileName.isBlank()) return false
        scope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            _nowPlaying.value = item
            playback.playRecording(endpoints.recordedFile(receiver, item.fileName), endpoints.streamAuthorization(receiver), durationMs(item), startMs)
        }
        return true
    }

    /** Deletes a box recording (moviedelete) or a box timer (timerdelete). */
    suspend fun deleteBox(item: Recording): Boolean {
        val receiver = settingsRepository.current().activeReceiver() ?: return false
        return runCatching {
            if (item.id.startsWith(BOX_MOVIE)) api.deleteMovie(receiver, movieRef(item.fileName))
            else api.deleteTimer(receiver, item.reference, item.beginEpochSec, item.endEpochSec)
        }.isSuccess
    }

    /** Pulls timer and movie state for receiver recordings started here. */
    suspend fun refreshReceiver() {
        awaitLoaded()
        val receiver = settingsRepository.current().activeReceiver() ?: return
        val ours = _items.value.receiverTimers(receiver.id)
        if (ours.isEmpty()) return
        val timers = runCatching { api.timers(receiver) }.getOrDefault(emptyList())
        val movies = runCatching { api.movies(receiver) }.getOrDefault(emptyList())
        ours.forEach { item ->
            val timer = matchTimer(timers, item)
            val movie = matchMovie(movies, item)
            val status = when {
                timer?.state == 2 -> RecordingStatus.RECORDING
                movie != null && (timer == null || timer.state >= 3) -> RecordingStatus.READY
                timer != null -> RecordingStatus.SCHEDULED
                item.endEpochSec < nowSec() && movie == null -> RecordingStatus.FAILED
                else -> item.status
            }
            update(item.id) {
                it.copy(
                    status = status,
                    fileName = movie?.filename ?: it.fileName,
                    error = if (status == RecordingStatus.FAILED) context.getString(R.string.record_missed) else it.error,
                )
            }
        }
    }

    suspend fun markRecording(id: String) = update(id) { it.copy(status = RecordingStatus.RECORDING, error = "") }

    suspend fun markReady(id: String) = update(id) { it.copy(status = RecordingStatus.READY, error = "") }

    suspend fun markFailed(id: String, reason: String) {
        update(id) { it.copy(status = RecordingStatus.FAILED, error = reason) }
        _items.value.firstOrNull { it.id == id }?.let { fileFor(it).delete() }
    }

    /** The capture stopped on purpose. The row and the file stay in My Items. */
    suspend fun markStopped(id: String, bytes: Long) {
        val item = _items.value.firstOrNull { it.id == id }
        if (item == null) {
            EnigmaLog.service("recording missing id=$id bytes=$bytes")
            return
        }
        val onDisk = if (item.place == RecordingPlace.TV) fileFor(item).takeIf { it.exists() }?.length() ?: 0L else 0L
        val size = maxOf(bytes, onDisk)
        if (item.status != RecordingStatus.RECORDING && item.status != RecordingStatus.SCHEDULED) {
            EnigmaLog.service("recording kept ${item.title} already ${item.status} bytes=$size")
            return
        }
        EnigmaLog.service("recording kept ${item.title} bytes=$size")
        if (size >= MIN_PLAY_BYTES) markReady(id) else {
            update(id) { it.copy(status = RecordingStatus.FAILED, error = context.getString(R.string.record_stopped)) }
        }
    }

    suspend fun get(id: String): Recording? {
        awaitLoaded()
        return _items.value.firstOrNull { it.id == id }
    }

    fun fileFor(item: Recording): File = File(moviesDir(), "${item.id}.ts")

    suspend fun streamFor(item: Recording): Triple<String, String?, String?>? {
        val receiver = receiverFor(item) ?: return null
        val preset = settingsRepository.current().playback.recordingPreset
        val target = streamTargets.recording(receiver, item.reference, preset)
        return Triple(target.url, target.authorization, target.fallbackUrl)
    }

    private suspend fun scheduleOnReceiver(receiver: ReceiverConfig, recording: Recording): UiText {
        val result = try {
            // With an EPG event the box applies its own margins (timeraddbyeventid); without one, a plain timer.
            if (recording.eventId >= 0) api.addTimer(receiver, recording.reference, recording.eventId)
            else api.addTimer(receiver, recording.reference, recording.beginEpochSec, recording.endEpochSec, recording.title, recording.channelName)
        } catch (error: Exception) {
            EnigmaLog.service("record timer failed: ${error.javaClass.simpleName}")
            return UiText.res(R.string.record_failed)
        }
        if (!result.success) {
            val message = result.message.ifBlank { context.getString(R.string.record_failed) }
            return UiText.Raw(message)
        }
        save(recording)
        return UiText.res(R.string.record_scheduled_receiver, recording.title)
    }

    private suspend fun arm() {
        val now = nowSec()
        _items.value.filter { it.place == RecordingPlace.TV }.forEach { item ->
            when {
                item.status == RecordingStatus.SCHEDULED && item.endEpochSec <= now ->
                    update(item.id) { it.copy(status = RecordingStatus.FAILED, error = context.getString(R.string.record_missed)) }
                item.status == RecordingStatus.SCHEDULED && item.beginEpochSec <= now + START_SLOP_SEC -> startCapture(item.id)
                item.status == RecordingStatus.SCHEDULED -> armOne(item)
                item.status == RecordingStatus.RECORDING && item.endEpochSec > now -> startCapture(item.id)
                else -> Unit
            }
        }
    }

    private fun armOne(item: Recording) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val whenMs = item.beginEpochSec * 1000
        val pending = pending(item.id)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pending)
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pending)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pending)
        }
    }

    private fun cancelAlarm(id: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(id))
    }

    private fun pending(id: String): PendingIntent {
        val intent = Intent(context, RecordingAlarmReceiver::class.java).apply {
            action = RecordingAlarmReceiver.ACTION_START
            putExtra(RecordingCaptureService.EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun startCapture(id: String) {
        val intent = RecordingCaptureService.startIntent(context, id)
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    private suspend fun receiverFor(item: Recording): ReceiverConfig? {
        val settings = settingsRepository.current()
        return settings.receivers.firstOrNull { it.id == item.receiverId } ?: settings.activeReceiver()
    }

    private suspend fun findMovie(receiver: ReceiverConfig, item: Recording): OpenWebifJson.ReceiverMovie? {
        return matchMovie(api.movies(receiver), item)
    }

    private suspend fun awaitLoaded() {
        if (loaded) return
        gate.withLock {
            if (loaded) return
            _items.value = storage.load()
            loaded = true
        }
    }

    private suspend fun save(item: Recording) = gate.withLock {
        if (!loaded) {
            _items.value = storage.load()
            loaded = true
        }
        val next = _items.value.filterNot { it.id == item.id } + item
        _items.value = next.sortedByDescending { it.beginEpochSec }
        storage.save(_items.value)
    }

    private suspend fun update(id: String, transform: (Recording) -> Recording) = gate.withLock {
        if (!loaded) {
            _items.value = storage.load()
            loaded = true
        }
        val next = _items.value.map { if (it.id == id) transform(it) else it }
        _items.value = next
        storage.save(next)
    }

    private suspend fun remove(id: String) = gate.withLock {
        val next = _items.value.filterNot { it.id == id }
        _items.value = next
        storage.save(next)
    }

    private fun moviesDir(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    sealed class PrepareResult {
        data class Ask(val ask: RecordAsk) : PrepareResult()
        data class Message(val text: UiText) : PrepareResult()
    }

    companion object {
        private const val START_SLOP_SEC = 20L
        const val MIN_PLAY_BYTES = 64L * 1024

        const val BOX_MOVIE = "box-movie:"
        const val BOX_TIMER = "box-timer:"

        fun movieRef(filename: String): String {
            if (filename.count { it == ':' } >= 8) return filename
            return "1:0:0:0:0:0:0:0:0:0:$filename"
        }

        private fun matchTimer(timers: List<OpenWebifJson.ReceiverTimer>, item: Recording): OpenWebifJson.ReceiverTimer? {
            timers.firstOrNull { com.enigmacast.domain.recording.TimerMatch.same(it.reference, it.begin, it.end, it.eventId, item) }
                ?.let { return it }
            return timers.firstOrNull {
                it.name.equals(item.title, true) && it.begin <= item.beginEpochSec + 60 && it.end >= item.endEpochSec - 60
            }
        }

        private fun matchMovie(movies: List<OpenWebifJson.ReceiverMovie>, item: Recording): OpenWebifJson.ReceiverMovie? {
            return movies.filter { movie -> movie.name.equals(item.title, true) }
                .minByOrNull { movie ->
                    if (movie.begin == 0L) Long.MAX_VALUE / 4 else kotlin.math.abs(movie.begin - item.beginEpochSec)
                }
        }
    }
}
