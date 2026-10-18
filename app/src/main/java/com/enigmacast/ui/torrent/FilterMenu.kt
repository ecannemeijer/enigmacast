package com.enigmacast.ui.torrent

/** Size and place of the Genre/Sort dropdowns on the Torrent page. */
internal object FilterMenu {
    const val MIN_WIDTH_DP = 200
    const val MAX_WIDTH_DP = 320
    /** About eight rows; longer lists (Genre) scroll inside. */
    const val MAX_HEIGHT_DP = 300

    /** First row shown so the selected one is visible with a few rows above it. */
    fun firstVisible(selected: Int): Int = (selected - 3).coerceAtLeast(0)

    /**
     * Left-aligned under the anchor ([gapPx] below it), kept [marginPx] inside the window.
     * When it does not fit below, it goes as low as it can without leaving the screen.
     */
    fun position(
        anchorLeft: Int, anchorBottom: Int,
        windowWidth: Int, windowHeight: Int,
        popupWidth: Int, popupHeight: Int,
        gapPx: Int, marginPx: Int,
    ): Pair<Int, Int> {
        val maxX = (windowWidth - popupWidth - marginPx).coerceAtLeast(marginPx)
        val maxY = (windowHeight - popupHeight - marginPx).coerceAtLeast(marginPx)
        return anchorLeft.coerceIn(marginPx, maxX) to (anchorBottom + gapPx).coerceIn(marginPx, maxY)
    }
}
