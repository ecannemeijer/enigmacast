package com.enigmacast.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLException

class SyncPullPolicyTest {
    @Test
    fun pageSizeShrinksAndStaysAtTheSmallest() {
        assertEquals(LibrarySync.PULL_CHUNK, SyncPullPolicy.pageSize(0))
        assertEquals(20, SyncPullPolicy.pageSize(1))
        assertEquals(10, SyncPullPolicy.pageSize(2))
        assertEquals(10, SyncPullPolicy.pageSize(9))
        assertEquals(LibrarySync.PULL_CHUNK, SyncPullPolicy.pageSize(-1))
    }

    @Test
    fun backoffDoublesUpToTheCap() {
        assertEquals(400L, SyncPullPolicy.backoffMs(0))
        assertEquals(800L, SyncPullPolicy.backoffMs(1))
        assertEquals(1_600L, SyncPullPolicy.backoffMs(2))
        assertEquals(3_200L, SyncPullPolicy.backoffMs(3))
        assertEquals(SyncPullPolicy.MAX_BACKOFF_MS, SyncPullPolicy.backoffMs(4))
        assertEquals(SyncPullPolicy.MAX_BACKOFF_MS, SyncPullPolicy.backoffMs(100))
    }

    @Test
    fun lastPageWhenShortStoppedOrMissing() {
        assertFalse(SyncPullPolicy.lastPage(cursor = 10, next = 30, received = 20, limit = 20))
        assertTrue(SyncPullPolicy.lastPage(cursor = 10, next = 15, received = 5, limit = 20))
        assertTrue(SyncPullPolicy.lastPage(cursor = 10, next = 10, received = 20, limit = 20))
        assertTrue(SyncPullPolicy.lastPage(cursor = 10, next = 30, received = null, limit = 20))
        // A server that ignores the limit and sends everything: more than asked, cursor moved → keep going.
        assertFalse(SyncPullPolicy.lastPage(cursor = 0, next = 400, received = 400, limit = 10))
    }

    @Test
    fun compactErrorIsOneShortLineWithoutSslHandles() {
        val error = SSLException("Read error: ssl=0x7a1b2c3d4e: I/O error during system call,\n   Connection reset by peer")
        assertEquals("SSLException: Read error: I/O error during system call,", SyncPullPolicy.compactError(error))
        val long = IllegalStateException("x".repeat(500))
        val text = SyncPullPolicy.compactError(long)
        assertEquals(120, text.length)
        assertTrue(text.endsWith("…"))
        assertFalse(text.contains('\n'))
    }

    @Test
    fun compactErrorUsesTheCauseWhenTheMessageIsEmpty() {
        val error = RuntimeException(null, java.io.IOException("unexpected end of stream"))
        assertEquals("IOException: unexpected end of stream", SyncPullPolicy.compactError(error))
        assertEquals("IllegalStateException", SyncPullPolicy.compactError(IllegalStateException()))
    }

    @Test
    fun healthIsIncompleteWhenAnythingFailed() {
        assertFalse(SyncHealth().incomplete)
        assertFalse(SyncHealth(failedProfiles = 0, totalProfiles = 3).incomplete)
        assertTrue(SyncHealth(failedProfiles = 1, totalProfiles = 3).incomplete)
        assertTrue(SyncHealth(otherFailed = true).incomplete)
    }
}
