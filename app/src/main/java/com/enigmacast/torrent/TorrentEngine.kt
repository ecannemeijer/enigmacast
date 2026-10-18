package com.enigmacast.torrent

import android.content.Context
import com.enigmacast.domain.torrent.PieceMath
import com.enigmacast.domain.torrent.StremioStreams
import com.enigmacast.domain.torrent.StreamWindow
import kotlinx.coroutines.launch
import com.enigmacast.domain.torrent.TorrentStream
import com.enigmacast.util.EnigmaLog
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class TorrentStage { IDLE, METADATA, BUFFERING, READY, ERROR }

data class TorrentProgress(
    val stage: TorrentStage = TorrentStage.IDLE,
    /** Startup buffer, 0..1. */
    val buffer: Float = 0f,
    val downloadBps: Int = 0,
    val peers: Int = 0,
    val seeds: Int = 0,
    val error: String? = null,
)

/** The file being streamed: where it sits in the torrent and on disk. */
internal class ActiveFile(
    val handle: TorrentHandle,
    /** libtorrent's id of the handle (matches the alerts of this torrent, no call into the session). */
    val handleId: Long,
    /** This stream's download folder (deleted on stop). */
    val dir: File,
    val file: File,
    val name: String,
    val size: Long,
    val offset: Long,
    val pieceLength: Int,
    /** The file's first and last piece (not the torrent's). */
    val firstPiece: Int,
    val lastPiece: Int,
    val numPieces: Int,
) {
    @Volatile var closed = false
    /**
     * Verified pieces, kept from the piece-finished alerts. libtorrent's havePiece is a
     * synchronous call into its network thread; asked for every read and every piece of the file
     * it slowed the download itself and blocked readers.
     */
    private val have = java.util.BitSet(numPieces)
    fun has(piece: Int): Boolean = synchronized(have) { have[piece] }
    fun mark(piece: Int) = synchronized(have) { have.set(piece) }
    fun count(): Int = synchronized(have) { have.get(firstPiece, lastPiece + 1).cardinality() }
    /** Pieces read by the player that are not there yet (top priority until verified). */
    val demanded: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Piece priorities as last set in libtorrent (whole torrent), guarded by this object: the file's at normal when added. */
    val prio = IntArray(numPieces) { if (it in firstPiece..lastPiece) 4 else 0 }
}

/** One HTTP response being read: where it is now (a Range request starts a new one). */
internal class Reader(@Volatile var pos: Long) {
    @Volatile var touchedMs: Long = System.currentTimeMillis()
}

/**
 * libtorrent (jlibtorrent) for streaming one file at a time, as torrent-stream (Stremio) does:
 * the magnet's metadata, only the pieces the player is about to read at top priority with
 * deadlines, the rest of the file in order behind them, and the player started as soon as the
 * piece at its start offset is verified. Everything runs on IO threads; downloads go to
 * cache/torrents and are deleted on [stop].
 */
@Singleton
class TorrentEngine @Inject constructor(@ApplicationContext private val context: Context) {
    private var session: SessionManager? = null
    private var server: TorrentHttpServer? = null
    @Volatile private var active: ActiveFile? = null
    private val _progress = MutableStateFlow(TorrentProgress())
    val progress: StateFlow<TorrentProgress> = _progress.asStateFlow()
    private val dir get() = File(context.cacheDir, "torrents")
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var windowJob: kotlinx.coroutines.Job? = null
    /** Removing the last torrent and deleting its files (off the caller's thread). */
    @Volatile private var cleanupJob: kotlinx.coroutines.Job? = null
    /** Wakes the window loop at once (a new Range request / seek, a reader waiting for a piece). */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    /** Notified on every finished (hash-checked) piece; readers wait on it instead of sleeping. */
    private val pieceSignal = Object()
    private val readers = java.util.concurrent.ConcurrentHashMap<Int, Reader>()
    private val readerIds = java.util.concurrent.atomic.AtomicInteger()
    /** The stream's bitrate once the player knows the duration (0 = unknown). */
    @Volatile private var bitrateBytesPerSec = 0L
    /** The last read that had to wait (for the player's BUFFERING log). */
    @Volatile var lastWait: String? = null
        private set

