package com.enigmacast.domain.torrent

/** One piece deadline for libtorrent: [piece] wanted within [deadlineMs]. */
data class PieceDeadline(val piece: Int, val deadlineMs: Int)

/**
 * The read-ahead window while a torrent file is streamed: the pieces right after the player's
 * read position get staggered deadlines (the next one first), pieces that fell out of the
 * window lose theirs, and the startup buffer says when playback may begin.
 */
object StreamWindow {
    /** Read-ahead at least this many bytes… */
    const val MIN_AHEAD_BYTES = 32L shl 20
    /** …and at most this many (a 4K remux would otherwise ask for hundreds of MB). */
    const val MAX_AHEAD_BYTES = 80L shl 20
    /** …or this many seconds of the stream's bitrate, when known. */
    const val AHEAD_SECONDS = 20
    const val MIN_PIECES = 4
    const val MAX_PIECES = 64
    /** First piece (the one being read) due at once, then one more every [STEP_MS]. */
    const val FIRST_MS = 0
    const val STEP_MS = 150

    /** Startup: this share of the file, contiguous from its first piece, bounded below and above. */
    const val HEAD_FRACTION = 0.04
    const val MIN_HEAD_BYTES = 8L shl 20
    const val MAX_HEAD_BYTES = 64L shl 20
    const val TAIL_PIECES = 2

    /** How many pieces to keep ahead: ~32 MB, or 20 s of [bitrateBytesPerSec] when that is more (at most 80 MB). */
    fun pieceCount(pieceLength: Int, bitrateBytesPerSec: Long = 0): Int {
        val wanted = maxOf(MIN_AHEAD_BYTES, bitrateBytesPerSec * AHEAD_SECONDS).coerceAtMost(MAX_AHEAD_BYTES)
        val pieces = ((wanted + pieceLength - 1) / pieceLength).toInt()
        return pieces.coerceIn(MIN_PIECES, MAX_PIECES)
    }

    /** The window from [head] (the piece being read), never past the file's [lastPiece]. */
    fun window(head: Int, lastPiece: Int, count: Int): IntRange =
        head..minOf(lastPiece, head + count - 1)

    /** Staggered deadlines for the window's pieces that are still missing. */
    fun deadlines(window: IntRange, have: (Int) -> Boolean, firstMs: Int = FIRST_MS, stepMs: Int = STEP_MS): List<PieceDeadline> =
        window.mapIndexedNotNull { i, p -> if (have(p)) null else PieceDeadline(p, firstMs + i * stepMs) }

    /** Pieces that had a deadline and are behind the playhead or outside the new window. */
    fun stale(previous: Set<Int>, window: IntRange): Set<Int> = previous.filterTo(HashSet()) { it !in window }

    /**
     * A read far from the window (more than half a window away, or backwards) is a seek: the old
     * window is dropped at once instead of draining.
     */
    fun isSeek(previousHead: Int?, head: Int, count: Int): Boolean =
        previousHead != null && (head < previousHead || head - previousHead > maxOf(2, count / 2))

    /** Bytes of the startup head: [HEAD_FRACTION] of the file, at least 8 MB and at most 64 MB (never more than the file). */
    fun headBytes(fileSize: Long): Long =
        (fileSize * HEAD_FRACTION).toLong().coerceIn(MIN_HEAD_BYTES, MAX_HEAD_BYTES).coerceAtMost(fileSize)

    /** Pieces that must be verified before playback: the head (contiguous from the first piece) and the last [TAIL_PIECES]. */
    fun startupPieces(fileOffset: Long, fileSize: Long, pieceLength: Int): List<Int> =
        PieceMath.startupPieces(fileOffset, fileSize, pieceLength, headBytes(fileSize), TAIL_PIECES)

    /** Startup progress 0..1: the share of the startup pieces already verified. */
    fun startupProgress(pieces: List<Int>, have: (Int) -> Boolean): Float {
        if (pieces.isEmpty()) return 1f
        return pieces.count(have) / pieces.size.toFloat()
    }

    fun startupDone(pieces: List<Int>, have: (Int) -> Boolean): Boolean = pieces.all(have)
}
