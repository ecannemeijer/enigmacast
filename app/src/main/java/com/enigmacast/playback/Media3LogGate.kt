package com.enigmacast.playback

/**
 * Filters Media3's own log lines. DefaultLoadControl warns on every load check while the byte
 * limit is reached with under 500 ms buffered (thousands of lines during one stalled start).
 * Release builds drop that tag; debug builds keep the first line and then at most one per
 * [windowMs] for the same tag and text, with how many were held back. Other lines pass as they are.
 */
class Media3LogGate(
    private val release: Boolean,
    private val windowMs: Long = WINDOW_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Seen(var lastShownMs: Long, var held: Int)

    private val seen = LinkedHashMap<String, Seen>()

    /** The line to write for this message, or null to drop it. */
    @Synchronized
    fun admit(tag: String, message: String): String? {
        if (tag !in NOISY_TAGS) return message
        if (release) return null
        val key = "$tag\u0000$message"
        val at = now()
        val entry = seen[key]
        if (entry == null) {
            if (seen.size >= MAX_KEYS) seen.remove(seen.keys.first())
            seen[key] = Seen(at, 0)
            return message
        }
        if (at - entry.lastShownMs < windowMs) {
            entry.held++
            return null
        }
        val held = entry.held
        entry.lastShownMs = at
        entry.held = 0
        return if (held == 0) message else "$message (+$held suppressed)"
    }

    companion object {
        const val WINDOW_MS = 5_000L
        private const val MAX_KEYS = 32
        val NOISY_TAGS = setOf("DefaultLoadControl")
    }
}
