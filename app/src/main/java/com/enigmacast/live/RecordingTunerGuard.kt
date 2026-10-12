package com.enigmacast.live

import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.StreamMode
import com.enigmacast.domain.recording.RecordingPlace
import com.enigmacast.domain.source.TunerStatusSource
import com.enigmacast.domain.tuner.PlannedRecording
import com.enigmacast.domain.tuner.RecordingGuard
import com.enigmacast.domain.tuner.StartAction
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches box timers and this app's own recordings while this device streams with its own
 * tuner. A few seconds before a recording begins, when no tuner would be left for it and
 * this device's stream is on another transponder, the stream moves to the recording's
 * service. While a recording runs, [OwnTunerSession] uses the running list to refuse zaps
 * to other transponders when no tuner is free.
 */
@Singleton
class RecordingTunerGuard @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val api: OpenWebifApi,
    private val tunerSource: TunerStatusSource,
    private val session: OwnTunerSession,
    private val recordings: dagger.Lazy<RecordingBook>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var timers: List<PlannedRecording> = emptyList()
    private var timersAt = 0L
    private val handled = mutableSetOf<String>()

    /** Re-read the box timers on the next check (after one was cancelled). */
    fun invalidate() { timersAt = 0L }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { if (it is CancellationException) throw it }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun tick() {
        val settings = settingsRepository.current()
        val receiver = settings.activeReceiver() ?: run { session.runningRecordings = emptyList(); return }
        val nowSec = System.currentTimeMillis() / 1000
        if (nowSec - timersAt >= TIMER_REFRESH_SEC) {
            timers = runCatching { api.timers(receiver) }.getOrNull()
                ?.filter { it.state < 3 && !it.justPlay }
                ?.map { t ->
                    val mine = recordings.get().items.value.firstOrNull { r ->
                        r.place == RecordingPlace.RECEIVER && com.enigmacast.domain.recording.TimerMatch.same(t.reference, t.begin, t.end, t.eventId, r)
                    }
                    PlannedRecording(
                        t.reference, session.channelNameFor(t.reference, mine?.channelName ?: t.name), t.begin, t.end,
                        title = t.name, programmeEndEpochSec = t.eventEnd, timer = true, recordingId = mine?.id,
                    )
                }
                ?: timers
            timersAt = nowSec
        }
        val ours = recordings.get().items.value
            .filter { it.receiverId == receiver.id && it.place == RecordingPlace.TV && it.isActive() }
            .map { PlannedRecording(it.reference, it.channelName, it.beginEpochSec, it.endEpochSec, title = it.title, recordingId = it.id) }
        // Box recordings made by the app are box timers already; app captures stream from the box.
        val all = (timers + ours).distinctBy { it.reference to it.beginEpochSec / 60 }
        session.runningRecordings = RecordingGuard.running(all, nowSec)
        if (settings.streamMode != StreamMode.OWN_TUNER) return
        val own = session.streamingReference() ?: return
        for (due in RecordingGuard.dueNow(all, nowSec)) {
            val key = "${due.reference}|${due.beginEpochSec}"
            if (key in handled) continue
            if (RecordingGuard.sameTransponder(own, due.reference)) { handled += key; continue }
            val snapshot = runCatching { tunerSource.snapshot(receiver) }.getOrNull() ?: continue
            val action = RecordingGuard.atRecordingStart(snapshot, due, own, session.addressesFor(receiver))
            EnigmaLog.service("recording at ${due.beginEpochSec} on ${due.reference}: $action")
            handled += key
            if (action is StartAction.SwitchTo) {
                session.switchForRecording(action.recording)
                break
            }
        }
        if (handled.size > 200) handled.clear()
    }

    private companion object {
        const val TICK_MS = 5_000L
        const val TIMER_REFRESH_SEC = 30L
    }
}
