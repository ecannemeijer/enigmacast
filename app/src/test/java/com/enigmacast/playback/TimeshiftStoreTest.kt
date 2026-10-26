package com.enigmacast.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TimeshiftStoreTest {
    @Test
    fun capIsOneGigabyte() {
        assertEquals(1024L * 1024L * 1024L, TimeshiftStore.MAX_BYTES)
    }

    @Test
    fun discardStopsBeforeUnreadBytes() {
        val directory = tempDir()
        try {
            val store = TimeshiftStore(directory, maxBytes = 10_000, chunkBytes = 100)
            val data = ByteArray(250) { it.toByte() }
            assertEquals(WriteResult.ACCEPTED, store.write(data, 0, data.size))
            store.discardBefore(150)
            val kept = ByteArray(100)
            assertEquals(100, store.read(100, kept, 0, kept.size))
            assertArrayEquals(data.copyOfRange(100, 200), kept)
            val edge = ByteArray(1)
            assertEquals(1, store.read(150, edge, 0, 1))
            assertEquals(data[150], edge[0])
            assertEquals(-1, store.read(99, edge, 0, 1))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun writePastTheCapIsFullAndDiscardMakesRoom() {
        val directory = tempDir()
        try {
            val store = TimeshiftStore(directory, maxBytes = 100, chunkBytes = 40)
            assertEquals(WriteResult.ACCEPTED, store.write(ByteArray(100) { it.toByte() }, 0, 100))
            assertEquals(WriteResult.FULL, store.write(ByteArray(1), 0, 1))
            val unchanged = ByteArray(100)
            assertEquals(100, store.read(0, unchanged, 0, 100))
            store.discardBefore(100)
            assertEquals(WriteResult.ACCEPTED, store.write(ByteArray(40), 0, 40))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun folderIsCreatedOnTheFirstFlushNotInTheConstructor() {
        val directory = File(System.getProperty("java.io.tmpdir"), "timeshift-lazy-${System.nanoTime()}")
        try {
            val store = TimeshiftStore(directory, maxBytes = 10_000, chunkBytes = 100)
            assertFalse(directory.exists())
            assertEquals(WriteResult.ACCEPTED, store.write(ByteArray(250), 0, 250))
            assertTrue(directory.isDirectory)
            val back = ByteArray(250)
            assertEquals(250, store.read(0, back, 0, 250))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun tempDir(): File {
        val directory = File(System.getProperty("java.io.tmpdir"), "timeshift-test-${System.nanoTime()}")
        directory.mkdirs()
        return directory
    }
}
