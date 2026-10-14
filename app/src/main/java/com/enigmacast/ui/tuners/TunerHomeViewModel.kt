package com.enigmacast.ui.tuners

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.recording.RecordingPlace
import com.enigmacast.domain.recording.RecordingStatus
import com.enigmacast.domain.sync.GuideCacheReader
import com.enigmacast.domain.tuner.RunningTimer
import com.enigmacast.domain.tuner.ScheduleItem
import com.enigmacast.domain.tuner.ScheduleKind
import com.enigmacast.domain.tuner.TodaySchedule
import com.enigmacast.domain.tuner.TunerBoard
import com.enigmacast.domain.tuner.TunerCard
import com.enigmacast.live.OwnTunerSession
import com.enigmacast.live.PiconRepository
import com.enigmacast.live.RecordingBook
import com.enigmacast.live.ReminderScheduler
import com.enigmacast.playback.LivePlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Extra facts about the channel a tuner card shows. */
data class CardChannel(val bouquet: String = "", val now: GuideEvent? = null)

@HiltViewModel
class TunerHomeViewModel @Inject constructor(
    private val listEvents: com.enigmacast.sync.ChannelListEvents,
    private val switcher: com.enigmacast.live.ReceiverSwitcher,
    private val settingsRepository: SettingsRepository,
    private val api: OpenWebifApi,
    private val guideCache: GuideCacheReader,
    private val picons: PiconRepository,
    private val reminders: ReminderScheduler,
    private val recordings: RecordingBook,
    private val session: OwnTunerSession,
    private val playback: LivePlaybackController,
    private val seriesRecorder: com.enigmacast.live.SeriesRecorder,
    private val cacheDao: com.enigmacast.data.db.CacheDao,
) : ViewModel() {
    fun openSwitcher() = switcher.show()

    init {
        // Another box was chosen: drop the old box's cards at once and load the new one,
        // instead of showing the old box until the next poll.
        viewModelScope.launch {
            // New lists on the active box (settings import): reload the cards' channel facts.
            listEvents.changed.collect { id -> if (id == settingsRepository.current().activeReceiver()?.id) refresh() }
        }
        viewModelScope.launch {
            settingsRepository.settings.map { it.activeReceiver()?.id }.distinctUntilChanged().drop(1).collect {
                cards = emptyList()
                channelInfo = emptyMap()
                schedule = emptyList()
                series = emptyList()
                failed = false
                inStandby = false
                loading = true
                refresh()
            }
        }
    }

    /** Series recordings: one card per series and channel. */
    var series by mutableStateOf<List<com.enigmacast.domain.series.SeriesGroup>>(emptyList()); private set
    var posters by mutableStateOf<Map<String, String?>>(emptyMap()); private set
    private var groupedKeys: Set<String> = emptySet()
    var cards by mutableStateOf<List<TunerCard>>(emptyList()); private set
    var channelInfo by mutableStateOf<Map<String, CardChannel>>(emptyMap()); private set
    var schedule by mutableStateOf<List<ScheduleItem>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var failed by mutableStateOf(false); private set
    var inStandby by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    /** Polls while Home is on screen (the caller's LaunchedEffect cancels it). */
    suspend fun whileVisible() {
        while (true) {
            refresh()
            delay(REFRESH_MS)
        }
    }

    suspend fun refresh() {
        val receiver = settingsRepository.current().activeReceiver()
        if (receiver == null) { loading = false; failed = true; return }
        val timers = runCatching { api.timers(receiver) }.getOrDefault(emptyList())
        val snapshot = runCatching { api.tunerSnapshot(receiver) }.getOrNull()
        // The box changed while this box was asked: its answer no longer belongs on Home.
        if (settingsRepository.current().activeReceiver()?.id != receiver.id) return
        if (snapshot == null) {
            failed = true
        } else {
            failed = false
            inStandby = snapshot.inStandby == true
            val running = timers.filter { it.state == 2 }.map { RunningTimer(it.reference, it.name) }
            cards = TunerBoard.cards(snapshot, ownAddresses(receiver), running)
            loadChannelInfo(receiver)
        }
        buildSeries(receiver, timers)
        schedule = buildSchedule(receiver, timers)
        loading = false
    }

    private suspend fun loadChannelInfo(receiver: ReceiverConfig) {
        val nowSec = System.currentTimeMillis() / 1000
        val next = mutableMapOf<String, CardChannel>()
        val refs = cards.flatMap { c -> c.uses.map { it.reference } }.filter { it.isNotBlank() }.distinct()
        val started = System.nanoTime()
        for (ref in refs) {
            val bouquet = runCatching { guideCache.bouquetOf(receiver, ref)?.name }.getOrNull().orEmpty()
            val events = runCatching { guideCache.serviceEvents(receiver, ref) }.getOrNull()
                ?.takeIf { list -> list.any { it.covers(nowSec) } }
                ?: runCatching { api.serviceEvents(receiver, ref) }.getOrDefault(emptyList())
            next[ref] = CardChannel(bouquet, events.firstOrNull { it.covers(nowSec) })
        }
        // #region agent log
        android.util.Log.i(
            "DBG-ee9576",
            """{"sessionId":"ee9576","runId":"post-fix","hypothesisId":"D","location":"TunerHomeViewModel.kt:loadChannelInfo","message":"home channel info","data":{"refs":${refs.size},"ms":${(System.nanoTime() - started) / 1_000_000}},"timestamp":${System.currentTimeMillis()}}""",
        )
        // #endregion
        channelInfo = next
    }

    private fun GuideEvent.covers(nowSec: Long) = beginEpochSec <= nowSec && beginEpochSec + durationSec > nowSec

    /** Today's cards and the coming week's, one per programme (box timers matched with the app's own). */
    private suspend fun buildSchedule(receiver: ReceiverConfig, timers: List<com.enigmacast.data.openwebif.OpenWebifJson.ReceiverTimer>): List<ScheduleItem> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toEpochSecond()
        val end = today.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val weekEnd = today.plusDays(8).atStartOfDay(zone).toEpochSecond()
        val nowSec = System.currentTimeMillis() / 1000
        val ours = recordings.items.value.filter { it.isActive() && it.receiverId == receiver.id }
        val items = mutableListOf<ScheduleItem>()
        ours.filter { com.enigmacast.domain.series.SeriesGrouping.keyOf(it) !in groupedKeys }.forEach { r ->
            val timer = if (r.place == RecordingPlace.RECEIVER) timers.firstOrNull { t -> com.enigmacast.domain.recording.TimerMatch.same(t.reference, t.begin, t.end, t.eventId, r) } else null
            items += ScheduleItem(
                r.beginEpochSec, r.endEpochSec, r.channelName, r.title,
                if (r.place == RecordingPlace.TV) ScheduleKind.TV_RECORDING else ScheduleKind.BOX_RECORDING,
                running = r.status == RecordingStatus.RECORDING || timer?.state == 2,
                reference = r.reference,
                recordingId = r.id,
                marginBeforeMin = ((timer?.marginBefore ?: 0) / 60).toInt(),
                marginAfterMin = ((timer?.marginAfter ?: 0) / 60).toInt(),
            )
        }
        timers.filter { it.state < 3 }
            // One entry per recording: a box timer that the app made is already listed above.
            .filter { t -> ours.none { it.place == RecordingPlace.RECEIVER && com.enigmacast.domain.recording.TimerMatch.same(t.reference, t.begin, t.end, t.eventId, it) } }
            .filter { t -> com.enigmacast.domain.series.SeriesDetector.key(t.name, t.reference) !in groupedKeys }
            .forEach { t ->
                val channel = channelName(receiver, t.reference)
                items += ScheduleItem(
                    t.eventBegin, t.eventEnd, channel, t.name, ScheduleKind.BOX_TIMER,
                    running = t.state == 2, reference = t.reference,
                    boxTimer = com.enigmacast.domain.tuner.BoxTimerRef(t.reference, t.begin, t.end),
                    marginBeforeMin = (t.marginBefore / 60).toInt(), marginAfterMin = (t.marginAfter / 60).toInt(),
                )
            }
        reminders.reminders.value.filter { it.receiverId.isBlank() || it.receiverId == receiver.id }.forEach { r ->
            items += ScheduleItem(r.beginEpochSec, r.beginEpochSec, r.channelName, r.title, ScheduleKind.AUTOSWITCH, reference = r.serviceReference, reminderKey = r.key)
        }
        upcoming = TodaySchedule.merge(items, end, weekEnd, 0L)
        loadPosters(receiver, items)
        return TodaySchedule.merge(items, start, end, nowSec)
    }

    /** The coming week after today. */
    var upcoming by mutableStateOf<List<ScheduleItem>>(emptyList()); private set
    /** TMDB poster path by cleaned title (null: none in the cache). */
    var titlePosters by mutableStateOf<Map<String, String?>>(emptyMap()); private set

    private suspend fun loadPosters(receiver: ReceiverConfig, items: List<ScheduleItem>) {
        val rk = com.enigmacast.data.db.CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)
        val next = titlePosters.toMutableMap()
        for (title in items.map { it.title }.distinct()) {
            if (title in next) continue
            val names = listOf(com.enigmacast.domain.films.FilmDetector.cleanTitle(title), com.enigmacast.domain.series.SeriesDetector.cleanTitle(title)).distinct()
            next[title] = runCatching { cacheDao.posterFor(rk, names) }.getOrNull()
        }
        titlePosters = next
    }

    fun piconFor(item: ScheduleItem): BouquetEntry = BouquetEntry(item.channelName, item.reference)

    /** Cancel from a Home card: the app's recording, a box timer (by the box's own begin/end), or an AutoSwitch. */
    fun cancel(item: ScheduleItem) {
        viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver()
            when {
                item.recordingId != null -> recordings.delete(item.recordingId)
                item.boxTimer != null && receiver != null -> runCatching { api.deleteTimer(receiver, item.boxTimer.reference, item.boxTimer.begin, item.boxTimer.end) }
                item.reminderKey != null -> reminders.cancel(item.reminderKey)
            }
            refresh()
        }
    }

    private suspend fun buildSeries(receiver: ReceiverConfig, timers: List<com.enigmacast.data.openwebif.OpenWebifJson.ReceiverTimer>) {
        val nowSec = System.currentTimeMillis() / 1000
        val ours = recordings.items.value.filter { it.isActive() && it.receiverId == receiver.id && it.endEpochSec > nowSec }
        val box = timers.filter { t -> t.state < 3 && t.end > nowSec }
            .filter { t -> ours.none { kotlin.math.abs(it.beginEpochSec - t.begin) < 300 && com.enigmacast.domain.tuner.NowEvents.key(it.reference) == com.enigmacast.domain.tuner.NowEvents.key(t.reference) } }
            .map { t ->
                com.enigmacast.domain.recording.Recording(
                    id = com.enigmacast.live.RecordingBook.BOX_TIMER + t.reference + "|" + t.begin,
                    title = t.name, channelName = channelName(receiver, t.reference), reference = t.reference, eventId = t.eventId,
                    beginEpochSec = t.begin, endEpochSec = t.end, place = RecordingPlace.RECEIVER,
                    status = if (t.state == 2) RecordingStatus.RECORDING else RecordingStatus.SCHEDULED, receiverId = receiver.id,
                )
            }
        val rules = kotlinx.coroutines.withTimeoutOrNull(2_000) { seriesRecorder.rules.first() }.orEmpty()
            .filter { it.receiverId == receiver.id }
            .associate { "${it.titleKey}|${it.serviceKey}" to (it.title to it.channelName) }
        val (groups, _) = com.enigmacast.domain.series.SeriesGrouping.group(ours + box, rules)
        series = groups
        groupedKeys = groups.map { it.key }.toSet()
        val rk = com.enigmacast.data.db.CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)
        val next = posters.toMutableMap()
        for (g in groups) if (g.key !in next) {
            next[g.key] = runCatching { cacheDao.posterFor(rk, listOf(com.enigmacast.domain.series.SeriesDetector.cleanTitle(g.title), g.title).distinct()) }.getOrNull()
        }
        posters = next
    }

    /** Cancels one episode (an app recording or a box timer). */
    fun cancelEpisode(item: com.enigmacast.domain.recording.Recording) {
        viewModelScope.launch {
            if (item.id.startsWith(com.enigmacast.live.RecordingBook.BOX_TIMER)) recordings.deleteBox(item) else recordings.delete(item.id)
            refresh()
        }
    }

    /** Cancel all: timers, AutoTimer and the series rule. */
    fun cancelSeries(group: com.enigmacast.domain.series.SeriesGroup) {
        viewModelScope.launch {
            seriesRecorder.cancelAll(group.key, group.episodes)
            message = null
            refresh()
        }
    }

    private val channelNames = mutableMapOf<String, String>()

    private suspend fun channelName(receiver: ReceiverConfig, reference: String): String {
        channelNames[reference]?.let { return it }
        val name = runCatching { guideCache.bouquetOf(receiver, reference) }.getOrNull()?.let { b ->
            guideCache.channelsInBouquet(receiver, b.reference)?.firstOrNull { it.reference == reference }?.name
        }.orEmpty()
        channelNames[reference] = name
        return name
    }

    fun cachedPicon(reference: String, name: String): ImageBitmap? = picons.cached(BouquetEntry(name, reference))

    suspend fun picon(reference: String, name: String): ImageBitmap? {
        val receiver = settingsRepository.current().activeReceiver() ?: return null
        return runCatching { picons.load(receiver, BouquetEntry(name, reference)) }.getOrNull()
    }

    /** Stops this app's own stream (the tuner frees up on the box within a few seconds). */
    fun stopOwnStream() {
        session.deactivate()
        playback.stop()
        viewModelScope.launch { delay(1500); refresh() }
    }

    /** Puts the receiver in standby: its live picture stops and that tuner is freed. */
    fun standby() {
        viewModelScope.launch {
            val receiver = settingsRepository.current().activeReceiver() ?: return@launch
            runCatching { api.setPowerState(receiver, PowerAction.STANDBY) }
            delay(1500)
            refresh()
        }
    }

    private suspend fun ownAddresses(receiver: ReceiverConfig): Set<String> = withContext(Dispatchers.IO) {
        val addresses = mutableSetOf<String>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress(receiver.host, receiver.streamingPort))
                socket.localAddress?.hostAddress?.let(addresses::add)
            }
        }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .mapNotNull { it.hostAddress }
                .forEach(addresses::add)
        }
        addresses
    }

    private companion object {
        const val REFRESH_MS = 15_000L
    }
}