    /**
     * The libtorrent session, started on first use. The native library is loaded by
     * [TorrentNative] first, so a device without a usable binary gets [TorrentUnavailableException]
     * and jlibtorrent's classes are never initialised in a failed state.
     */
    @Synchronized private fun session(): SessionManager = session ?: createSession()

    private fun createSession(): SessionManager {
        TorrentNative.ensureLoaded(context)
        return try {
            newSession()
        } catch (e: LinkageError) {
            // UnsatisfiedLinkError / NoClassDefFoundError / ExceptionInInitializerError from jlibtorrent.
            EnigmaLog.player("torrent session failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            throw TorrentUnavailableException(TorrentNative.processAbi(context), e.javaClass.simpleName)
        }
    }

    private fun newSession(): SessionManager = SessionManager(false).also { sm ->
        val params = SessionParams(streamingSettings())
        params.setPosixDiskIO()
        sm.start(params)
        // jlibtorrent enables every alert but the logs: one per 16 KB block and per upload request,
        // all crossing JNI. Only what the session manager and the engine use.
        runCatching { sm.applySettings(SettingsPack().setInteger(com.frostwire.jlibtorrent.swig.settings_pack.int_types.alert_mask.swigValue(), alertMask())) }
        sm.addListener(object : com.frostwire.jlibtorrent.AlertListener {
            override fun types() = intArrayOf(com.frostwire.jlibtorrent.alerts.AlertType.PIECE_FINISHED.swig())
            override fun alert(alert: com.frostwire.jlibtorrent.alerts.Alert<*>) {
                val a = alert as? com.frostwire.jlibtorrent.alerts.PieceFinishedAlert ?: return
                val f = active
                if (f != null && runCatching { a.handle().swig().id() == f.handleId }.getOrDefault(false)) f.mark(a.pieceIndex())
                synchronized(pieceSignal) { pieceSignal.notifyAll() }
                wake.trySend(Unit)
            }
        })
        session = sm
        EnigmaLog.player("torrent session started")
    }

    private fun alertMask(): Int {
        val a = com.frostwire.jlibtorrent.swig.alert::class.java
        return listOf(
            "error_notification", "status_notification", "storage_notification", "tracker_notification",
            "dht_notification", "piece_progress_notification", "port_mapping_notification", "ip_block_notification",
            "performance_warning",
        ).sumOf { name ->
            runCatching { (a.getField(name).get(null) as com.frostwire.jlibtorrent.swig.alert_category_t).to_int() }.getOrDefault(0)
        }
    }

    /** Settings for streaming one file: many peers fast, all of them on the same few pieces, no rate limits. */
    private fun streamingSettings(): SettingsPack {
        fun int(t: com.frostwire.jlibtorrent.swig.settings_pack.int_types) = t.swigValue()
        fun bool(t: com.frostwire.jlibtorrent.swig.settings_pack.bool_types) = t.swigValue()
        return SettingsPack()
            .connectionsLimit(200)
            .activeDownloads(4)
            .activeSeeds(1)
            .downloadRateLimit(0)
            .uploadRateLimit(0)
            .apply { setEnableDht(true); setEnableLsd(true) }
            .setBoolean(bool(com.frostwire.jlibtorrent.swig.settings_pack.bool_types.enable_upnp), true)
            .setBoolean(bool(com.frostwire.jlibtorrent.swig.settings_pack.bool_types.enable_natpmp), true)
            // Never drop seeds because nothing more is wanted for a moment (startup, a full window).
            .setBoolean(bool(com.frostwire.jlibtorrent.swig.settings_pack.bool_types.close_redundant_connections), false)
            // Many peers fill the same piece: no whole-piece requests per peer (a fast peer would take
            // an 8 MB piece alone), partial pieces first, no duplicate requests before the end game.
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.whole_pieces_threshold), 1)
            .setBoolean(bool(com.frostwire.jlibtorrent.swig.settings_pack.bool_types.prioritize_partial_pieces), true)
            .setBoolean(bool(com.frostwire.jlibtorrent.swig.settings_pack.bool_types.piece_extent_affinity), false)
            .setBoolean(bool(com.frostwire.jlibtorrent.swig.settings_pack.bool_types.strict_end_game_mode), true)
            // TCP and uTP side by side (prefer_tcp throttles uTP peers whenever a TCP peer is there).
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.mixed_mode_algorithm), 1)
            // Keep the peers we have.
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.peer_turnover), 0)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.max_failcount), 5)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.alert_queue_size), 10_000)
            // Outstanding requests per peer cover this many seconds of its rate; deep queues for fast peers.
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.request_queue_time), 4)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.max_out_request_queue), 1500)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.max_allowed_in_request_queue), 2000)
            // Connect quickly to many candidates; drop slow pieces/peers sooner.
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.connection_speed), 100)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.torrent_connect_boost), 40)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.peer_connect_timeout), 5)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.request_timeout), 10)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.piece_timeout), 10)
            // Uploading normally (tit-for-tat) keeps peers unchoking us: enough upload slots for that.
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.unchoke_slots_limit), 20)
            // Hash checks and disk writes must keep up with a fast swarm on a TV CPU.
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.aio_threads), 8)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.hashing_threads), 2)
            // libtorrent's default knows a single DHT router; with only a magnet that delays the first peers.
            .setString(com.frostwire.jlibtorrent.swig.settings_pack.string_types.dht_bootstrap_nodes.swigValue(), DHT_BOOTSTRAP)
    }

    /**
     * Fetches the metadata, starts the download of the stream's file from where playback starts
     * and waits only for the piece there (≈4 MB, plus the header on a resume). Returns the
     * local http address to play; the server blocks on any piece still missing.
     */
    suspend fun open(stream: TorrentStream, resumeMs: Long = 0, durationMs: Long = 0): String = withContext(Dispatchers.IO) {
        stopActive()
        cleanupJob?.join()
        val magnet = StremioStreams.magnet(stream) ?: throw IOException("no info hash")
        _progress.value = TorrentProgress(TorrentStage.METADATA)
        val work = File(dir, newToken())
        var file: ActiveFile? = null
        try {
            dir.listFiles()?.forEach { it.deleteRecursively() }
            work.mkdirs()
            val sm = session()
            val t0 = System.currentTimeMillis()
            EnigmaLog.player("torrent metadata ${stream.infoHash?.take(8)}…")
            val bytes = sm.fetchMagnet(magnet, METADATA_TIMEOUT_S, work) ?: throw IOException("no metadata")
            coroutineContext.ensureActive()
            val info = TorrentInfo.bdecode(bytes)
            val files = info.files()
            val index = chooseFile(stream.fileIdx, files.numFiles(), { files.fileName(it) }, { files.fileSize(it) })
            // Only the chosen file is wanted; its pieces get their own priorities at once below.
            val priorities = Priority.array(Priority.IGNORE, files.numFiles()).also { it[index] = Priority.FOUR }
            sm.download(info, work, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            var handle: TorrentHandle? = null
            repeat(50) { if (handle == null) { handle = sm.find(info)?.takeIf { it.isValid }; if (handle == null) delay(100) } }
            val h = handle ?: throw IOException("torrent not added")
            h.prioritizeFiles(priorities)
            val size = files.fileSize(index)
            val offset = files.fileOffset(index)
            val pieces = PieceMath.range(offset, size, info.pieceLength())
            val f = ActiveFile(
                handle = h,
                handleId = h.swig().id(),
                dir = work,
                file = File(work, files.filePath(index)),
                name = files.fileName(index),
                size = size,
                offset = offset,
                pieceLength = info.pieceLength(),
                firstPiece = pieces.first,
                lastPiece = pieces.last,
                numPieces = info.numPieces(),
            )
            file = f
            active = f
            // Pieces verified before the alerts were matched to this file (a fast first piece).
            for (p in pieces.first..minOf(pieces.last, pieces.first + 64)) if (h.havePiece(p)) f.mark(p)
            bitrateBytesPerSec = if (durationMs > 0) size * 1000 / durationMs else 0
            val startByte = StreamWindow.startByteFor(resumeMs, durationMs, size)
            val gate = StreamWindow.gatePieces(f.offset, f.size, f.pieceLength, startByte)
            readers.clear()
            startReader = Reader(startByte)
            EnigmaLog.player(
                "torrent ${f.size shr 20} MB in ${f.lastPiece - f.firstPiece + 1} pieces of ${f.pieceLength shr 10} KB, " +
                    "metadata ${System.currentTimeMillis() - t0} ms, start at piece ${PieceMath.pieceAt(f.offset, startByte, f.pieceLength)}",
            )
            windowJob = scope.launch { slideWindow(f, gate) }
            val srv = server ?: TorrentHttpServer(this@TorrentEngine).also { it.start(); server = it }
            _progress.value = TorrentProgress(TorrentStage.BUFFERING)
            val started = System.currentTimeMillis()
            val gateBytes = gate.size.toLong() * f.pieceLength
            var lastCheck = 0L
            while (true) {
                coroutineContext.ensureActive()
                if (f.closed) throw CancellationException("stopped")
                var done = gate.all(f::has)
                val now = System.currentTimeMillis()
                if (!done && now - lastCheck > RECHECK_MS) {
                    lastCheck = now
                    gate.forEach { if (!f.has(it) && h.havePiece(it)) f.mark(it) }
                    done = gate.all(f::has)
                }
                val st = h.status()
                _progress.value = TorrentProgress(
                    TorrentStage.BUFFERING, StreamWindow.startupProgress(st.totalPayloadDownload(), gateBytes, done),
                    st.downloadRate(), st.numPeers(), st.numSeeds(),
                )
                if (done) break
                if (now - started > BUFFER_TIMEOUT_MS) throw IOException("buffer timeout")
                synchronized(pieceSignal) { pieceSignal.wait(GATE_POLL_MS) }
            }
            _progress.value = _progress.value.copy(stage = TorrentStage.READY, buffer = 1f)
            EnigmaLog.player("torrent ready after ${System.currentTimeMillis() - t0} ms (first piece ${System.currentTimeMillis() - started} ms after metadata)")
            "http://127.0.0.1:${srv.listeningPort}/stream/${srv.token}"
        } catch (e: Throwable) {
            // Back on the loading page stops the torrent under this loop: a cancellation, not a failure.
            val cancelled = e is CancellationException || file?.closed == true || coroutineContext[kotlinx.coroutines.Job]?.isActive == false
            if (file == null || active === file) stopActive()
            if (file == null) cleanup(null, work)
            when {
                cancelled -> throw (e as? CancellationException ?: CancellationException("stopped").also { it.initCause(e) })
                e is TorrentUnavailableException -> {
                    _progress.value = TorrentProgress(TorrentStage.ERROR, error = e.abi)
                    throw e
                }
                else -> {
                    EnigmaLog.player("torrent failed: ${e.javaClass.simpleName}: ${e.message?.take(200)}" + (e.cause?.let { c -> " (cause ${c.javaClass.simpleName}: ${c.message?.take(120)})" } ?: ""))
                    _progress.value = TorrentProgress(TorrentStage.ERROR, error = e.javaClass.simpleName)
                    throw if (e is IOException) e else IOException(e)
                }
            }
        }
    }

    /** Download/peer numbers while playing (for the loading page and stream info). Call off the main thread. */
    fun refreshStats() {
        val f = active ?: return
        runCatching {
            val st = f.handle.status()
            _progress.value = _progress.value.copy(downloadBps = st.downloadRate(), peers = st.numPeers(), seeds = st.numSeeds())
        }
    }

    internal fun current(): ActiveFile? = active

    /** The player's duration, for the read-ahead and the deadline pacing. */
    fun setDuration(durationMs: Long) {
        val f = active ?: return
        if (durationMs > 0) bitrateBytesPerSec = f.size * 1000 / durationMs
    }

    /** A new HTTP response from [start] (ExoPlayer opens one per seek): the window follows it at once. */
    internal fun openReader(start: Long): Int {
        val id = readerIds.incrementAndGet()
        readers[id] = Reader(start)
        wake.trySend(Unit)
        return id
    }

    internal fun closeReader(id: Int) { readers.remove(id); wake.trySend(Unit) }

    /** Before the player's first request: where playback will start (the resume point). */
    @Volatile private var startReader: Reader? = null

    /**
     * Every 500 ms, and at once on a new reader / finished piece / read that waits:
     * - starting (gate not verified): only the critical pieces from the start offset (and the
     *   header on a resume) are wanted, plus the last piece low (the player's next read);
     * - playing: a window per open reader at top priority, the rest of the file after the first
     *   reader low (sequential), behind it none;
     * - deadlines on each reader's next few seconds and on pieces read on demand, set once,
     *   dropped when they leave those ranges.
     */
    private suspend fun slideWindow(f: ActiveFile, gate: List<Int>) {
        val h = f.handle
        val withDeadline = HashSet<Int>()
        var lastLog = 0L
        var lastCheck = 0L
        var lastPlan: Any? = null
        var starting = true
        while (!f.closed) {
            runCatching {
                if (starting && gate.all(f::has)) starting = false
                val positions = readers.values.map { it.pos }.ifEmpty { listOfNotNull(startReader?.pos) }
                    .map { it.coerceIn(0, f.size - 1) }.distinct()
                val heads = positions.map { PieceMath.pieceAt(f.offset, it, f.pieceLength) }
                val crit = StreamWindow.criticalCount(f.pieceLength, bitrateBytesPerSec)
                val critical = heads.map { it..minOf(f.lastPiece, it + crit - 1) }
                val windows = if (starting) critical + gate.map { it..it } else positions.mapIndexed { i, pos ->
                    heads[i]..StreamWindow.windowEnd(f.offset, f.size, f.pieceLength, pos, bitrateBytesPerSec)
                }
                f.demanded.removeAll { f.has(it) }
                val demanded = f.demanded.toSet()
                // Starting: the last piece low (mkv cues / mp4 moov, which the player reads before the first
                // frame) with the spare bandwidth; it also keeps the torrent unfinished, so peers stay.
                val guard = if (starting) f.lastPiece else null
                val plan = listOf(windows, demanded, starting, guard)
                if (plan != lastPlan) {
                    lastPlan = plan
                    applyPriorities(f) { p ->
                        when {
                            p < f.firstPiece || p > f.lastPiece -> StreamWindow.PRIORITY_SKIP
                            else -> StreamWindow.priorityOf(p, windows, demanded, starting).let { if (p == guard) maxOf(it, StreamWindow.PRIORITY_LOW) else it }
                        }
                    }
                }
                // Deadlines: the critical stretch of every reader (or the gate) and what was read on demand.
                val due = HashSet<Int>().apply { critical.forEach { r -> r.forEach { add(it) } }; addAll(demanded); if (starting) addAll(gate) }
                withDeadline.removeAll { p ->
                    val drop = f.has(p) || p !in due
                    if (drop && !f.has(p)) h.resetPieceDeadline(p)
                    drop
                }
                heads.forEach { head ->
                    StreamWindow.criticalDeadlines(head, f.lastPiece, f.pieceLength, bitrateBytesPerSec, f::has, withDeadline).forEach { d ->
                        h.setPieceDeadline(d.piece, d.deadlineMs)
                        withDeadline += d.piece
                    }
                }
                if (starting) gate.forEach { p -> if (!f.has(p) && withDeadline.add(p)) h.setPieceDeadline(p, 0) }
                // Read on demand: the reader already set their (paced) deadlines.
                demanded.forEach { p -> if (!f.has(p)) withDeadline.add(p) }
                val now = System.currentTimeMillis()
                // Alerts can be late or dropped: ask libtorrent itself, rarely, about the few pieces due.
                if (now - lastCheck > RECHECK_MS) {
                    lastCheck = now
                    withDeadline.forEach { if (!f.has(it) && h.havePiece(it)) f.mark(it) }
                }
                if (now - lastLog > LOG_EVERY_MS) {
                    lastLog = now
                    val st = h.status()
                    val ahead = positions.joinToString("/") { pos -> "${(StreamWindow.contiguousEnd(f.offset, f.size, f.pieceLength, pos, f::has) - pos) shr 20}" }
                    EnigmaLog.player(
                        "torrent ${if (starting) "starting" else "playing"} heads $heads readers ${readers.size} deadlines ${withDeadline.size} demanded ${demanded.size}, " +
                            "ready ahead $ahead MB, file ${f.count()}/${f.lastPiece - f.firstPiece + 1} pieces, " +
                            "${st.downloadRate() / 1024} KB/s, peers ${st.numPeers()} (seeds ${st.numSeeds()}), " +
                            "redundant ${st.totalRedundantBytes() shr 20} MB",
                    )
                }
            }
            kotlinx.coroutines.withTimeoutOrNull(WINDOW_TICK_MS) { wake.receive() }
        }
    }

    /** Sets the pieces whose priority changed: one call for many, single calls for a few. */
    private fun applyPriorities(f: ActiveFile, want: (Int) -> Int) {
        synchronized(f) {
            val changed = (0 until f.numPieces).filter { p -> !f.has(p) && f.prio[p] != want(p) }
            if (changed.isEmpty()) return
            changed.forEach { f.prio[it] = want(it) }
            if (changed.size > BULK_PRIORITIES) {
                f.handle.prioritizePieces(Array(f.numPieces) { Priority.fromSwig(f.prio[it]) })
            } else {
                changed.forEach { f.handle.piecePriority(it, Priority.fromSwig(f.prio[it])) }
            }
        }
    }

    /** A read of [piece] that is not there: it and the next pieces top priority, due now (the HTTP thread). */
    private fun demand(f: ActiveFile, piece: Int) {
        StreamWindow.demandPieces(piece, f.lastPiece, f.pieceLength, bitrateBytesPerSec, f::has).forEach { d ->
            if (f.demanded.add(d.piece)) {
                synchronized(f) {
                    f.prio[d.piece] = StreamWindow.PRIORITY_TOP
                    f.handle.piecePriority(d.piece, Priority.SEVEN)
                }
                f.handle.setPieceDeadline(d.piece, d.deadlineMs)
            }
        }
        wake.trySend(Unit)
    }

    /**
     * End of the data that can be played without waiting, from byte [pos] of the stream (for the
     * OSD's buffered bar); null when nothing is streaming.
     */
    fun contiguousEnd(pos: Long): Long? {
        val f = active ?: return null
        return StreamWindow.contiguousEnd(f.offset, f.size, f.pieceLength, pos, f::has)
    }

    /** Size of the file being streamed, 0 when none. */
    fun fileSize(): Long = active?.size ?: 0

    /**
     * Blocking read for the HTTP server: when the piece holding [pos] is missing it is asked for
     * at once (with the next ones) and the read waits on the piece-finished alerts; then it reads
     * at most up to that piece's end. [raf] opens the file only once there is data to read.
     */
    internal fun read(f: ActiveFile, reader: Int, pos: Long, buf: ByteArray, off: Int, len: Int, raf: () -> RandomAccessFile): Int {
        if (pos >= f.size) return -1
        readers[reader]?.let { it.pos = pos; it.touchedMs = System.currentTimeMillis() }
        val piece = PieceMath.pieceAt(f.offset, pos, f.pieceLength)
        if (!f.has(piece)) {
            demand(f, piece)
            val started = System.currentTimeMillis()
            var lastCheck = started
            while (!f.has(piece)) {
                if (f.closed) throw IOException("closed")
                val now = System.currentTimeMillis()
                if (now - started > READ_TIMEOUT_MS) throw IOException("piece timeout")
                if (now - lastCheck > RECHECK_MS) {
                    lastCheck = now
                    if (f.handle.havePiece(piece)) { f.mark(piece); break }
                }
                synchronized(pieceSignal) { if (!f.has(piece)) pieceSignal.wait(PIECE_POLL_MS) }
                readers[reader]?.touchedMs = System.currentTimeMillis()
            }
            val waited = System.currentTimeMillis() - started
            if (waited >= SLOW_READ_MS) {
                lastWait = "piece $piece at ${pos shr 20} MB waited $waited ms"
                EnigmaLog.player("torrent read waited $waited ms for piece $piece (offset $pos, reader $reader)")
            }
        }
        val pieceEnd = (piece + 1L) * f.pieceLength - f.offset
        val n = minOf(len.toLong(), pieceEnd - pos, f.size - pos).toInt()
        val file = raf()
        file.seek(pos)
        return file.read(buf, off, n)
    }

    /** Stops the stream: the torrent is removed and its files deleted in the background (any thread). */
    fun stop() {
        stopActive()
        _progress.value = TorrentProgress()
    }

    private fun stopActive() {
        val f = active ?: return
        active = null
        f.closed = true
        windowJob?.cancel()
        windowJob = null
        readers.clear()
        startReader = null
        lastWait = null
        synchronized(pieceSignal) { pieceSignal.notifyAll() }
        cleanup(f.handle, f.dir)
        EnigmaLog.player("torrent stopped")
    }

    private fun cleanup(handle: TorrentHandle?, folder: File) {
        val sm = session
        val previous = cleanupJob
        cleanupJob = scope.launch {
            previous?.join()
            if (handle != null) runCatching { sm?.remove(handle) }
            runCatching { folder.deleteRecursively() }
        }
    }

    companion object {
        const val METADATA_TIMEOUT_S = 60
        const val BUFFER_TIMEOUT_MS = 5 * 60_000L
        const val READ_TIMEOUT_MS = 60_000L
        const val WINDOW_TICK_MS = 500L
        const val PIECE_POLL_MS = 100L
        const val GATE_POLL_MS = 250L
        /** How often libtorrent itself is asked about pieces the alerts have not reported. */
        const val RECHECK_MS = 2_000L
        /** A read that waits this long is logged. */
        const val SLOW_READ_MS = 200L
        /** More changed priorities than this: one prioritizePieces call. */
        const val BULK_PRIORITIES = 16
        const val LOG_EVERY_MS = 10_000L
        const val DHT_BOOTSTRAP = "dht.libtorrent.org:25401,router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,router.bitcomet.com:6881"

        /** The stream's fileIdx when valid, else the largest video file, else the largest file. */
        fun chooseFile(fileIdx: Int?, count: Int, name: (Int) -> String, size: (Int) -> Long): Int {
            if (fileIdx != null && fileIdx in 0 until count) return fileIdx
            val all = (0 until count)
            return all.filter { PieceMath.isVideo(name(it)) }.maxByOrNull(size) ?: all.maxByOrNull(size) ?: 0
        }

        fun newToken(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
