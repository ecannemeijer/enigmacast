package com.enigmacast.ui.dashboard

/** D-pad Right from the left menu into the open page. */
internal object MenuRight {
    /** Frames to wait for the page's own entry before taking its first focusable. */
    const val FALLBACK_FRAMES = 12

    /**
     * Restore the last focused element of the page only when that page had focus since it was
     * opened. Search and My items first switch their rows on (focusable only while active),
     * so they always take their own entry.
     */
    fun mayRestore(open: DashSection, visited: DashSection?): Boolean =
        visited == open && open != DashSection.Search && open != DashSection.MyItems

    /**
     * OK on the menu item of the page that is open, after Left brought focus from that page into
     * the menu: back to the element focused there (like Right) instead of the page's first entry.
     * OK after Back, or on another section, keeps opening the page at its entry.
     */
    fun okRestores(target: DashSection, open: DashSection, visited: DashSection?, viaLeft: Boolean): Boolean =
        viaLeft && target == open && mayRestore(open, visited)
}
