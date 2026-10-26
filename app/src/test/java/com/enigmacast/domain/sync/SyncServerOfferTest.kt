package com.enigmacast.domain.sync

import com.enigmacast.domain.sync.SyncServerOffer.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncServerOfferTest {
    @Test
    fun `no server on the receiver offers the install form only`() {
        assertEquals(listOf(Action.INSTALL), SyncServerOffer.actions(found = false, version = null, latest = "0.3.1"))
    }

    @Test
    fun `old server without a version is paired first and offered the update`() {
        assertEquals(listOf(Action.PAIR, Action.UPDATE, Action.REINSTALL), SyncServerOffer.actions(true, null, "0.3.1"))
    }

    @Test
    fun `current server is only paired, reinstall stays secondary`() {
        assertEquals(listOf(Action.PAIR, Action.REINSTALL), SyncServerOffer.actions(true, "0.3.1", "v0.3.1"))
        assertEquals(Action.PAIR, SyncServerOffer.actions(true, "0.3.1", null).first())
    }

    @Test
    fun `outdated compares with the latest release`() {
        assertTrue(SyncServerOffer.outdated("0.3.0", "0.3.1"))
        assertFalse(SyncServerOffer.outdated("0.3.2", "0.3.1"))
        assertFalse(SyncServerOffer.outdated("0.3.1", null))
    }

    @Test
    fun `label shows the version or the range of old servers`() {
        assertEquals("v0.3.1", SyncServerOffer.label("0.3.1"))
        assertEquals("< 0.3.1", SyncServerOffer.label(null))
    }
}
