package com.enigmacast.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class UpdateDownloadTest {
    /** Hands out at most [chunk] bytes per read, like a network stream. */
    private class Trickle(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(len, chunk, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    private val data = ByteArray(3_000_000) { (it % 251).toByte() }

    @Test
    fun copiesEverythingAndReportsEachPercentOnce() = runBlocking {
        val out = ByteArrayOutputStream()
        val seen = mutableListOf<Int>()
        val copied = UpdateDownload.copy(Trickle(data, 1000), out, data.size.toLong(), { seen += it })
        assertEquals(data.size.toLong(), copied)
        assertArrayEquals(data, out.toByteArray())
        assertEquals(seen.distinct(), seen)
        assertEquals(seen.sorted(), seen)
        assertEquals(100, seen.last())
        assertTrue(seen.size <= 101)
    }

    @Test
    fun unknownSizeIsThrottledAndEndsAt100() = runBlocking {
        var clock = 0L
        val seen = mutableListOf<Int>()
        UpdateDownload.copy(Trickle(data, 1000), ByteArrayOutputStream(), -1L, { seen += it }, nowMs = { clock += 10; clock })
        // 3000 chunks, 10 ms apart: about one report per 25 chunks instead of 3000.
        assertTrue(seen.size in 100..130)
        assertEquals(100, seen.last())
        assertTrue(seen.dropLast(1).all { it == -1 })
    }

    @Test
    fun truncatedBodyFails() = runBlocking {
        try {
            UpdateDownload.copy(ByteArrayInputStream(data, 0, 1000), ByteArrayOutputStream(), data.size.toLong(), {})
            fail("expected an error")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("incomplete"))
        }
    }

    @Test
    fun cancellationStopsTheCopy() = runBlocking {
        var chunks = 0
        try {
            UpdateDownload.copy(Trickle(data, 1000), ByteArrayOutputStream(), data.size.toLong(), {}, checkActive = {
                if (++chunks > 5) throw CancellationException("cancelled")
            })
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals(6, chunks)
        }
    }

    @Test
    fun percentIsClamped() {
        assertEquals(0, UpdateDownload.percent(5, 0))
        assertEquals(50, UpdateDownload.percent(50, 100))
        assertEquals(100, UpdateDownload.percent(150, 100))
    }
}
