package com.enigmacast.companionbridge

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * HTTPS + WSS op één poort, zonder serverframework. De HTTP-parser en de WebSocket-frames
 * zijn bewust klein gehouden; alles wat de brug nodig heeft staat in het protocol.
 */
class CompanionServer(
    private val port: Int,
    private val tvId: String,
    private val tvName: String,
    private val appVersion: String,
    private val auth: CompanionAuth,
    private val devices: CompanionDevices,
    private val pairing: CompanionPairing,
    private val hello: () -> CompanionEnvelope,
    private val state: () -> CompanionEnvelope,
    private val form: () -> JsonObject?,
    private val settings: () -> JsonObject?,
    private val library: () -> JsonObject?,
    private val recent: () -> JsonObject?,
    private val onPhoto: suspend (PhotoRequest) -> PhotoResult,
    private val onPhotoRead: suspend (String) -> ByteArray?,
    private val onPhotoDelete: suspend (String) -> Boolean,
    private val onCommand: suspend (CompanionEnvelope) -> CompanionEnvelope,
    private val onClients: (Int) -> Unit,
    private val log: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = CopyOnWriteArrayList<Client>()
    private val pairingAttempts = ConcurrentHashMap<String, MutableList<Long>>()
    private var serverSocket: SSLServerSocket? = null

    @Volatile
    private var running = false

    fun start(): Result<Unit> {
        if (running) return Result.success(Unit)
        val context = runCatching { CompanionTls.serverContext() }
            .getOrElse { return Result.failure(it) }
        val socket = try {
            (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            }
        } catch (error: Exception) {
            return Result.failure(error)
        }
        serverSocket = socket
        running = true
        scope.launch {
            log("companion: listening on port $port")
            while (running) {
                val client = try {
                    socket.accept() as? SSLSocket
                } catch (error: Exception) {
                    if (running) log("companion: accept failed ${error.javaClass.simpleName}")
                    null
                } ?: break
                scope.launch { handle(client) }
            }
        }
        return Result.success(Unit)
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.forEach { it.close() }
        clients.clear()
        onClients(0)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    fun broadcastState() {
        if (!running) return
        val envelope = state()
        clients.forEach { client ->
            runCatching { client.send(envelope, client.nextSeq()) }.onFailure { clients.remove(client) }
        }
    }

    fun broadcastProfiles(profilesJson: JsonObject) {
        if (!running) return
        val payload = buildJsonObject { put("profile", profilesJson) }
        val envelope = CompanionProtocol.envelope(CompanionProtocol.TYPE_STATE_PROFILES, payload)
        clients.forEach { client ->
            runCatching { client.send(envelope, client.nextSeq()) }.onFailure { clients.remove(client) }
        }
    }

    fun broadcastForm(formJson: JsonObject) {
        if (!running) return
        val envelope = CompanionProtocol.envelope(CompanionProtocol.TYPE_FORM, formJson)
        clients.forEach { client ->
            runCatching { client.send(envelope, client.nextSeq()) }.onFailure { clients.remove(client) }
        }
    }

    fun broadcastSettings(settingsJson: JsonObject) {
        if (!running) return
        val envelope = CompanionProtocol.envelope(CompanionProtocol.TYPE_STATE_SETTINGS, settingsJson)
        clients.forEach { client ->
            runCatching { client.send(envelope, client.nextSeq()) }.onFailure { clients.remove(client) }
        }
    }

    fun broadcastLibrary(libraryJson: JsonObject) {
        if (!running) return
        val envelope = CompanionProtocol.envelope(CompanionProtocol.TYPE_STATE_LIBRARY, libraryJson)
        clients.forEach { client ->
            runCatching { client.send(envelope, client.nextSeq()) }.onFailure { clients.remove(client) }
        }
    }

    fun broadcastRecent(recentJson: JsonObject) {
        if (!running) return
        val envelope = CompanionProtocol.envelope(CompanionProtocol.TYPE_STATE_RECENT, recentJson)
        clients.forEach { client ->
            runCatching { client.send(envelope, client.nextSeq()) }.onFailure { clients.remove(client) }
        }
    }

    // ---------------------------------------------------------------- verbindingen

    private suspend fun handle(socket: SSLSocket) {
        socket.use { ssl ->
            ssl.soTimeout = 30_000
            val input = BufferedInputStream(ssl.inputStream)
            val output = BufferedOutputStream(ssl.outputStream)
            val request = try {
                readRequest(input)
            } catch (error: Exception) {
                log("companion: bad request ${error.javaClass.simpleName}")
                null
            } ?: return

            when {
                request.method == "GET" && request.path == CompanionProtocol.HEALTH_PATH ->
                    respondJson(output, 200, healthJson())
                request.method == "GET" && request.path == CompanionProtocol.INFO_PATH ->
                    handleInfo(request, output)
                request.method == "POST" && request.path == CompanionProtocol.PAIR_PATH ->
                    handlePair(request, ssl, output)
                request.method == "GET" && request.path == CompanionProtocol.WS_PATH ->
                    handleUpgrade(request, ssl, input, output)
                request.method in setOf("PUT", "GET", "DELETE") &&
                    request.path.startsWith("/api/v1/profiles/") &&
                    request.path.endsWith("/photo") ->
                    handlePhoto(request, output)
                else -> respondJson(output, 404, errorJson(CompanionProtocol.ERR_NOT_FOUND, "not found"))
            }
        }
    }

    private fun handleInfo(request: HttpRequest, output: OutputStream) {
        val device = try {
            auth.authenticate(request.headers, request.method, request.signedPath, request.body)
        } catch (error: CompanionAuthException) {
            respondJson(output, 401, errorJson(error.code, error.message.orEmpty()))
            return
        }
        device.let { devicesTouch(it.id) }
        respondJson(
            output,
            200,
            buildJsonObject {
                put("tv", CompanionProtocol.tvJson(tvId, tvName, appVersion))
                put("api", "v1")
                putStrings("capabilities", CompanionProtocol.CAPABILITIES)
                put("settingsRevision", 1)
            },
        )
    }

    private fun handlePair(request: HttpRequest, socket: SSLSocket, output: OutputStream) {
        val address = socket.inetAddress?.hostAddress.orEmpty()
        if (!allowPairAttempt(address)) {
            respondJson(output, 429, errorJson(CompanionProtocol.ERR_PAIR_RATE, "too many attempts"))
            return
        }
        val body = runCatching { CompanionProtocol.JSON.parseToJsonElement(request.body.toString(Charsets.UTF_8)) as JsonObject }
            .getOrNull()
        if (body == null) {
            respondJson(output, 400, errorJson(CompanionProtocol.ERR_AUTH_SIGNATURE, "invalid body"))
            return
        }
        val code = body.string("code")
        val name = body.string("name").ifBlank { "Telefoon" }
        val publicKey = body.string("publicKey")
        if (publicKey.isBlank() || !validPublicKey(publicKey)) {
            respondJson(output, 400, errorJson(CompanionProtocol.ERR_AUTH_SIGNATURE, "invalid public key"))
            return
        }
        if (!pairing.consume(code)) {
            registerPairFailure(address)
            respondJson(output, 401, errorJson(CompanionProtocol.ERR_PAIR_CODE, "pairing code rejected"))
            return
        }
        val (device, token) = kotlinx.coroutines.runBlocking { devices.add(name, publicKey) }
        log("companion: paired ${device.name}")
        respondJson(
            output,
            200,
            buildJsonObject {
                put("deviceId", device.id)
                put("token", token)
                put("role", device.role)
                put("tv", CompanionProtocol.tvJson(tvId, tvName, appVersion))
                put("api", "v1")
                putStrings("capabilities", CompanionProtocol.CAPABILITIES)
            },
        )
    }

    private suspend fun handleUpgrade(
        request: HttpRequest,
        socket: SSLSocket,
        input: InputStream,
        output: OutputStream,
    ) {
        val upgrade = request.headers["upgrade"].orEmpty()
        val key = request.headers["sec-websocket-key"]
        if (!upgrade.equals("websocket", ignoreCase = true) || key.isNullOrBlank()) {
            respondJson(output, 400, errorJson(CompanionProtocol.ERR_NOT_FOUND, "websocket expected"))
            return
        }
        val device = try {
            auth.authenticate(request.headers, request.method, request.signedPath, request.body)
        } catch (error: CompanionAuthException) {
            respondJson(output, 401, errorJson(error.code, error.message.orEmpty()))
            return
        }
        val accept = CompanionWebSocket.acceptKey(key)
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()

        val client = Client(device.id, socket, output)
        clients += client
        onClients(clients.size)
        device.let { devicesTouch(it.id) }
        runCatching {
            client.send(hello(), 0)
            client.send(state(), 1)
            form()?.let { client.send(CompanionProtocol.envelope(CompanionProtocol.TYPE_FORM, it), 2) }
            settings()?.let { client.send(CompanionProtocol.envelope(CompanionProtocol.TYPE_STATE_SETTINGS, it), 3) }
            library()?.let { client.send(CompanionProtocol.envelope(CompanionProtocol.TYPE_STATE_LIBRARY, it), 4) }
            recent()?.let { client.send(CompanionProtocol.envelope(CompanionProtocol.TYPE_STATE_RECENT, it), 5) }
        }

        var fragment: ByteArrayOutputStream? = null
        try {
            while (running) {
                val frame = CompanionWebSocket.readFrame(input) ?: break
                when (frame.opcode) {
                    CompanionWebSocket.opcodeClose() -> break
                    CompanionWebSocket.opcodePing() -> CompanionWebSocket.writePong(output, frame.payload)
                    CompanionWebSocket.opcodeText() -> {
                        if (frame.fin) {
                            handleText(client, String(frame.payload, Charsets.UTF_8))
                        } else {
                            fragment = ByteArrayOutputStream().apply { write(frame.payload) }
                        }
                    }
                    CompanionWebSocket.opcodeContinuation() -> {
                        val buffer = fragment ?: continue
                        buffer.write(frame.payload)
                        if (buffer.size() > CompanionProtocol.MAX_FRAME_BYTES) break
                        if (frame.fin) {
                            fragment = null
                            handleText(client, String(buffer.toByteArray(), Charsets.UTF_8))
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (error !is EOFException) log("companion: ws closed ${error.javaClass.simpleName}")
        } finally {
            clients.remove(client)
            onClients(clients.size)
            client.close()
        }
    }

    private suspend fun handlePhoto(request: HttpRequest, output: OutputStream) {
        val device = try {
            auth.authenticate(request.headers, request.method, request.signedPath, request.body)
        } catch (error: CompanionAuthException) {
            respondJson(output, 401, errorJson(error.code, error.message.orEmpty()))
            return
        }
        device.let { devicesTouch(it.id) }
        val id = request.path.removePrefix("/api/v1/profiles/").removeSuffix("/photo").trim('/')
        if (id.isBlank()) {
            respondJson(output, 404, errorJson(CompanionProtocol.ERR_NOT_FOUND, "profile"))
            return
        }
        when (request.method) {
            "GET" -> {
                val bytes = onPhotoRead(id)
                if (bytes == null) {
                    respondJson(output, 404, errorJson(CompanionProtocol.ERR_NOT_FOUND, "photo"))
                } else {
                    respondBytes(output, 200, bytes, "image/webp")
                }
                return
            }
            "DELETE" -> {
                if (onPhotoDelete(id)) {
                    respondJson(output, 200, buildJsonObject { put("deleted", true) })
                } else {
                    respondJson(output, 404, errorJson(CompanionProtocol.ERR_NOT_FOUND, "photo"))
                }
                return
            }
        }
        val query = request.query.split('&').mapNotNull { part ->
            val key = part.substringBefore('=')
            if (key.isBlank()) null else key to java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }.toMap()
        val result = onPhoto(
            PhotoRequest(
                profileId = id,
                bytes = request.body,
                x = query["x"]?.toDoubleOrNull(),
                y = query["y"]?.toDoubleOrNull(),
                side = query["s"]?.toDoubleOrNull(),
            ),
        )
        when (result) {
            is PhotoResult.Saved -> respondJson(output, 200, buildJsonObject { put("stamp", result.stamp) })
            is PhotoResult.Rejected -> when (result.code) {
                "size" -> respondJson(output, 413, errorJson(CompanionProtocol.ERR_PAYLOAD, "image too large"))
                "profile" -> respondJson(output, 404, errorJson(CompanionProtocol.ERR_NOT_FOUND, "profile"))
                else -> respondJson(output, 422, errorJson("VALIDATION", "image rejected"))
            }
        }
    }

    private suspend fun handleText(client: Client, text: String) {
        val envelope = CompanionProtocol.decode(text)
        if (envelope == null) {
            client.send(CompanionProtocol.envelope(CompanionProtocol.TYPE_ERROR, CompanionProtocol.errorPayload(CompanionProtocol.ERR_NOT_FOUND, "bad json")))
            return
        }
        if (envelope.type == CompanionProtocol.TYPE_PING) {
            client.send(CompanionProtocol.envelope(CompanionProtocol.TYPE_PONG, null))
            return
        }
        val response = runCatching { onCommand(envelope) }.getOrElse { error ->
            log("companion: command failed ${error.javaClass.simpleName}")
            CompanionProtocol.envelope(
                CompanionProtocol.TYPE_ERROR,
                CompanionProtocol.errorPayload(CompanionProtocol.ERR_INTERNAL, "command failed"),
                id = envelope.id,
            )
        }
        client.send(response)
    }

    // ---------------------------------------------------------------- HTTP

    private fun readRequest(input: InputStream): HttpRequest? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            if (separator > 0) {
                headers[header.substring(0, separator).trim().lowercase()] = header.substring(separator + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val maxBody = if (target.startsWith("/api/v1/profiles/")) {
            CompanionProtocol.MAX_PHOTO_BYTES
        } else {
            CompanionProtocol.MAX_BODY_BYTES
        }
        if (length < 0 || length > maxBody) {
            throw IllegalArgumentException("body too large")
        }
        val body = ByteArray(length)
        readFully(input, body)
        return HttpRequest(parts[0].uppercase(), path, query, headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.US_ASCII.name())
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.write(value)
            if (buffer.size() > 8 * 1024) throw IllegalArgumentException("line too long")
        }
        return buffer.toString(Charsets.US_ASCII.name())
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw EOFException("unexpected end")
            offset += read
        }
    }

    private fun respondJson(output: OutputStream, status: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        respondBytes(output, status, bytes, "application/json; charset=utf-8")
    }

    private fun respondBytes(output: OutputStream, status: Int, bytes: ByteArray, contentType: String) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun healthJson(): JsonObject = buildJsonObject {
        put("ok", true)
        put("service", "enigmacast-companion")
        put("api", "v1")
        put("tvId", tvId)
        put("name", tvName)
        put("pairing", pairing.current() != null)
    }

    private fun errorJson(code: String, message: String): JsonObject = buildJsonObject {
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                put("retryable", false)
            },
        )
    }

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.content.orEmpty()

    private fun kotlinx.serialization.json.JsonObjectBuilder.putStrings(name: String, values: List<String>) {
        putJsonArray(name) { values.forEach { add(it) } }
    }

    private fun validPublicKey(base64: String): Boolean = runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(bytes))
        true
    }.getOrDefault(false)

    private fun allowPairAttempt(address: String): Boolean {
        val now = System.currentTimeMillis()
        val attempts = pairingAttempts.getOrPut(address) { mutableListOf() }
        synchronized(attempts) {
            attempts.removeIf { now - it > 5 * 60_000L }
            return attempts.size < 5
        }
    }

    private fun registerPairFailure(address: String) {
        val attempts = pairingAttempts.getOrPut(address) { mutableListOf() }
        synchronized(attempts) { attempts += System.currentTimeMillis() }
    }

    private fun devicesTouch(id: String) {
        scope.launch { runCatching { devices.touch(id) } }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val signedPath: String get() = if (query.isEmpty()) path else "$path?$query"
    }

    private class Client(
        val deviceId: String,
        private val socket: Socket,
        private val output: OutputStream,
    ) {
        private val sequence = AtomicLong(1)

        fun nextSeq(): Long = sequence.incrementAndGet()

        fun send(envelope: CompanionEnvelope, seq: Long? = null) {
            val value = if (seq == null) envelope else envelope.copy(seq = seq)
            CompanionWebSocket.writeText(output, CompanionProtocol.encode(value))
        }

        fun close() {
            runCatching { CompanionWebSocket.writeClose(output) }
            runCatching { socket.close() }
        }
    }
}

data class PhotoRequest(
    val profileId: String,
    val bytes: ByteArray,
    val x: Double?,
    val y: Double?,
    val side: Double?,
)

sealed interface PhotoResult {
    data class Saved(val stamp: Long) : PhotoResult
    data class Rejected(val code: String) : PhotoResult
}
