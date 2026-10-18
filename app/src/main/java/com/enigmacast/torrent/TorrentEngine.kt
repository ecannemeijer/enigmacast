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
    val file: File,
    val name: String,
    val size: Long,
    val offset: Long,
    val pieceLength: Int,
    /** The file's first and last piece (not the torrent's). */
    val firstPiece: Int,
    val lastPiece: Int,
) {
    @Volatile var closed = false
}

/** One HTTP response being read: where it is now (a Range request starts a new one). */
internal class Reader(@Volatile var pos: Long) {
    @Volatile var touchedMs: Long = System.currentTimeMillis()
}

/**
 * libtorrent (jlibtorrent) for streaming one file at a time: the magnet's metadata, only the
 * chosen file downloaded, sequentially, with deadlines on the pieces the player needs next.
 * Everything runs on IO threads; downloads go to cache/torrents and are deleted on [stop].
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
    /** Wakes the window loop at once (a new Range request / seek, a reader waiting for a piece). */
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    /** Notified on every finished (hash-checked) piece; readers wait on it instead of sleeping. */
    private val pieceSignal = Object()
    private val readers = java.util.concurrent.ConcurrentHashMap<Int, Reader>()
    private val readerIds = java.util.concurrent.atomic.AtomicInteger()
    /** The stream's bitrate once the player knows the duration (0 = unknown). */
    @Volatile private var bitrateBytesPerSec = 0L

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
        sm.addListener(object : com.frostwire.jlibtorrent.AlertListener {
            override fun types() = intArrayOf(com.frostwire.jlibtorrent.alerts.AlertType.PIECE_FINISHED.swig())
            override fun alert(alert: com.frostwire.jlibtorrent.alerts.Alert<*>) {
                synchronized(pieceSignal) { pieceSignal.notifyAll() }
            }
        })
        session = sm
        EnigmaLog.player("torrent session started")
    }

    /** Settings for streaming one file: many peers fast, deep request queues, no rate limits. */
    private fun streamingSettings(): SettingsPack {
        fun int(t: com.frostwire.jlibtorrent.swig.settings_pack.int_types) = t.swigValue()
        fun bool(t: com.frostwire.jlibtorrent.swig.settings_pack.bool_types) = t.swigValue()
        return SettingsPack()
            .connectionsLimit(400)
            .activeDownloads(4)
            .activeSeeds(1)
            .downloadRateLimit(0)
            .uploadRateLimit(0)
            .apply { setEnableDht(true); setEnableLsd(true) }
            .setBoolean(bool(com.frostwire.jlibtorrent.swig.settings_pack.bool_types.enable_upnp), true)
            .setBoolean(bool(com.frostwire.jlibtorrent.swig.settings_pack.bool_types.enable_natpmp), true)
            .setBoolean(bool(com.frostwire.jlibtorrent.swig.settings_pack.bool_types.close_redundant_connections), true)
            // Outstanding requests per peer cover this many seconds of its rate; deep queues for fast peers.
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.request_queue_time), 4)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.max_out_request_queue), 1500)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.max_allowed_in_request_queue), 2000)
            // Connect quickly to many candidates; drop slow pieces/peers sooner.
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.connection_speed), 50)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.torrent_connect_boost), 40)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.peer_connect_timeout), 7)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.request_timeout), 10)
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.piece_timeout), 10)
            // Uploading normally (tit-for-tat) keeps peers unchoking us; enough slots for that.
            .setInteger(int(com.frostwire.jlibtorrent.swig.settings_pack.int_types.unchoke_slots_limit), 8)
    }

    /**
     * Fetches the metadata, starts the download of the stream's file and waits until the first
     * ~8 MB and the last pieces are there. Returns the local http address to play.
     */
    suspend fun open(stream: TorrentStream): String = withContext(Dispatchers.IO) {
        stopActive()
        val magnet = StremioStreams.magnet(stream) ?: throw IOException("no info hash")
        _progress.value = TorrentProgress(TorrentStage.METADATA)
        try {
            dir.deleteRecursively(); dir.mkdirs()
            val sm = session()
            EnigmaLog.player("torrent metadata ${stream.infoHash?.take(8)}…")
            val bytes = sm.fetchMagnet(magnet, METADATA_TIMEOUT_S, dir) ?: throw IOException("no metadata")
            coroutineContext.ensureActive()
            val info = TorrentInfo.bdecode(bytes)
            val files = info.files()
            val index = chooseFile(stream.fileIdx, files.numFiles(), { files.fileName(it) }, { files.fileSize(it) })
            // Only the chosen file: every other file IGNORE, the full array at once.
            val priorities = Priority.array(Priority.IGNORE, files.numFiles()).also { it[index] = Priority.SEVEN }
            sm.download(info, dir, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            var handle: TorrentHandle? = null
            repeat(50) { if (handle == null) { handle = sm.find(info)?.takeIf { it.isValid }; if (handle == null) delay(100) } }
            val h = handle ?: throw IOException("torrent not added")
            h.prioritizeFiles(priorities)
            val size = files.fileSize(index)
            val offset = files.fileOffset(index)
            val pieces = PieceMath.range(offset, size, info.pieceLength())
            val file = ActiveFile(
                handle = h,
                file = File(dir, files.filePath(index)),
                name = files.fileName(index),
                size = size,
                offset = offset,
                pieceLength = info.pieceLength(),
                firstPiece = pieces.first,
                lastPiece = pieces.last,
            )
            active = file
            bitrateBytesPerSec = 0
            // Startup: the head (≈4 % of the file, 8–64 MB, contiguous from its first piece) and the
            // last pieces (mkv cues / mp4 moov). The tail first, then the head in order.
            val startup = StreamWindow.startupPieces(file.offset, file.size, file.pieceLength)
            val tailPieces = (file.lastPiece - StreamWindow.TAIL_PIECES + 1).coerceAtLeast(file.firstPiece)..file.lastPiece
            val tail = startup.filter { it in tailPieces }
            tail.forEach { h.setPieceDeadline(it, 0) }
            startup.filter { it !in tail }.forEachIndexed { i, p -> h.setPieceDeadline(p, StreamWindow.FIRST_MS + i * StreamWindow.STEP_MS) }
            windowJob = scope.launch { slideWindow(file) }
            _progress.value = TorrentProgress(TorrentStage.BUFFERING)
            val started = System.currentTimeMillis()
            while (true) {
                coroutineContext.ensureActive()
                // havePiece is true only for pieces that passed the hash check.
                val st = h.status()
                _progress.value = TorrentProgress(TorrentStage.BUFFERING, StreamWindow.startupProgress(startup, h::havePiece), st.downloadRate(), st.numPeers(), st.numSeeds())
                if (StreamWindow.startupDone(startup, h::havePiece)) break
                if (System.currentTimeMillis() - started > BUFFER_TIMEOUT_MS) throw IOException("buffer timeout")
                delay(500)
            }
            val srv = server ?: TorrentHttpServer(this@TorrentEngine).also { it.start(); server = it }
            _progress.value = _progress.value.copy(stage = TorrentStage.READY, buffer = 1f)
            EnigmaLog.player("torrent ready (${file.size shr 20} MB)")
            "http://127.0.0.1:${srv.listeningPort}/stream/${srv.token}"
        } catch (e: CancellationException) {
            stopActive()
            throw e
        } catch (e: TorrentUnavailableException) {
            _progress.value = TorrentProgress(TorrentStage.ERROR, error = e.abi)
            throw e
        } catch (e: Throwable) {
            EnigmaLog.player("torrent failed: ${e.javaClass.simpleName}")
            stopActive()
            _progress.value = TorrentProgress(TorrentStage.ERROR, error = e.javaClass.simpleName)
            throw if (e is IOException) e else IOException(e)
        }
    }

    /** Download/peer numbers while playing (for the loading page and stream info). */
    fun refreshStats() {
        val f = active ?: return
        runCatching {
            val st = f.handle.status()
            _progress.value = _progress.value.copy(downloadBps = st.downloadRate(), peers = st.numPeers(), seeds = st.numSeeds())
        }
    }

    internal fun current(): ActiveFile? = active

    /** The player's duration, for the read-ahead (20 s of bitrate when that is more than 32 MB). */
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

    internal fun closeReader(id: Int) { readers.remove(id) }

    /** The piece being played: where the most recently active reader is. */
    private fun playhead(f: ActiveFile): Int? =
        readers.values.maxByOrNull { it.touchedMs }?.let { PieceMath.pieceAt(f.offset, it.pos.coerceAtMost(f.size - 1), f.pieceLength) }

    /**
     * Every 500 ms (or at once on a seek): staggered deadlines and top priority on the missing
     * pieces of the window after the playhead; deadlines dropped for pieces that left it.
     */
    private suspend fun slideWindow(f: ActiveFile) {
        val h = f.handle
        val withDeadline = HashSet<Int>()
        var lastHead: Int? = null
        var lastLog = 0L
        while (!f.closed) {
            runCatching {
                val head = playhead(f) ?: f.firstPiece
                val count = StreamWindow.pieceCount(f.pieceLength, bitrateBytesPerSec)
                if (StreamWindow.isSeek(lastHead, head, count)) {
                    withDeadline.forEach { if (!h.havePiece(it)) h.resetPieceDeadline(it) }
                    withDeadline.clear()
                }
                val window = StreamWindow.window(head, f.lastPiece, count)
                StreamWindow.stale(withDeadline, window).forEach { p ->
                    if (!h.havePiece(p)) h.resetPieceDeadline(p)
                    withDeadline.remove(p)
                }
                val due = StreamWindow.deadlines(window, h::havePiece)
                due.forEach { (p, ms) ->
                    h.piecePriority(p, Priority.SEVEN)
                    h.setPieceDeadline(p, ms)
                    withDeadline += p
                }
                withDeadline.retainAll(due.mapTo(HashSet()) { it.piece })
                lastHead = head
                val now = System.currentTimeMillis()
                if (now - lastLog > LOG_EVERY_MS) {
                    lastLog = now
                    val st = h.status()
                    EnigmaLog.player(
                        "torrent window ${window.first}..${window.last} missing ${due.size}/${window.count()}, " +
                            "file ${(f.firstPiece..f.lastPiece).count(h::havePiece)}/${f.lastPiece - f.firstPiece + 1} pieces, " +
                            "${st.downloadRate() / 1024} KB/s, peers ${st.numPeers()} (seeds ${st.numSeeds()})",
                    )
                }
            }
            kotlinx.coroutines.withTimeoutOrNull(WINDOW_TICK_MS) { wake.receive() }
        }
    }

    /**
     * Blocking read for the HTTP server: waits for the piece holding [pos] (the window loop gives
     * it the first deadline), then reads at most up to that piece's end. Waiting is on the
     * piece-finished alerts with a short poll, never on a lock other readers need.
     */
    internal fun read(f: ActiveFile, reader: Int, pos: Long, buf: ByteArray, off: Int, len: Int, raf: RandomAccessFile): Int {
        if (pos >= f.size) return -1
        readers[reader]?.let { it.pos = pos; it.touchedMs = System.currentTimeMillis() }
        val piece = PieceMath.pieceAt(f.offset, pos, f.pieceLength)
        if (!f.handle.havePiece(piece)) {
            f.handle.setPieceDeadline(piece, 0)
            wake.trySend(Unit)
            val until = System.currentTimeMillis() + READ_TIMEOUT_MS
            while (!f.handle.havePiece(piece)) {
                if (f.closed) throw IOException("closed")
                if (System.currentTimeMillis() > until) throw IOException("piece timeout")
                synchronized(pieceSignal) { pieceSignal.wait(PIECE_POLL_MS) }
                readers[reader]?.touchedMs = System.currentTimeMillis()
            }
        }
        val pieceEnd = (piece + 1L) * f.pieceLength - f.offset
        val n = minOf(len.toLong(), pieceEnd - pos, f.size - pos).toInt()
        raf.seek(pos)
        return raf.read(buf, off, n)
    }

    /** Stops the stream: removes the torrent and deletes its files (the session stays for the next one). */
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
        synchronized(pieceSignal) { pieceSignal.notifyAll() }
        runCatching { session?.remove(f.handle) }
        runCatching { dir.deleteRecursively() }
        EnigmaLog.player("torrent stopped")
    }

    companion object {
        const val METADATA_TIMEOUT_S = 60
        const val BUFFER_TIMEOUT_MS = 5 * 60_000L
        const val READ_TIMEOUT_MS = 60_000L
        const val WINDOW_TICK_MS = 500L
        const val PIECE_POLL_MS = 100L
        const val LOG_EVERY_MS = 10_000L

        /** The stream's fileIdx when valid, else the largest video file, else the largest file. */
        fun chooseFile(fileIdx: Int?, count: Int, name: (Int) -> String, size: (Int) -> Long): Int {
            if (fileIdx != null && fileIdx in 0 until count) return fileIdx
            val all = (0 until count)
            return all.filter { PieceMath.isVideo(name(it)) }.maxByOrNull(size) ?: all.maxByOrNull(size) ?: 0
        }

        fun newToken(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
