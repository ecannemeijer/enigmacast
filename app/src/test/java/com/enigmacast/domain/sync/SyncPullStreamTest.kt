package com.enigmacast.domain.sync

import java.io.IOException
import java.io.Reader
import java.io.StringReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncPullStreamTest {
    private fun read(text: String, out: MutableList<String> = mutableListOf()) =
        runBlocking { SyncPullStream.read(StringReader(text)) { out += it } }

    @Test
    fun readsAPagedResponse() {
        val out = mutableListOf<String>()
        val page = read(
            """{"cursor":12,"changes":[{"rev":11,"kind":"a","data":{"x":[1,2]}},{"rev":12,"kind":"b"}],"limit":2,"more":true}""",
            out,
        )
        assertEquals(2, out.size)
        assertEquals("""{"rev":11,"kind":"a","data":{"x":[1,2]}}""", out[0])
        assertEquals(12, page.cursor)
        assertEquals(2, page.limit)
        assertEquals(true, page.more)
        assertEquals(12, page.lastRev)
        assertTrue(page.serverPages)
    }

    @Test
    fun oldServerWithoutLimitIsRecognised() {
        val page = read("""{"cursor":3,"changes":[{"rev":3}]}""")
        assertNull(page.limit)
        assertNull(page.more)
        assertFalse(page.serverPages)
    }

    @Test
    fun emptyChanges() {
        val out = mutableListOf<String>()
        val page = read("""{ "cursor" : 0 , "changes" : [ ] , "limit" : 500, "more": false }""", out)
        assertTrue(out.isEmpty())
        assertEquals(0, page.received)
        assertNull(page.lastRev)
        assertEquals(false, page.more)
    }

    @Test
    fun escapesAndUnicodeInsideStringsDoNotConfuseTheScanner() {
        val out = mutableListOf<String>()
        val tricky = """{"rev":5,"title":"a \"}\" ] { \\ \u00e9 é 🎬","n":null,"b":false}"""
        read("""{"changes":[$tricky],"cursor":5,"limit":1}""", out)
        assertEquals(1, out.size)
        assertEquals(tricky, out[0])
    }

    @Test
    fun truncatedMidChangeKeepsTheCompleteOnes() {
        val out = mutableListOf<String>()
        try {
            read("""{"cursor":9,"changes":[{"rev":7,"a":1},{"rev":8,"a":2},{"rev":9,"a":"half""", out)
            fail("expected TruncatedPull")
        } catch (error: SyncPullStream.TruncatedPull) {
            assertEquals(8, error.lastRev)
            assertEquals(2, error.received)
        }
        assertEquals(2, out.size)
    }

    @Test
    fun brokenConnectionBecomesTruncatedPull() {
        val text = """{"changes":[{"rev":1},{"rev":2},{"rev":3"""
        var served = 0
        val failing = object : Reader() {
            override fun read(buf: CharArray, off: Int, len: Int): Int {
                if (served >= 25) throw IOException("unexpected end of stream")
                val n = minOf(len, 25 - served, text.length - served)
                text.toCharArray(served, served + n).copyInto(buf, off)
                served += n
                return n
            }
            override fun close() {}
        }
        try {
            runBlocking { SyncPullStream.read(failing) {} }
            fail("expected TruncatedPull")
        } catch (error: SyncPullStream.TruncatedPull) {
            assertEquals(1, error.lastRev)
            assertTrue(error.cause is IOException)
        }
    }

    @Test
    fun revIsFoundAnywhereInTheChange() {
        assertEquals(42, SyncPullStream.revOf("""{"kind":"x","rev" : 42}"""))
        assertNull(SyncPullStream.revOf("""{"kind":"x"}"""))
    }
}
