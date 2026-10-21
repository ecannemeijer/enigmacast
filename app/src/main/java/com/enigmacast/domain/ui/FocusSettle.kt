package com.enigmacast.domain.ui

/**
 * How long focus must rest on a row before focus-driven work starts (loading a bouquet, the
 * preview pane, picons). Holding Up/Down repeats a key every ~50 ms, so a row that is only passed
 * never reaches these delays and costs nothing.
 */
object FocusSettle {
    /** Channels of a focused bouquet. */
    const val BOUQUET_MS = 350L
    /** Preview pane / programme details of the focused row. */
    const val PREVIEW_MS = 250L
    /** A picon that is not cached yet; rows scrolled past within this time never load one. */
    const val PICON_MS = 150L
}

/**
 * Key repeat for D-pad keys: at most one repeat per drawn frame, and a repeat that waited in the
 * queue longer than [maxLagMs] is dropped. When a move costs more than the repeat interval the
 * events used to pile up; the list then kept scrolling after the key was released and felt stuck.
 * Pure, so it is unit tested; the activity feeds it the frame counter and clock.
 */
class KeyRepeatGate(private val maxLagMs: Long = 120) {
    private var lastFrame = -1L

    /**
     * [repeatCount] > 0 marks a held key. [eventTimeMs] is when the event was made, [nowMs] the
     * current uptime and [frame] the number of frames drawn so far.
     */
    fun accept(repeatCount: Int, eventTimeMs: Long, nowMs: Long, frame: Long): Boolean {
        if (repeatCount == 0) {
            lastFrame = frame
            return true
        }
        if (nowMs - eventTimeMs > maxLagMs) return false
        if (frame == lastFrame) return false
        lastFrame = frame
        return true
    }
}
