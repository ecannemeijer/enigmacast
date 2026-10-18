package com.enigmacast.torrent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientGoneTest {
    @Test fun brokenPipeAndResetAreTheClientLeaving() {
        assertTrue(isClientGone(java.net.SocketException("Connection reset")))
        assertTrue(isClientGone(java.io.IOException("write failed", java.io.IOException("Broken pipe"))))
        assertFalse(isClientGone(java.io.IOException("piece timeout")))
        assertFalse(isClientGone(null))
    }
}
