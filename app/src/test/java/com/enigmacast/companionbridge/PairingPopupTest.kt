package com.enigmacast.companionbridge

import com.enigmacast.companionbridge.ui.newlyPaired
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPopupTest {
    private fun dev(id: String, revoked: Boolean = false) = CompanionDevice(id, "Phone " + id, "", "", revoked = revoked)

    @Test fun `known devices are not a new pairing`() {
        assertNull(newlyPaired(setOf("a"), listOf(dev("a"))))
    }

    @Test fun `new device is reported`() {
        assertEquals("b", newlyPaired(setOf("a"), listOf(dev("a"), dev("b")))?.id)
    }

    @Test fun `revoked new device is ignored`() {
        assertNull(newlyPaired(emptySet(), listOf(dev("c", revoked = true))))
    }
}
