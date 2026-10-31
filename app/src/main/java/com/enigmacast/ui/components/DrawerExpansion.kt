package com.enigmacast.ui.components

/**
 * Whether the side menu shows its labels. Open while the menu has focus, closed while the page
 * has it. With focus nowhere the menu opens, except right after arriving on a page that takes
 * focus itself (Back from live TV into the guide, back from a film): then it stays closed, so it
 * does not open on the first frame and fold away again once the page grabs focus.
 */
object DrawerExpansion {
    /** How long a page may take to focus its content before the menu opens anyway. */
    const val CONTENT_ENTRY_GRACE_MS = 1_500L

    fun expanded(navFocused: Boolean, contentFocused: Boolean, awaitingContent: Boolean): Boolean = when {
        navFocused -> true
        contentFocused -> false
        else -> !awaitingContent
    }
}
