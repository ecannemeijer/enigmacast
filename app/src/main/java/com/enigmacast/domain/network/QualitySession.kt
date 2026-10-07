package com.enigmacast.domain.network

/** Why the stream has its current quality. */
enum class QualityReason { START, STALLS, STABLE, MANUAL, FALLBACK }

/**
 * The quality decisions of one app run, without Android: which level a stream start gets,
 * the one-time fallback to the original stream when a transcoded start fails, stalls and
 * stable stretches (through [StreamQualityPolicy]), and the quick options override.
 *
 * Rules that keep a failure from looping:
 * - after a transcoder failure a start is always the original until the settings change;
 * - the fallback happens at most once per receiver, and only for a transcoded start that never played.
 */
class QualitySession {
    var inputs: QualityInputs? = null
        private set
    var level: StreamLevel = StreamLevel.DIRECT
        private set
    var manual: StreamLevel? = null
        private set
    var reason: QualityReason = QualityReason.START
        private set
    private var key = ""
    private var lastGood: StreamLevel? = null
    private val transcoderFailed = mutableSetOf<String>()
    private var streamingTranscoded = false
    private var reachedPlaying = false

    fun transcoderFailed(receiverKey: String): Boolean = receiverKey in transcoderFailed

    /** The level for a new stream start; [base] is what the receiver and route allow. */
    fun choose(receiverKey: String, base: QualityInputs): StreamLevel {
        val next = if (receiverKey in transcoderFailed) base.copy(transcoderFailed = true) else base
        if (next != inputs || receiverKey != key) {
            level = StreamQualityPolicy.initial(next, lastGood)
            if (receiverKey != key || reason != QualityReason.FALLBACK) reason = QualityReason.START
        }
        // Only Auto away from home adapts; everything else is fixed at its ceiling, so a level
        // reached earlier (for example over the tunnel) is never carried into a home stream.
        val ceiling = StreamQualityPolicy.ceiling(next)
        level = if (StreamQualityPolicy.adaptive(next)) StreamQualityPolicy.atMost(level, ceiling) else ceiling
        inputs = next
        key = receiverKey
        val chosen = manual?.let { if (it.transcoded && next.transcoderFailed) StreamLevel.DIRECT else it } ?: level
        streamingTranscoded = chosen.transcoded
        reachedPlaying = false
        return chosen
    }

    fun onPlaying() {
        reachedPlaying = true
    }

    /**
     * A start failed before the first picture. True when it was a transcoded start: the
     * receiver is then marked as not transcoding and the caller restarts once with the
     * original stream. False for anything else, so the caller does not restart for quality.
     */
    fun onStartFailed(): Boolean {
        if (!streamingTranscoded || reachedPlaying || key.isEmpty() || key in transcoderFailed) return false
        transcoderFailed += key
        streamingTranscoded = false
        inputs = inputs?.copy(transcoderFailed = true)
        level = StreamLevel.DIRECT
        if (manual?.transcoded == true) manual = null
        reason = QualityReason.FALLBACK
        return true
    }

    /** A lower level after repeated stalls, or null. */
    fun afterStalls(stallTimesMs: List<Long>, nowMs: Long): StreamLevel? {
        val current = inputs ?: return null
        if (manual != null) return null
        return StreamQualityPolicy.afterStalls(current, level, stallTimesMs, nowMs)?.also { change(it, QualityReason.STALLS) }
    }

    /** A higher level after a long stable stretch, or null. */
    fun afterStable(stableForMs: Long): StreamLevel? {
        val current = inputs ?: return null
        if (manual != null) return null
        return StreamQualityPolicy.afterStable(current, level, stableForMs)?.also { change(it, QualityReason.STABLE) }
    }

    /** The user's choice in the quick options; choosing a transcoded level gives the transcoder a new try. */
    fun setManual(choice: StreamLevel?) {
        manual = choice
        if (choice?.transcoded == true) transcoderFailed.remove(key)
        reason = if (choice == null) QualityReason.START else QualityReason.MANUAL
    }

    /** The stream type or port changed in the settings: the transcoder may work now. */
    fun forgetFailures() {
        transcoderFailed.clear()
        if (reason == QualityReason.FALLBACK) reason = QualityReason.START
    }

    fun ceiling(): StreamLevel = inputs?.let(StreamQualityPolicy::ceiling) ?: StreamLevel.DIRECT

    private fun change(next: StreamLevel, why: QualityReason) {
        level = next
        lastGood = next
        reason = why
    }
}
