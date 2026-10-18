package com.enigmacast.domain.torrent

/** One piece deadline for libtorrent: [piece] wanted within [deadlineMs]. */
data class PieceDeadline(val piece: Int, val deadlineMs: Int)

/**
 * How a torrent file is downloaded while it streams (the torrent-stream / Stremio approach on
 * libtorrent):
 * - pieces behind the reader are not wanted, the read-ahead window is top priority and the
 *   rest of the file normal priority, so libtorrent's sequential mode really goes in order
 *   (with the whole file at top priority it picks rarest-first over the whole file);
 * - only a short critical stretch gets deadlines, each paced at the stream's bitrate and set
 *   once, so time-critical mode never sees a wall of overdue pieces (it would request each
 *   overdue block from several peers);
 * - the startup buffer starts where playback starts (a resume).
 */
object StreamWindow {
    /** Read-ahead window at top priority: at least this many bytes… */
    const val MIN_AHEAD_BYTES = 32L shl 20
    /** …and at most this many (a 4K remux would otherwise ask for hundreds of MB). */
    const val MAX_AHEAD_BYTES = 80L shl 20
    /** …or this many seconds of the stream's bitrate, when that is more. */
    const val AHEAD_SECONDS = 30
    /** Assumed until the player knows the duration: 10 Mbit/s. */
    const val DEFAULT_BITRATE = 1_250_000L

    /** Deadlines on the next [CRITICAL_SECONDS] of the stream (at least [CRITICAL_MIN_BYTES], 3–24 pieces). */
    const val CRITICAL_SECONDS = 8
    const val CRITICAL_MIN_BYTES = 4L shl 20
    const val CRITICAL_MIN = 3
    const val CRITICAL_MAX = 24
    /** One piece's playing time, bounded (the spacing between deadlines). */
    const val PIECE_MS_MIN = 50L
    const val PIECE_MS_MAX = 4_000L

    /** libtorrent piece priorities. */
    const val PRIORITY_SKIP = 0
    const val PRIORITY_NORMAL = 4
    const val PRIORITY_TOP = 7

    /** Startup: this share of the file, contiguous from where playback starts, bounded below and above. */
    const val HEAD_FRACTION = 0.04
    const val MIN_HEAD_BYTES = 8L shl 20
    const val MAX_HEAD_BYTES = 64L shl 20
    const val TAIL_PIECES = 2
    /** A resume also needs the container header at the file start (mkv EBML/SeekHead, mp4 ftyp). */
    const val HEADER_BYTES = 1L shl 20

    fun bitrate(known: Long): Long = if (known > 0) known else DEFAULT_BITRATE

    fun aheadBytes(bitrateBytesPerSec: Long): Long =
        maxOf(MIN_AHEAD_BYTES, bitrate(bitrateBytesPerSec) * AHEAD_SECONDS).coerceAtMost(MAX_AHEAD_BYTES)

    /** The last piece of the read-ahead window from byte [pos] of the file. */
    fun windowEnd(fileOffset: Long, fileSize: Long, pieceLength: Int, pos: Long, bitrateBytesPerSec: Long): Int =
        PieceMath.pieceAt(fileOffset, (pos + aheadBytes(bitrateBytesPerSec)).coerceAtMost(fileSize - 1).coerceAtLeast(0), pieceLength)

    /**
     * The priority of [piece]: behind the reader not wanted, the window top, the rest normal.
     * The last [TAIL_PIECES] (mkv cues / mp4 index) are always wanted.
     */
    fun priorityOf(piece: Int, head: Int, windowEnd: Int, lastPiece: Int): Int = when {
        piece >= head && piece <= windowEnd -> PRIORITY_TOP
        piece > lastPiece - TAIL_PIECES -> PRIORITY_NORMAL
        piece < head -> PRIORITY_SKIP
        else -> PRIORITY_NORMAL
    }

    /** Milliseconds of playback in one piece (deadline spacing). */
    fun pieceMs(pieceLength: Int, bitrateBytesPerSec: Long): Long =
        (pieceLength * 1000L / bitrate(bitrateBytesPerSec)).coerceIn(PIECE_MS_MIN, PIECE_MS_MAX)

    /** How many pieces from the head get a deadline. */
    fun criticalCount(pieceLength: Int, bitrateBytesPerSec: Long): Int {
        val bytes = maxOf(CRITICAL_MIN_BYTES, bitrate(bitrateBytesPerSec) * CRITICAL_SECONDS)
        return ((bytes + pieceLength - 1) / pieceLength).toInt().coerceIn(CRITICAL_MIN, CRITICAL_MAX)
    }

