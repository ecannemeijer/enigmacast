package com.enigmacast.data.profile

import com.enigmacast.domain.profile.photo.HttpError
import com.enigmacast.domain.profile.photo.HttpRequestParser
import com.enigmacast.domain.profile.photo.HttpResponse
import com.enigmacast.domain.profile.photo.PhotoImage
import com.enigmacast.domain.profile.photo.PhotoPage
import com.enigmacast.domain.profile.photo.UploadToken
import com.enigmacast.util.EnigmaLog
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A tiny one-off HTTP server on the TV's LAN address for one photo upload. Serves the phone page
 * and takes one POST. Stops on success, on [stop] or when the token expires. The token is never logged.
 */
class PhotoUploadServer(
    private val address: InetAddress,
    private val token: UploadToken,
    private val profileName: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Stores the photo; throws [AvatarPhotos.Rejected]. Runs on the server thread. */
    private val onPhoto: (bytes: ByteArray, x: Double?, y: Double?, side: Double?) -> Unit,
    private val onSuccess: () -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(2) { r -> Thread(r, "ec-photo-http").apply { isDaemon = true } }

    val port: Int get() = socket?.localPort ?: -1

    fun url(): String = "http://${address.hostAddress}:$port/upload?token=${token.value}"

    /** Binds to [address] on the first free port from [preferred], else any free port. */
    fun start(preferred: IntRange = 8765..8775) {
        fun open(p: Int): ServerSocket? {
            val s = ServerSocket()
            return try { s.bind(InetSocketAddress(address, p), 8); s } catch (_: Exception) { runCatching { s.close() }; null }
        }
        val server = preferred.firstNotNullOfOrNull { open(it) } ?: open(0) ?: throw java.io.IOException("no free port")
        socket = server
        running.set(true)
        EnigmaLog.network("photo upload: listening on ${address.hostAddress}:${server.localPort}")
        Thread({ acceptLoop(server) }, "ec-photo-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { socket?.close() }
        workers.shutdownNow()
        EnigmaLog.network("photo upload: stopped")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = try { server.accept() } catch (_: SocketException) { break } catch (_: Exception) { continue }
            if (token.expired(clock())) { runCatching { client.close() }; stop(); break }
            runCatching { workers.execute { handle(client) } }.onFailure { runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) = client.use { s ->
        s.soTimeout = 20_000
        val input = s.getInputStream().buffered()
        val out = s.getOutputStream()
        try {
            var req = HttpRequestParser.parseHead(input)
            val lang = PhotoPage.language(req.header("accept-language"))
            val tokenOk = token.accepts(req.query["token"], clock())
            when {
                req.path != "/upload" -> send(out, 404, "text/plain; charset=utf-8", "Not found")
                req.method == "GET" || req.method == "HEAD" ->
                    if (tokenOk) send(out, 200, HTML, PhotoPage.page(lang, profileName), req.method == "HEAD")
                    else send(out, 403, HTML, PhotoPage.gone(lang), req.method == "HEAD")
                !tokenOk -> json(out, 403, "token")
                else -> {
                    req = HttpRequestParser.readBody(req, input, PhotoImage.MAX_BYTES)
                    val x = req.query["x"]?.toDoubleOrNull()
                    val y = req.query["y"]?.toDoubleOrNull()
                    val side = req.query["s"]?.toDoubleOrNull()
                    synchronized(this) {
                        // A second upload racing the first finds the token spent.
                        if (!token.accepts(req.query["token"], clock())) return@use json(out, 403, "token")
                        try {
                            onPhoto(req.body, x, y, side)
                        } catch (e: AvatarPhotos.Rejected) {
                            EnigmaLog.network("photo upload: rejected (${e.code}, ${req.body.size} bytes)")
                            return@use json(out, if (e.code == "size") 413 else 415, e.code)
                        }
                        token.consume()
                    }
                    EnigmaLog.network("photo upload: saved (${req.body.size} bytes)")
                    json(out, 200, null)
                    onSuccess()
                    stop()
                }
            }
        } catch (e: HttpError) {
            runCatching { json(out, e.status, if (e.status == 413) "size" else "request") }
        } catch (e: Exception) {
            EnigmaLog.network("photo upload: ${e.javaClass.simpleName}")
            runCatching { json(out, 500, "server") }
        }
    }

    private fun send(out: java.io.OutputStream, status: Int, type: String, text: String, headOnly: Boolean = false) =
        HttpResponse.write(out, status, type, text.toByteArray(Charsets.UTF_8), headOnly)

    private fun json(out: java.io.OutputStream, status: Int, error: String?) =
        send(out, status, "application/json", if (error == null) """{"ok":true}""" else """{"ok":false,"error":"$error"}""")

    companion object {
        private const val HTML = "text/html; charset=utf-8"

        /** The TV's private IPv4 address on Wi-Fi or Ethernet; null without a LAN. */
        fun lanAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
                .sortedBy { rank(it.name) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrNull()

        internal fun rank(name: String) = when {
            name.startsWith("eth") -> 0
            name.startsWith("wlan") -> 1
            else -> 2
        }
    }
}
