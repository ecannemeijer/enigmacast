package com.enigmacast.domain.torrent

/** One piece deadline for libtorrent: [piece] wanted within [deadlineMs]. */
data class PieceDeadline(val piece: Int, val deadlineMs: Int)

/**
 * How a torrent file is downloaded while it streams (the torrent-stream / Stremio approach on
 * libtorrent):
 * - startup: only the first pieces from where playback starts are wanted, top priority with
 *   tight deadlines, so every peer works on the same pieces and they complete in seconds; the
 *   player starts as soon as the piece at the start offset is verified ([gatePieces]);
 * - playing: for every open HTTP response (ExoPlayer reader) a read-ahead window at top
 *   priority, deadlines on the next few seconds paced at the bitrate, the rest of the file after
 *   the readers at low priority (sequential), behind them not wanted;
 * - a read of a missing piece (the mkv cues / mp4 moov at the end, a seek) makes that piece and
 *   the next ones top priority with deadline 0 at once ([demandPieces]).
 */
object StreamWindow {
    /** Read-ahead window at top priority: at least this many bytes… */
    const val MIN_AHEAD_BYTES = 32L shl 20
    /** …and at most this many. */
    const val MAX_AHEAD_BYTES = 160L shl 20
    /** …or this many seconds of the stream's bitrate, when that is more. */
    const val AHEAD_SECONDS = 60
    /** Assumed until the player knows the duration: 10 Mbit/s. */
    const val DEFAULT_BITRATE = 1_250_000L

    /** Deadlines on the next [CRITICAL_SECONDS] of the stream (at least [CRITICAL_MIN_BYTES]). */
    const val CRITICAL_SECONDS = 8
    const val CRITICAL_MIN_BYTES = 4L shl 20
    const val CRITICAL_MIN = 3
    const val CRITICAL_MAX = 24
    /** Pieces this big (and larger): 2–3 critical pieces, so the peers' blocks converge on them. */
    const val BIG_PIECE = 4 shl 20
    const val BIG_CRITICAL_MIN = 2
    const val BIG_CRITICAL_MAX = 3
    /** One piece's playing time, bounded (the spacing between deadlines). */
    const val PIECE_MS_MIN = 50L
    const val PIECE_MS_MAX = 4_000L

    /** libtorrent piece priorities. */
    const val PRIORITY_SKIP = 0
    const val PRIORITY_LOW = 1
    const val PRIORITY_TOP = 7

    /** Startup gate: the pieces holding this many bytes from the start offset (at least one piece). */
    const val GATE_BYTES = 4L shl 20
    /** A read of a missing piece also asks for the next pieces. */
    const val DEMAND_AHEAD = 2
    /** A resume also needs the container header at the file start (mkv EBML/SeekHead, mp4 ftyp). */
    const val HEADER_BYTES = 1L shl 20

    fun bitrate(known: Long): Long = if (known > 0) known else DEFAULT_BITRATE

    fun aheadBytes(bitrateBytesPerSec: Long): Long =
        maxOf(MIN_AHEAD_BYTES, bitrate(bitrateBytesPerSec) * AHEAD_SECONDS).coerceAtMost(MAX_AHEAD_BYTES)

    /** The last piece of the read-ahead window from byte [pos] of the file. */
    fun windowEnd(fileOffset: Long, fileSize: Long, pieceLength: Int, pos: Long, bitrateBytesPerSec: Long): Int =
        PieceMath.pieceAt(fileOffset, (pos + aheadBytes(bitrateBytesPerSec)).coerceAtMost(fileSize - 1).coerceAtLeast(0), pieceLength)

    /**
     * The priority of [piece]: in a reader's window (or read on demand) top; while starting
     * nothing else; after that the rest after the first reader low (in order), behind it none.
     */
    fun priorityOf(piece: Int, windows: List<IntRange>, demanded: Set<Int>, starting: Boolean): Int = when {
        piece in demanded || windows.any { piece in it } -> PRIORITY_TOP
        starting -> PRIORITY_SKIP
        windows.isEmpty() || piece > windows.minOf { it.first } -> PRIORITY_LOW
        else -> PRIORITY_SKIP
    }

    /** A read of [piece] that is not there: it and the next [DEMAND_AHEAD] (within the file), due now and paced. */
    fun demandPieces(piece: Int, lastPiece: Int, pieceLength: Int, bitrateBytesPerSec: Long, have: (Int) -> Boolean): List<PieceDeadline> {
        val step = pieceMs(pieceLength, bitrateBytesPerSec)
        return (piece..minOf(lastPiece, piece + DEMAND_AHEAD)).mapIndexedNotNull { k, p ->
            if (have(p)) null else PieceDeadline(p, (k * step).toInt())
        }
    }

    /** Milliseconds of playback in one piece (deadline spacing). */
    fun pieceMs(pieceLength: Int, bitrateBytesPerSec: Long): Long =
        (pieceLength * 1000L / bitrate(bitrateBytesPerSec)).coerceIn(PIECE_MS_MIN, PIECE_MS_MAX)

    /** How many pieces from the head get a deadline. */
    fun criticalCount(pieceLength: Int, bitrateBytesPerSec: Long): Int {
        val bytes = maxOf(CRITICAL_MIN_BYTES, bitrate(bitrateBytesPerSec) * CRITICAL_SECONDS)
        val count = ((bytes + pieceLength - 1) / pieceLength).toInt()
        return if (pieceLength >= BIG_PIECE) count.coerceIn(BIG_CRITICAL_MIN, BIG_CRITICAL_MAX) else count.coerceIn(CRITICAL_MIN, CRITICAL_MAX)
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

    /**
     * Pieces that must be verified before the player starts: those holding [GATE_BYTES] from
     * [startByte] (0, or the resume point) and on a resume the container header. Everything
     * else (the tail with mkv cues / mp4 moov) is fetched when the player reads it.
     */
    fun gatePieces(fileOffset: Long, fileSize: Long, pieceLength: Int, startByte: Long = 0): List<Int> {
        if (fileSize <= 0) return emptyList()
        val all = PieceMath.range(fileOffset, fileSize, pieceLength)
        val start = startByte.coerceIn(0, fileSize - 1)
        val head = PieceMath.pieceAt(fileOffset, start, pieceLength)..PieceMath.pieceAt(fileOffset, (start + GATE_BYTES - 1).coerceAtMost(fileSize - 1), pieceLength)
        val header = if (start > 0) all.first..PieceMath.pieceAt(fileOffset, (HEADER_BYTES - 1).coerceAtMost(fileSize - 1), pieceLength) else IntRange.EMPTY
        return (head + header).distinct().sorted()
    }

    /** Where the startup head begins for a resume at [positionMs] of [durationMs] (0 when unknown). */
    fun startByteFor(positionMs: Long, durationMs: Long, fileSize: Long): Long =
        if (positionMs <= 0 || durationMs <= 0 || positionMs >= durationMs) 0 else (fileSize.toDouble() * positionMs / durationMs).toLong()

    /** Startup progress 0..1 from the bytes received so far for the [gateBytes] (verified pieces count fully). */
    fun startupProgress(receivedBytes: Long, gateBytes: Long, done: Boolean): Float =
        if (done || gateBytes <= 0) 1f else (receivedBytes.toFloat() / gateBytes).coerceIn(0f, 0.99f)

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
