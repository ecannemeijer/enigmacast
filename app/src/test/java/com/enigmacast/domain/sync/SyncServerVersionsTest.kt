package com.enigmacast.domain.sync

import com.enigmacast.domain.sync.SyncServerVersions.Running
import com.enigmacast.domain.sync.SyncServerVersions.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncServerVersionsTest {
    @Test
    fun parsesTagsAndOpkgLines() {
        assertEquals("0.3.0", SyncServerVersions.normalize("v0.3.0"))
        assertEquals("0.2.4", SyncServerVersions.normalize("enigmacast-sync - 0.2.4"))
        assertEquals("1.2.0", SyncServerVersions.normalize("1.2"))
        assertNull(SyncServerVersions.normalize(""))
        assertTrue(SyncServerVersions.older("0.2.10", "0.3.0"))
        assertFalse(SyncServerVersions.older("0.3.0", "0.2.10"))
        assertFalse(SyncServerVersions.older(null, "0.3.0"))
    }

    @Test
    fun enricosReceiver024IsOutdatedWithoutAVersionEndpoint() {
        val running = Running(reported = null, installed = null, pagesSeen = false)
        assertTrue(running.withoutPaging)
        assertEquals(Verdict.OUTDATED, SyncServerVersions.verdict(running, "0.3.0"))
        assertEquals(Verdict.OUTDATED, SyncServerVersions.verdict(running, null))
        assertEquals("≤ 0.2.5", SyncServerVersions.describe(running))
    }

    @Test
    fun reportedVersionWins() {
        val running = Running("0.3.1", "0.2.4", pagesSeen = true)
        assertEquals("0.3.1", running.version)
        assertEquals(Verdict.UP_TO_DATE, SyncServerVersions.verdict(running, "0.3.0"))
        assertEquals(Verdict.OUTDATED, SyncServerVersions.verdict(running, "0.3.2"))
    }

    @Test
    fun staleInstalledVersionIsIgnoredOncePagesArrive() {
        assertNull(Running(null, "0.2.4", pagesSeen = true).version)
        assertEquals("0.3.0", Running(null, "0.3.0", pagesSeen = true).version)
    }

    @Test
    fun pagingServerWithoutVersionIsUnknownAgainst030ButOutdatedAgainstNewer() {
        val running = Running(null, null, pagesSeen = true)
        assertEquals(Verdict.UNKNOWN, SyncServerVersions.verdict(running, "0.3.0"))
        assertEquals(Verdict.OUTDATED, SyncServerVersions.verdict(running, "0.3.1"))
        assertEquals("0.2.6 – 0.3.0", SyncServerVersions.describe(running))
    }

    @Test
    fun promptOnlyForOwnerOncePerRelease() {
        assertTrue(SyncServerVersions.shouldPrompt(Verdict.OUTDATED, "0.3.0", null, hotel = false))
        assertFalse(SyncServerVersions.shouldPrompt(Verdict.OUTDATED, "0.3.0", null, hotel = true))
        assertFalse(SyncServerVersions.shouldPrompt(Verdict.OUTDATED, "0.3.0", "0.3.0", hotel = false))
        assertTrue(SyncServerVersions.shouldPrompt(Verdict.OUTDATED, "0.3.1", "0.3.0", hotel = false))
        assertFalse(SyncServerVersions.shouldPrompt(Verdict.UNKNOWN, "0.3.0", null, hotel = false))
        assertFalse(SyncServerVersions.shouldPrompt(Verdict.UP_TO_DATE, "0.3.0", null, hotel = false))
    }
}
