package com.enigmacast.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerExpansionTest {
    @Test fun menuFocusOpensIt() {
        assertTrue(DrawerExpansion.expanded(navFocused = true, contentFocused = false, awaitingContent = false))
        assertTrue(DrawerExpansion.expanded(navFocused = true, contentFocused = false, awaitingContent = true))
    }

    @Test fun pageFocusClosesIt() {
        assertFalse(DrawerExpansion.expanded(navFocused = false, contentFocused = true, awaitingContent = false))
    }

    @Test fun arrivingOnAPageThatTakesFocusStaysClosed() {
        assertFalse(DrawerExpansion.expanded(navFocused = false, contentFocused = false, awaitingContent = true))
    }

    @Test fun noFocusAnywhereOtherwiseOpensIt() {
        assertTrue(DrawerExpansion.expanded(navFocused = false, contentFocused = false, awaitingContent = false))
    }
}
