package com.enigmacast.domain.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamWindowTest {
    private val mb = 1 shl 20

    @Test fun readAheadIs32To160MbOrSixtySecondsOfBitrate() {
        assertEquals(75_000_000L, StreamWindow.aheadBytes(0))              // unknown: 60 s of 10 Mbit
        assertEquals(32L * mb, StreamWindow.aheadBytes(100_000))           // at least 32 MB
        assertEquals(160L * mb, StreamWindow.aheadBytes(10L * mb))         // capped
    }

    @Test fun windowEndStopsAtTheFileEnd() {
        assertEquals(71, StreamWindow.windowEnd(0, 1000L * mb, mb, 0, 0))
        assertEquals(99, StreamWindow.windowEnd(0, 100L * mb, mb, 90L * mb, 0))
    }

    @Test fun startingWantsOnlyTheWindows() {
        val w = listOf(10..11)
        assertEquals(StreamWindow.PRIORITY_TOP, StreamWindow.priorityOf(11, w, emptySet(), starting = true))
        assertEquals(StreamWindow.PRIORITY_SKIP, StreamWindow.priorityOf(12, w, emptySet(), starting = true))
        assertEquals(StreamWindow.PRIORITY_TOP, StreamWindow.priorityOf(99, w, setOf(99), starting = true))  // tail read on demand
    }

    @Test fun playingWantsWindowsTopRestLowBehindNone() {
        val w = listOf(40..60, 95..99)   // the playing reader and a tail reader
        assertEquals(StreamWindow.PRIORITY_SKIP, StreamWindow.priorityOf(39, w, emptySet(), starting = false))
        assertEquals(StreamWindow.PRIORITY_TOP, StreamWindow.priorityOf(50, w, emptySet(), starting = false))
        assertEquals(StreamWindow.PRIORITY_LOW, StreamWindow.priorityOf(61, w, emptySet(), starting = false))
        assertEquals(StreamWindow.PRIORITY_TOP, StreamWindow.priorityOf(97, w, emptySet(), starting = false))
        assertEquals(StreamWindow.PRIORITY_TOP, StreamWindow.priorityOf(5, w, setOf(5), starting = false))
        assertEquals(StreamWindow.PRIORITY_LOW, StreamWindow.priorityOf(5, emptyList(), emptySet(), starting = false))
    }

    @Test fun aReadOnDemandAsksForThePieceAndTheNextTwo() {
        val d = StreamWindow.demandPieces(97, 99, 4 * mb, 2L * mb, have = { it == 98 })
        assertEquals(listOf(PieceDeadline(97, 0), PieceDeadline(99, 4000)), d)
        assertEquals(listOf(PieceDeadline(99, 0)), StreamWindow.demandPieces(99, 99, mb, 0, { false }))
    }

    @Test fun criticalStretchIsAboutEightSecondsAndTwoOrThreeBigPieces() {
        assertEquals(16, StreamWindow.criticalCount(mb, 2L * mb))
        assertEquals(3, StreamWindow.criticalCount(2 * mb, 100_000))   // small pieces: at least 3
        assertEquals(24, StreamWindow.criticalCount(256 * 1024, 2L * mb))
        assertEquals(2, StreamWindow.criticalCount(8 * mb, 500_000))     // 8 MB pieces: 2
        assertEquals(3, StreamWindow.criticalCount(4 * mb, 2L * mb))     // 16 MB in 4 MB pieces, capped at 3
    }

    @Test fun criticalDeadlinesSkipHaveAndAlreadySet() {
        val d = StreamWindow.criticalDeadlines(10, 100, 2 * mb, 2L * mb, have = { it == 11 }, already = setOf(12))
        // 8 pieces (16 MB of 2 MB pieces), 1 s each.
        assertEquals(listOf(10, 13, 14, 15, 16, 17), d.map { it.piece })
        assertEquals(listOf(0, 3000), d.take(2).map { it.deadlineMs })
    }

    @Test fun gateIsAbout4MbFromTheStartOffset() {
        assertEquals(listOf(0, 1, 2, 3), StreamWindow.gatePieces(0, 1000L * mb, mb))
        assertEquals(listOf(0), StreamWindow.gatePieces(0, 3696L * mb, 8 * mb))          // one 8 MB piece
        // Resume: the pieces at the resume point and the header piece.
        assertEquals(listOf(0, 500, 501, 502, 503), StreamWindow.gatePieces(0, 1000L * mb, mb, 500L * mb))
        assertEquals(listOf(0, 230), StreamWindow.gatePieces(0, 3696L * mb, 8 * mb, 1840L * mb))
        assertEquals(listOf(0, 1), StreamWindow.gatePieces(0, 2L * mb, mb))              // never past the file
    }

    @Test fun startupProgressFromReceivedBytes() {
        assertEquals(0.5f, StreamWindow.startupProgress(4L * mb, 8L * mb, false))
        assertEquals(0.99f, StreamWindow.startupProgress(20L * mb, 8L * mb, false))
        assertEquals(1f, StreamWindow.startupProgress(0, 8L * mb, true))
        assertTrue(StreamWindow.startupDone(listOf(1, 2)) { true })
        assertFalse(StreamWindow.startupDone(listOf(1, 2)) { it == 1 })
    }

    @Test fun deadlinesArePacedAtTheBitrate() {
        assertEquals(500L, StreamWindow.pieceMs(mb, 2L * mb))     // 1 MB at 2 MB/s
        assertEquals(50L, StreamWindow.pieceMs(16 * 1024, 2L * mb))
        assertEquals(4000L, StreamWindow.pieceMs(16 * mb, 1000))
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

    @Test fun startByteIsTheResumeShareOfTheFile() {
        assertEquals(500L, StreamWindow.startByteFor(30_000, 60_000, 1000))
        assertEquals(0L, StreamWindow.startByteFor(30_000, 0, 1000))
        assertEquals(0L, StreamWindow.startByteFor(0, 60_000, 1000))
        assertEquals(0L, StreamWindow.startByteFor(70_000, 60_000, 1000))
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
