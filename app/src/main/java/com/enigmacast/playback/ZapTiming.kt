package com.enigmacast.playback

/**
 * How long one Enigma2 zap takes, step by step, from the key press to the first picture.
 *
 * One line per zap in logcat (`adb logcat -s EcZap`), with the milliseconds each step took:
 * `zap: reason=zap owif=412 prepare=2 connect=95 bytes=8 tracks=180 ready=240 frame=31 total=968 ms`.
 * Steps that did not happen show `-`. A zap that is replaced by the next one, or stopped, before
 * its first picture is logged with `unfinished=…`. No address, reference or credential goes in
 * the line; only the reason, the milliseconds and a few short notes (buffer mode, tuner wait).
 */
class ZapTimeline(private val clock: () -> Long) {
    enum class Step(val label: String) {
        /** OpenWebif before play: tuner check, stream target, the release wait. */
        OWIF("owif"),
        /** ExoPlayer got the new media item and prepare() was called. */
        PREPARE("prepare"),
        /** The receiver answered the stream request (HTTP headers). */
        CONNECT("connect"),
        /** The first stream bytes arrived. */
        BYTES("bytes"),
        /** The tracks are known (PAT/PMT parsed, formats read). */
        TRACKS("tracks"),
        /** The player has enough buffered to play. */
        READY("ready"),
        /** The first video frame is on screen. */
        FRAME("frame"),
    }

    private var startedAt = -1L
    private var reason = ""
    private var finished = true
    private var hasVideo = true
    private val marks = LinkedHashMap<Step, Long>()
    private val notes = LinkedHashMap<String, String>()

    /** A new zap starts now. Returns the line for the previous one when it never finished. */
    @Synchronized
    fun begin(reason: String): String? {
        val previous = if (!finished && startedAt >= 0) line("superseded") else null
        startedAt = clock()
        this.reason = reason
        finished = false
        hasVideo = true
        marks.clear()
        notes.clear()
        return previous
    }

    /** True while a zap is being timed (begun and not finished). */
    @Synchronized
    fun running(): Boolean = !finished && startedAt >= 0

    /** A short extra for the line (e.g. `buffer=fast`, `wait=0`). */
    @Synchronized
    fun note(key: String, value: String) {
        if (running()) notes[key] = value
    }

    /**
     * Marks [step] (only its first time per zap). Returns the finished line on the first
     * frame, or on READY for a stream without video (radio).
     */
    @Synchronized
    fun mark(step: Step, video: Boolean? = null): String? {
        if (!running() || step in marks) return null
        if (video != null) hasVideo = video
        marks[step] = clock() - startedAt
        val done = step == Step.FRAME || (!hasVideo && Step.READY in marks && Step.TRACKS in marks)
        if (!done) return null
        finished = true
        return line(null)
    }

    /** The zap ended without a picture (stop, error). Returns its line, or null when none ran. */
    @Synchronized
    fun abandon(why: String): String? {
        if (!running()) return null
        finished = true
        return line(why)
    }

    private fun line(unfinished: String?): String {
        val now = clock() - startedAt
        val parts = StringBuilder("zap: reason=").append(reason)
        var previous = 0L
        for (step in Step.entries) {
            val at = marks[step]
            parts.append(' ').append(step.label).append('=')
            if (at == null) {
                parts.append('-')
            } else {
                parts.append((at - previous).coerceAtLeast(0))
                previous = at
            }
        }
        val total = if (unfinished == null) marks.values.maxOrNull() ?: now else now
        parts.append(" total=").append(total).append(" ms")
        if (!hasVideo) parts.append(" video=none")
        notes.forEach { (key, value) -> parts.append(' ').append(key).append('=').append(value) }
        if (unfinished != null) parts.append(" unfinished=").append(unfinished)
        return parts.toString()
    }
}

/** The app-wide zap clock shared by the tuner session (key press, OpenWebif) and the player. */
object ZapTiming {
    const val TAG = "EcZap"
    private val timeline = ZapTimeline { System.nanoTime() / 1_000_000 }

    fun begin(reason: String) {
        timeline.begin(reason)?.let(::log)
    }

    fun running(): Boolean = timeline.running()

    fun note(key: String, value: String) = timeline.note(key, value)

    fun mark(step: ZapTimeline.Step, video: Boolean? = null) {
        timeline.mark(step, video)?.let(::log)
    }

    fun abandon(why: String) {
        timeline.abandon(why)?.let(::log)
    }

    // Straight to logcat, also in release builds (no Timber tree there): one short line per
    // zap with milliseconds only. Unit tests have no android.util.Log; the line is dropped there.
    private fun log(line: String) {
        runCatching { android.util.Log.i(TAG, line) }
    }
}
