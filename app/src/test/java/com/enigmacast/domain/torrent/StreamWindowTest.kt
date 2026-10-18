package com.enigmacast.domain.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamWindowTest {
    private val mb = 1 shl 20

    @Test fun readAheadIs32To80MbOrThirtySecondsOfBitrate() {
        assertEquals(37_500_000L, StreamWindow.aheadBytes(0))              // unknown: 30 s of 10 Mbit
        assertEquals(32L * mb, StreamWindow.aheadBytes(500_000))           // at least 32 MB
        assertEquals(60L * mb, StreamWindow.aheadBytes(2L * mb))           // 30 s of 16 Mbit
        assertEquals(80L * mb, StreamWindow.aheadBytes(10L * mb))          // capped
    }

    @Test fun windowEndStopsAtTheFileEnd() {
        assertEquals(35, StreamWindow.windowEnd(0, 1000L * mb, mb, 0, 0))
        assertEquals(99, StreamWindow.windowEnd(0, 100L * mb, mb, 90L * mb, 0))
    }

    @Test fun behindSkippedWindowTopRestNormalTailKept() {
        // File pieces 0..99, reader at 40, window to 60.
        assertEquals(StreamWindow.PRIORITY_SKIP, StreamWindow.priorityOf(39, 40, 60, 99))
        assertEquals(StreamWindow.PRIORITY_TOP, StreamWindow.priorityOf(40, 40, 60, 99))
        assertEquals(StreamWindow.PRIORITY_TOP, StreamWindow.priorityOf(60, 40, 60, 99))
        assertEquals(StreamWindow.PRIORITY_NORMAL, StreamWindow.priorityOf(61, 40, 60, 99))
        assertEquals(StreamWindow.PRIORITY_NORMAL, StreamWindow.priorityOf(98, 99, 99, 99))  // tail behind the head
        assertEquals(StreamWindow.PRIORITY_SKIP, StreamWindow.priorityOf(97, 99, 99, 99))
    }

    @Test fun deadlinesArePacedAtTheBitrate() {
        assertEquals(500L, StreamWindow.pieceMs(mb, 2L * mb))     // 1 MB at 2 MB/s
        assertEquals(50L, StreamWindow.pieceMs(16 * 1024, 2L * mb))
        assertEquals(4000L, StreamWindow.pieceMs(16 * mb, 1000))
    }

    @Test fun criticalStretchIsAboutEightSeconds() {
        assertEquals(16, StreamWindow.criticalCount(mb, 2L * mb))   // 16 MB in 1 MB pieces
        assertEquals(3, StreamWindow.criticalCount(16 * mb, 0))     // at least 3 pieces
        assertEquals(24, StreamWindow.criticalCount(256 * 1024, 2L * mb))
    }

    @Test fun criticalDeadlinesSkipHaveAndAlreadySet() {
        val d = StreamWindow.criticalDeadlines(10, 100, 4 * mb, 2L * mb, have = { it == 11 }, already = setOf(12))
        // 4 pieces (16 MB of 4 MB pieces), 2 s each.
        assertEquals(listOf(PieceDeadline(10, 0), PieceDeadline(13, 6000)), d)
    }

    @Test fun deadlinesBehindTheHeadAreDropped() {
        assertEquals(setOf(8, 9), StreamWindow.behind(setOf(8, 9, 10, 15), 10))
    }

    @Test fun seekIsBackwardsOrFarAhead() {
        assertFalse(StreamWindow.isSeek(null, 50, 32))
        assertFalse(StreamWindow.isSeek(10, 12, 32))
        assertFalse(StreamWindow.isSeek(10, 26, 32))
        assertTrue(StreamWindow.isSeek(10, 27, 32))
        assertTrue(StreamWindow.isSeek(10, 9, 32))
    }

    @Test fun headIsFourPercentBetween8And64Mb() {
        assertEquals(8L * mb, StreamWindow.headBytes(100L * mb))
        assertEquals(40L * mb, StreamWindow.headBytes(1000L * mb))
        assertEquals(64L * mb, StreamWindow.headBytes(20_000L * mb))
        assertEquals(5L * mb, StreamWindow.headBytes(5L * mb))
    }

    @Test fun startupIsContiguousHeadPlusLastTwoPieces() {
        val p = StreamWindow.startupPieces(0, 1000L * mb, mb)
        assertEquals((0..39).toList() + listOf(998, 999), p)
    }

    @Test fun resumeBuffersFromTheResumePointPlusHeader() {
        val p = StreamWindow.startupPieces(0, 1000L * mb, mb, startByte = 500L * mb)
        assertEquals(listOf(0) + (500..539).toList() + listOf(998, 999), p)
        // Near the end the head stops at the file end.
        assertEquals(listOf(0) + (990..999).toList(), StreamWindow.startupPieces(0, 1000L * mb, mb, 990L * mb))
    }

    @Test fun startByteIsTheResumeShareOfTheFile() {
        assertEquals(500L, StreamWindow.startByteFor(30_000, 60_000, 1000))
        assertEquals(0L, StreamWindow.startByteFor(30_000, 0, 1000))
        assertEquals(0L, StreamWindow.startByteFor(0, 60_000, 1000))
        assertEquals(0L, StreamWindow.startByteFor(70_000, 60_000, 1000))
    }

    @Test fun startupWaitsForEveryPiece() {
        val p = listOf(0, 1, 2, 3)
        assertFalse(StreamWindow.startupDone(p) { it != 2 })
        assertEquals(0.75f, StreamWindow.startupProgress(p) { it != 2 })
        assertTrue(StreamWindow.startupDone(p) { true })
    }

    @Test fun contiguousEndRunsToTheFirstMissingPiece() {
        // File at offset 100 in 1000-byte pieces, 5000 bytes: pieces 0..5.
        val have = setOf(1, 2, 3)
        assertEquals(3900L, StreamWindow.contiguousEnd(100, 5000, 1000, 1000, have::contains))  // 1000 is in piece 1
        assertEquals(50L, StreamWindow.contiguousEnd(100, 5000, 1000, 50, have::contains))      // piece 0 missing
        assertEquals(5000L, StreamWindow.contiguousEnd(100, 5000, 1000, 4500, { true }))        // capped at the size
    }

    @Test fun bytesMapToTime() {
        assertEquals(30_000L, StreamWindow.bytesToMs(500, 1000, 60_000))
        assertEquals(0L, StreamWindow.bytesToMs(500, 0, 60_000))
        assertEquals(60_000L, StreamWindow.bytesToMs(2000, 1000, 60_000))
    }
}
