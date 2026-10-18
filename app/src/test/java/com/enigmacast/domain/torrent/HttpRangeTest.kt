package com.enigmacast.domain.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpRangeTest {
    @Test
    fun parsesRanges() {
        assertNull(HttpRange.parse(null, 1000))
        assertNull(HttpRange.parse("", 1000))
        assertNull(HttpRange.parse("items=0-1", 1000))
        assertEquals(ByteRange(0, 999), HttpRange.parse("bytes=0-", 1000))
        assertEquals(ByteRange(100, 199), HttpRange.parse("bytes=100-199", 1000))
        assertEquals(100L, HttpRange.parse("bytes=100-199", 1000)!!.length)
        assertEquals(ByteRange(900, 999), HttpRange.parse("bytes=900-5000", 1000))
        assertEquals(ByteRange(500, 999), HttpRange.parse("bytes=-500", 1000))
        assertEquals(ByteRange(0, 999), HttpRange.parse("bytes=-5000", 1000))
        assertEquals(ByteRange(0, 9), HttpRange.parse("bytes=0-9, 20-29", 1000))
        assertEquals(HttpRange.UNSATISFIABLE, HttpRange.parse("bytes=1000-", 1000))
        assertEquals(HttpRange.UNSATISFIABLE, HttpRange.parse("bytes=50-10", 1000))
        assertEquals(HttpRange.UNSATISFIABLE, HttpRange.parse("bytes=-0", 1000))
        assertNull(HttpRange.parse("bytes=abc-", 1000))
    }

    @Test
    fun piecesForAFile() {
        val mb = 1L shl 20
        // File starts halfway piece 2 (1 MB pieces), 100 MB long.
        val offset = 2 * mb + mb / 2
        assertEquals(2, PieceMath.pieceAt(offset, 0, mb.toInt()))
        assertEquals(3, PieceMath.pieceAt(offset, mb / 2, mb.toInt()))
        assertEquals(2..102, PieceMath.range(offset, 100 * mb, mb.toInt()))
        val startup = PieceMath.startupPieces(offset, 100 * mb, mb.toInt())
        assertEquals((2..10).toList() + listOf(101, 102), startup)
        // A small file: head and tail overlap, no duplicates.
        assertEquals(listOf(0, 1, 2), PieceMath.startupPieces(0, 3 * mb, mb.toInt()))
    }
}