    /**
     * Deadlines for the critical pieces from [head] that are missing and do not have one yet
     * ([already]): the k-th piece due when playback reaches it (k × [pieceMs]).
     */
    fun criticalDeadlines(
        head: Int, lastPiece: Int, pieceLength: Int, bitrateBytesPerSec: Long,
        have: (Int) -> Boolean, already: Set<Int>,
    ): List<PieceDeadline> {
        val step = pieceMs(pieceLength, bitrateBytesPerSec)
        val end = minOf(lastPiece, head + criticalCount(pieceLength, bitrateBytesPerSec) - 1)
        return (head..end).mapIndexedNotNull { k, p ->
            if (have(p) || p in already) null else PieceDeadline(p, (k * step).toInt())
        }
    }

    /** Pieces that had a deadline and are now behind the head (dropped so libtorrent forgets them). */
    fun behind(withDeadline: Set<Int>, head: Int): Set<Int> = withDeadline.filterTo(HashSet()) { it < head }

    /**
     * A read far from the last head (backwards, or more than half the window ahead) is a seek:
     * the old deadlines are dropped at once.
     */
    fun isSeek(previousHead: Int?, head: Int, windowPieces: Int): Boolean =
        previousHead != null && (head < previousHead || head - previousHead > maxOf(8, windowPieces / 2))

    /** Bytes of the startup head: [HEAD_FRACTION] of the file, at least 8 MB and at most 64 MB (never more than the file). */
    fun headBytes(fileSize: Long): Long =
        (fileSize * HEAD_FRACTION).toLong().coerceIn(MIN_HEAD_BYTES, MAX_HEAD_BYTES).coerceAtMost(fileSize)

    /**
     * Pieces that must be verified before playback: the head from [startByte] (0, or the resume
     * point), the last [TAIL_PIECES], and on a resume the container header at the file start.
     */
    fun startupPieces(fileOffset: Long, fileSize: Long, pieceLength: Int, startByte: Long = 0): List<Int> {
        val all = PieceMath.range(fileOffset, fileSize, pieceLength)
        val start = startByte.coerceIn(0, (fileSize - 1).coerceAtLeast(0))
        val head = PieceMath.pieceAt(fileOffset, start, pieceLength)..PieceMath.pieceAt(fileOffset, (start + headBytes(fileSize) - 1).coerceAtMost(fileSize - 1), pieceLength)
        val tail = (all.last - TAIL_PIECES + 1).coerceAtLeast(all.first)..all.last
        val header = if (start > 0) all.first..PieceMath.pieceAt(fileOffset, (HEADER_BYTES - 1).coerceAtMost(fileSize - 1), pieceLength) else IntRange.EMPTY
        return (head + tail + header).distinct().sorted()
    }

    /** Where the startup head begins for a resume at [positionMs] of [durationMs] (0 when unknown). */
    fun startByteFor(positionMs: Long, durationMs: Long, fileSize: Long): Long =
        if (positionMs <= 0 || durationMs <= 0 || positionMs >= durationMs) 0 else (fileSize.toDouble() * positionMs / durationMs).toLong()

    /** Startup progress 0..1: the share of the startup pieces already verified. */
    fun startupProgress(pieces: List<Int>, have: (Int) -> Boolean): Float {
        if (pieces.isEmpty()) return 1f
        return pieces.count(have) / pieces.size.toFloat()
    }

    fun startupDone(pieces: List<Int>, have: (Int) -> Boolean): Boolean = pieces.all(have)

    /**
     * End (exclusive, in file bytes) of the verified data contiguous from byte [pos]: what can
     * be played without waiting. [pos] itself when its piece is missing.
     */
    fun contiguousEnd(fileOffset: Long, fileSize: Long, pieceLength: Int, pos: Long, have: (Int) -> Boolean): Long {
        if (fileSize <= 0) return 0
        val from = pos.coerceIn(0, fileSize - 1)
        val last = PieceMath.pieceAt(fileOffset, fileSize - 1, pieceLength)
        var p = PieceMath.pieceAt(fileOffset, from, pieceLength)
        if (!have(p)) return from
        while (p < last && have(p + 1)) p++
        return ((p + 1L) * pieceLength - fileOffset).coerceAtMost(fileSize)
    }

    /** [bytesEnd] of the file as a playback time ([durationMs] spread over [fileSize]). */
    fun bytesToMs(bytesEnd: Long, fileSize: Long, durationMs: Long): Long =
        if (fileSize <= 0 || durationMs <= 0) 0 else (durationMs.toDouble() * bytesEnd.coerceIn(0, fileSize) / fileSize).toLong()
}
