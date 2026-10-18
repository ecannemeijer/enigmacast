package com.enigmacast.torrent

import com.enigmacast.domain.torrent.HttpRange
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Serves the file being streamed on 127.0.0.1 (random free port, random path token) with Range
 * support. Reads block until the needed pieces have arrived (see [TorrentEngine.read]).
 */
internal class TorrentHttpServer(private val engine: TorrentEngine) : NanoHTTPD("127.0.0.1", 0) {
    val token: String = TorrentEngine.newToken()

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/stream/$token") return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        val f = engine.current() ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        val mime = mimeOf(f.name)
        val range = HttpRange.parse(session.headers["range"], f.size)
        val response = when {
            range == HttpRange.UNSATISFIABLE ->
                newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "").also { it.addHeader("Content-Range", "bytes */${f.size}") }
            range == null -> newFixedLengthResponse(Response.Status.OK, mime, PieceStream(engine, f, 0, f.size), f.size)
            else -> newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, PieceStream(engine, f, range.start, range.length), range.length)
                .also { it.addHeader("Content-Range", "bytes ${range.start}-${range.end}/${f.size}") }
        }
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "mkv" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        else -> "application/octet-stream"
    }
}

/** [length] bytes of the file from [start], blocking per piece. */
private class PieceStream(private val engine: TorrentEngine, private val f: ActiveFile, start: Long, length: Long) : InputStream() {
    private var pos = start
    private val end = start + length
    private val raf by lazy {
        // The file appears once libtorrent writes the first piece; wait briefly for it.
        var tries = 0
        while (!f.file.exists() && tries++ < 100 && !f.closed) Thread.sleep(50)
        RandomAccessFile(f.file, "r")
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= end) return -1
        val n = engine.read(f, pos, b, off, minOf(len.toLong(), end - pos).toInt(), raf)
        if (n > 0) pos += n
        return n
    }

    override fun close() { runCatching { raf.close() } }
}
