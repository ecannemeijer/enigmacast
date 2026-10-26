package com.enigmacast.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRunRulesTest {
    @Test
    fun joinsOnlyARecentPullingRun() {
        assertTrue(SyncRunRules.canJoin(1_000L, runPulls = true, nowMs = 1_000L))
        assertTrue(SyncRunRules.canJoin(1_000L, runPulls = true, nowMs = 1_000L + SyncRunRules.JOIN_WINDOW_MS))
        assertFalse(SyncRunRules.canJoin(1_000L, runPulls = true, nowMs = 1_001L + SyncRunRules.JOIN_WINDOW_MS))
        assertFalse(SyncRunRules.canJoin(1_000L, runPulls = false, nowMs = 2_000L))
        assertFalse(SyncRunRules.canJoin(null, runPulls = true, nowMs = 2_000L))
        assertFalse(SyncRunRules.canJoin(5_000L, runPulls = true, nowMs = 4_000L))
    }

    @Test
    fun reusesARecentCheckOfTheSameHostOnly() {
        assertTrue(SyncRunRules.reuseCheck("192.168.1.209", 10_000L, "192.168.1.209", 12_000L))
        assertFalse(SyncRunRules.reuseCheck("192.168.1.209", 10_000L, "192.168.1.210", 12_000L))
        assertFalse(SyncRunRules.reuseCheck("192.168.1.209", 10_000L, "192.168.1.209", 10_000L + SyncRunRules.CHECK_REUSE_MS))
        assertFalse(SyncRunRules.reuseCheck(null, 10_000L, "192.168.1.209", 12_000L))
        assertFalse(SyncRunRules.reuseCheck("192.168.1.209", 0L, "192.168.1.209", 12_000L))
        assertFalse(SyncRunRules.reuseCheck("192.168.1.209", 20_000L, "192.168.1.209", 12_000L))
    }

    @Test
    fun groupsKeepOrderAndSplitOnChange() {
        val items = listOf("fav1", "pos1", "settings", "fav2", "playlist", "settings", "hist")
        val groups = SyncRunRules.groups(items) { it != "settings" && it != "playlist" }
        assertEquals(
            listOf(
                true to listOf("fav1", "pos1"),
                false to listOf("settings"),
                true to listOf("fav2"),
                false to listOf("playlist", "settings"),
                true to listOf("hist"),
            ),
            groups,
        )
        assertEquals(items, groups.flatMap { it.second })
        assertTrue(SyncRunRules.groups(emptyList<String>()) { true }.isEmpty())
    }

    @Test
    fun pairingIsBoundedToAboutThirtySeconds() {
        assertTrue(SyncRunRules.PAIR_TIMEOUT_MS in 20_000L..35_000L)
    }
}
