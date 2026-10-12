package com.enigmacast.ui.soccer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The chain follows the drawn order: hero → match rows → official table → PitchAPI rows; edges stay. */
class FocusChainTest {
    @Test
    fun neighboursFollowVisualOrder() {
        val chain = FocusChain(HashMap())
        chain.keys = listOf("top", "fd-today", "fd-up-9", "fd-recent", "fd-table", "p-res-l1", "p-up-l1", "p-clubs-l1")
        assertEquals("fd-today", chain.neighbourKey("top", 1))
        assertNull(chain.neighbourKey("top", -1))
        assertEquals("fd-recent", chain.neighbourKey("fd-table", -1))
        assertEquals("p-res-l1", chain.neighbourKey("fd-table", 1))
        assertNull(chain.neighbourKey("p-clubs-l1", 1))
        // A section that disappears (e.g. no live matches any more) drops out without leaving a hole.
        chain.keys = chain.keys - "fd-up-9"
        assertEquals("fd-recent", chain.neighbourKey("fd-today", 1))
    }
}
