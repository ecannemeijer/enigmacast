package com.enigmacast.domain.remote

/**
 * Where a channel / category list sits so that its anchor (the playing channel, the current
 * category) is in the middle when the list opens. One rule for both the position set while the
 * list is hidden and the check when it opens, and focusing the anchor must not move it either
 * (the lists use [bringDistance], not the TV default that pulls the focused row to ~30 % from
 * the top).
 */
object ListAnchor {
    /** Rows assumed to fit fully before the list was ever laid out (any TV layout shows more). */
    const val DEFAULT_ROWS = 10

    /** Rows that fit fully in [viewportPx] (content padding already removed) at [pitchPx] per row. */
    fun fullRows(viewportPx: Int, pitchPx: Int): Int =
        if (viewportPx <= 0 || pitchPx <= 0) DEFAULT_ROWS else (viewportPx / pitchPx).coerceAtLeast(1)

    /** The first row to show for [anchor] in the middle of [rows] fully visible rows of a [size]-row list. */
    fun firstVisible(anchor: Int, rows: Int, size: Int): Int {
        if (size <= 0 || anchor < 0) return 0
        val shown = rows.coerceAtLeast(1)
        return (anchor.coerceAtMost(size - 1) - (shown - 1) / 2).coerceIn(0, (size - shown).coerceAtLeast(0))
    }

    /** The row from [itemStart] to [itemEnd] lies inside the viewport [viewStart]..[viewEnd]. */
    fun fullyVisible(itemStart: Int, itemEnd: Int, viewStart: Int, viewEnd: Int): Boolean =
        itemStart >= viewStart && itemEnd <= viewEnd

    /**
     * How far a list scrolls to show a focused row at [offset] (from the viewport start) of
     * [size] in a viewport of [container]: nothing when it is fully visible, else just enough.
     */
    fun bringDistance(offset: Float, size: Float, container: Float): Float {
        return bringDistance(offset, size, container, 0f)
    }

    /**
     * As [bringDistance], with [margin] kept free on both sides of the row (a focused card is
     * drawn scaled beyond its bounds). Without the room for it, the plain rule.
     */
    fun bringDistance(offset: Float, size: Float, container: Float, margin: Float): Float {
        if (margin > 0f && size + 2 * margin <= container) return bringDistance(offset - margin, size + 2 * margin, container, 0f)
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= container -> 0f
            offset < 0f || size > container -> offset
            else -> trailing - container
        }
    }
}
