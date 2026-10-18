package com.enigmacast.domain.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamWindowTest {
    private val mb = 1 shl 20

    @Test fun windowCountScalesWithPieceSize() {
        assertEquals(32, StreamWindow.pieceCount(mb))          // 32 MB of 1 MB pieces
        assertEquals(64, StreamWindow.pieceCount(256 * 1024))  // capped at 64 pieces
        assertEquals(8, StreamWindow.pieceCount(4 * mb))
        assertEquals(4, StreamWindow.pieceCount(16 * mb))      // never fewer than 4
    }

    @Test fun windowCoversTwentySecondsOfBitrate() {
        // 25 Mbit/s ≈ 3.1 MB/s → 20 s ≈ 62.5 MB → 63 pieces of 1 MB.
        assertEquals(63, StreamWindow.pieceCount(mb, 3_276_800))
        // A huge bitrate stops at 80 MB.
        assertEquals(20, StreamWindow.pieceCount(4 * mb, 50L * mb))
    }

    @Test fun windowStopsAtTheFileEnd() {
        assertEquals(10..19, StreamWindow.window(10, 100, 10))
        assertEquals(95..100, StreamWindow.window(95, 100, 10))
    }

    @Test fun deadlinesAreStaggeredAndSkipVerifiedPieces() {
        val have = setOf(11, 13)
        val d = StreamWindow.deadlines(10..14, { it in have }, firstMs = 0, stepMs = 150)
        assertEquals(listOf(PieceDeadline(10, 0), PieceDeadline(12, 300), PieceDeadline(14, 600)), d)
    }

    @Test fun piecesBehindThePlayheadAreStale() {
        assertEquals(setOf(8, 9, 30), StreamWindow.stale(setOf(8, 9, 10, 15, 30), 10..20))
    }

    @Test fun seekIsBackwardsOrFarAhead() {
        assertFalse(StreamWindow.isSeek(null, 50, 32))
        assertFalse(StreamWindow.isSeek(10, 12, 32))
        assertFalse(StreamWindow.isSeek(10, 26, 32))
        assertTrue(StreamWindow.isSeek(10, 27, 32))
        assertTrue(StreamWindow.isSeek(10, 9, 32))
    }

    @Test fun headIsFourPercentBetween8And64Mb() {
        assertEquals(8L * mb, StreamWindow.headBytes(100L * mb))          // 4 MB → min 8 MB
        assertEquals(40L * mb, StreamWindow.headBytes(1000L * mb))        // 4 %
        assertEquals(64L * mb, StreamWindow.headBytes(20_000L * mb))      // max 64 MB
        assertEquals(5L * mb, StreamWindow.headBytes(5L * mb))            // never more than the file
    }

    @Test fun startupIsContiguousHeadPlusLastTwoPieces() {
        // 1000 MB file at offset 0, 1 MB pieces: 40 head pieces (0..39) and the last two.
        val p = StreamWindow.startupPieces(0, 1000L * mb, mb)
        assertEquals((0..39).toList() + listOf(998, 999), p)
    }

    @Test fun startupWaitsForEveryPiece() {
        val p = listOf(0, 1, 2, 9)
        assertFalse(StreamWindow.startupDone(p) { it != 2 })
        assertEquals(0.75f, StreamWindow.startupProgress(p) { it != 2 })
        assertTrue(StreamWindow.startupDone(p) { true })
    }
}
