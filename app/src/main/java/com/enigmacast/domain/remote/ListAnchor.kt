package com.enigmacast.domain.remote

/**
 * Where a channel / category list sits so that its anchor (the playing channel, the current
 * category) is in the middle when the list opens: no scrolling after it appears.
 */
object ListAnchor {
    /** Rows on screen until the list has been laid out once (a 1080p channel list shows ~15). */
    const val DEFAULT_ROWS = 14

    /** The first row to show for [anchor] in the middle of [rows] rows of a [size]-row list. */
    fun firstVisible(anchor: Int, rows: Int, size: Int): Int {
        if (size <= 0 || anchor < 0) return 0
        val shown = rows.coerceAtLeast(1)
        return (anchor.coerceAtMost(size - 1) - shown / 2).coerceIn(0, (size - shown).coerceAtLeast(0))
    }

    /** [anchor] is fully on screen between the first and last visible rows (the edge rows may be cut off). */
    fun onScreen(anchor: Int, firstVisible: Int, lastVisible: Int): Boolean =
        anchor in (firstVisible + 1) until lastVisible || (firstVisible == 0 && anchor == 0)
}
