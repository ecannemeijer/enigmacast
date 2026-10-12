package com.enigmacast.live

import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.tuner.PlannedRecording
import com.enigmacast.domain.tuner.TunerConflict
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/** Actions of the "recording running" dialog that replaces the tuners-full message. */
@Singleton
class TunerConflictController @Inject constructor(
    private val session: OwnTunerSession,
    private val search: ProgrammeSearch,
    private val recordings: RecordingBook,
    private val api: OpenWebifApi,
    private val settingsRepository: SettingsRepository,
    private val guard: RecordingTunerGuard,
) {
    val conflict get() = session.conflict

    fun close() = session.setConflict(null)

    /** Stream the recording's channel, switching (and remembering) the bouquet that holds it. */
    suspend fun watch(recording: PlannedRecording) {
        session.setConflict(null)
        val place = runCatching { search.placeOf(recording.reference) }.getOrNull()
        if (place != null) {
            session.start(place.channel, place.channels, place.bouquet.name, place.bouquet.reference)
        } else {
            session.start(BouquetEntry(recording.channelName, recording.reference))
        }
    }

    /** Stop [recording]; when nothing else blocks, go on to the channel that was asked for. */
    suspend fun cancel(conflict: TunerConflict, recording: PlannedRecording) {
        val receiver = settingsRepository.current().activeReceiver()
        if (recording.timer && receiver != null) {
            runCatching { api.deleteTimer(receiver, recording.reference, recording.beginEpochSec, recording.endEpochSec) }
                .onFailure { EnigmaLog.service("timer delete failed: ${it.javaClass.simpleName}") }
            recording.recordingId?.let { runCatching { recordings.stop(it) } }
        } else {
            recording.recordingId?.let { recordings.stop(it) }
        }
        session.runningRecordings = session.runningRecordings - recording
        guard.invalidate()
        // Always retry: the freed tuner may be enough. If other recordings still block, the dialog returns with them.
        session.setConflict(null)
        delay(RELEASE_MS) // let the box free the tuner
        session.start(conflict.target, force = true)
    }

    private companion object {
        const val RELEASE_MS = 1500L
    }
}
