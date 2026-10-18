package com.enigmacast.torrent

import android.content.Context
import com.enigmacast.domain.torrent.PieceMath
import com.enigmacast.domain.torrent.StremioStreams
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
    val lastPiece: Int,
) {
    @Volatile var closed = false
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
        val settings = SettingsPack().connectionsLimit(200).activeDownloads(2)
        val params = SessionParams(settings)
        params.setPosixDiskIO()
        sm.start(params)
        session = sm
        EnigmaLog.player("torrent session started")
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
            val priorities = Priority.array(Priority.IGNORE, files.numFiles()).also { it[index] = Priority.SEVEN }
            sm.download(info, dir, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            var handle: TorrentHandle? = null
            repeat(50) { if (handle == null) { handle = sm.find(info)?.takeIf { it.isValid }; if (handle == null) delay(100) } }
            val h = handle ?: throw IOException("torrent not added")
            h.prioritizeFiles(priorities)
            val file = ActiveFile(
                handle = h,
                file = File(dir, files.filePath(index)),
                name = files.fileName(index),
                size = files.fileSize(index),
                offset = files.fileOffset(index),
                pieceLength = info.pieceLength(),
                lastPiece = info.numPieces() - 1,
            )
            active = file
            val startup = PieceMath.startupPieces(file.offset, file.size, file.pieceLength)
            startup.forEachIndexed { i, p -> h.setPieceDeadline(p, 1_000 + i * 150) }
            _progress.value = TorrentProgress(TorrentStage.BUFFERING)
            val started = System.currentTimeMillis()
            while (true) {
                coroutineContext.ensureActive()
                val have = startup.count { h.havePiece(it) }
                val st = h.status()
                _progress.value = TorrentProgress(TorrentStage.BUFFERING, have / startup.size.toFloat(), st.downloadRate(), st.numPeers(), st.numSeeds())
                if (have == startup.size) break
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

    /**
     * Blocking read for the HTTP server: waits (with deadlines on the needed pieces) until the
     * piece holding [pos] is downloaded, then reads at most up to that piece's end.
     */
    internal fun read(f: ActiveFile, pos: Long, buf: ByteArray, off: Int, len: Int, raf: RandomAccessFile): Int {
        if (pos >= f.size) return -1
        val piece = PieceMath.pieceAt(f.offset, pos, f.pieceLength)
        if (!f.handle.havePiece(piece)) {
            for (i in 0 until READ_AHEAD) {
                val p = piece + i
                if (p <= f.lastPiece) f.handle.setPieceDeadline(p, i * 300)
            }
            val until = System.currentTimeMillis() + READ_TIMEOUT_MS
            while (!f.handle.havePiece(piece)) {
                if (f.closed) throw IOException("closed")
                if (System.currentTimeMillis() > until) throw IOException("piece timeout")
                Thread.sleep(50)
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
        runCatching { session?.remove(f.handle) }
        runCatching { dir.deleteRecursively() }
        EnigmaLog.player("torrent stopped")
    }

    companion object {
        const val METADATA_TIMEOUT_S = 60
        const val BUFFER_TIMEOUT_MS = 5 * 60_000L
        const val READ_TIMEOUT_MS = 60_000L
        const val READ_AHEAD = 6

        /** The stream's fileIdx when valid, else the largest video file, else the largest file. */
        fun chooseFile(fileIdx: Int?, count: Int, name: (Int) -> String, size: (Int) -> Long): Int {
            if (fileIdx != null && fileIdx in 0 until count) return fileIdx
            val all = (0 until count)
            return all.filter { PieceMath.isVideo(name(it)) }.maxByOrNull(size) ?: all.maxByOrNull(size) ?: 0
        }

        fun newToken(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
