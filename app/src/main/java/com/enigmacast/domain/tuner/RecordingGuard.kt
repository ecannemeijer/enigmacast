package com.enigmacast.domain.tuner

/** A recording that needs a tuner: a box timer or this app's own capture. */
data class PlannedRecording(
    val reference: String,
    val channelName: String,
    val beginEpochSec: Long,
    val endEpochSec: Long,
    /** Programme title, for messages. */
    val title: String = "",
    /** End of the programme itself (without the box margin), for "until hh:mm". 0: [endEpochSec]. */
    val programmeEndEpochSec: Long = 0,
    /** Set for a box timer: its own begin/end, as `/api/timerdelete` needs them. */
    val timer: Boolean = false,
    /** Set for this app's own recording (on this device or a box timer it made). */
    val recordingId: String? = null,
) {
    val untilEpochSec: Long get() = if (programmeEndEpochSec > 0) programmeEndEpochSec else endEpochSec
}

/** Recordings that keep [target] from getting a tuner, and the channel to go to once one is cancelled. */
data class TunerConflict(val target: com.enigmacast.domain.model.BouquetEntry, val recordings: List<PlannedRecording>)

object RecordingConflicts {
    /**
     * The running recordings to offer (Watch / Cancel) when [target] found no free tuner:
     * every recording on another transponder, one entry per recording. Empty when the
     * target shares a recording's transponder (it could not be blocked by recordings) or
     * when nothing records (the tuners are taken by other viewers).
     */
    fun blockers(running: List<PlannedRecording>, target: String): List<PlannedRecording> {
        if (!ServiceRef.needsTuner(target)) return emptyList()
        if (running.any { ServiceRef.sameTransponder(it.reference, target) }) return emptyList()
        return running.filter { ServiceRef.needsTuner(it.reference) }
            .distinctBy { Triple(it.reference, it.beginEpochSec, it.recordingId) }
            .sortedBy { it.untilEpochSec }
    }

    /**
     * A stream that failed to start while recordings run: offer them, whatever `/api/about`
     * says about free tuners. A tuner listed as free may not reach the satellite (loop-through,
     * no cable on that input, other position), so the box's free count cannot be trusted here.
     */
    fun afterStreamFailure(health: StreamHealth, running: List<PlannedRecording>, target: String): List<PlannedRecording> =
        if (health in START_FAILURES) blockers(running, target) else emptyList()

    /** Running recordings from the box's timer list (state 2, not zap-only) when the guard has none yet. */
    fun fromSnapshot(snapshot: TunerSnapshot?, nameOf: (String) -> String): List<PlannedRecording> =
        snapshot?.recordingReferences.orEmpty().distinct().map { PlannedRecording(it, nameOf(it), 0, 0) }

    val START_FAILURES = setOf(StreamHealth.NO_TUNER, StreamHealth.NO_PICTURE, StreamHealth.FAILED)
}

sealed interface StartAction {
    data object None : StartAction
    /** All tuners are taken and this device's stream holds one on another transponder: move it to the recording. */
    data class SwitchTo(val recording: PlannedRecording) : StartAction
}

sealed interface ZapDecision {
    data object Allowed : ZapDecision
    /** No tuner left while [recording] runs; only its transponder can be watched. */
    data class Blocked(val recording: PlannedRecording) : ZapDecision
}

/**
 * Keeps the box able to record when the tuners run out.
 *
 * Before a recording begins, this device's own stream is counted as busy (unlike a zap,
 * where the app first releases it). When the recording's transponder is not tuned and
 * no tuner is left, this device's stream is what blocks it, so the app moves its picture
 * to the recording service; both then share one tuner. Other devices' streams (multi-user,
 * one tuner each) stay counted as busy: only this device's stream is ever moved.
 */
object RecordingGuard {
    /** Seconds before a recording's begin at which the switch happens. */
    const val LEAD_SEC = 15L

    fun sameTransponder(a: String, b: String): Boolean = ServiceRef.sameTransponder(a, b)

    /** Recordings starting within [LEAD_SEC] (or already started less than [graceSec] ago). */
    fun dueNow(recordings: List<PlannedRecording>, nowSec: Long, graceSec: Long = 60): List<PlannedRecording> =
        recordings.filter { it.beginEpochSec - LEAD_SEC <= nowSec && nowSec < it.beginEpochSec + graceSec && nowSec < it.endEpochSec }

    fun running(recordings: List<PlannedRecording>, nowSec: Long): List<PlannedRecording> =
        recordings.filter { it.beginEpochSec <= nowSec && nowSec < it.endEpochSec }

    fun atRecordingStart(
        snapshot: TunerSnapshot,
        recording: PlannedRecording,
        ownReference: String?,
        ownAddresses: Set<String>,
    ): StartAction {
        val own = ownReference?.takeIf { ServiceRef.needsTuner(it) } ?: return StartAction.None
        if (!ServiceRef.needsTuner(recording.reference)) return StartAction.None
        if (sameTransponder(own, recording.reference)) return StartAction.None
        val ownNorm = ownAddresses.map(ServiceRef::normalizeAddress).toSet()
        val streamsHere = snapshot.streams.any { ServiceRef.normalizeAddress(it.address) in ownNorm }
        // Count this device's stream as a tuner user: pass no own addresses.
        val withOwnStream = if (streamsHere) snapshot else snapshot.withOwnStream(own, ownNorm)
        return when (TunerAvailability.evaluate(withOwnStream, recording.reference, emptySet())) {
            is TunerVerdict.Full -> StartAction.SwitchTo(recording)
            else -> StartAction.None
        }
    }

    /** May this device zap to [target] while [running] recordings use the box? */
    fun zap(
        snapshot: TunerSnapshot?,
        target: String,
        running: List<PlannedRecording>,
        ownAddresses: Set<String>,
    ): ZapDecision {
        if (running.isEmpty() || !ServiceRef.needsTuner(target)) return ZapDecision.Allowed
        if (running.any { sameTransponder(it.reference, target) }) return ZapDecision.Allowed
        val withRecordings = (snapshot ?: TunerSnapshot(emptyList())).let { s ->
            s.copy(recordingReferences = (s.recordingReferences + running.map { it.reference }).distinct())
        }
        if (withRecordings.tuners.isEmpty()) return ZapDecision.Allowed
        return when (TunerAvailability.evaluate(withRecordings, target, ownAddresses)) {
            is TunerVerdict.Full -> ZapDecision.Blocked(running.firstOrNull { ServiceRef.needsTuner(it.reference) } ?: running.first())
            else -> ZapDecision.Allowed
        }
    }

    /** Adds this device's stream when the box did not list it (some images omit the client list). */
    private fun TunerSnapshot.withOwnStream(reference: String, own: Set<String>): TunerSnapshot {
        if (streams.any { ServiceRef.normalizeAddress(it.address) in own }) return this
        return copy(streams = streams + StreamClient(reference, "", own.firstOrNull() ?: "self"))
    }
}
