package com.enigmacast.data.enigmasync

import android.util.Base64
import com.enigmacast.data.security.SecretStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class SyncServerConfig(
    val host: String,
    val pin: String,
    val token: String,
    val deviceId: String,
    val role: String,
)

/** What port 8740 on a receiver answered without logging in. [version] is null before 0.3.1. */
data class SyncServerProbe(val host: String, val pin: String, val version: String?)

/** HTTPS client. Requests are refused when the certificate pin is missing. */
@Singleton
class EnigmaSyncClient @Inject constructor(
    private val identity: DeviceIdentity,
    private val secrets: SecretStore,
) {
    fun config(): SyncServerConfig? {
        val host = secrets.read(HOST).orEmpty()
        val pin = secrets.read(PIN).orEmpty()
        val token = secrets.read(TOKEN).orEmpty()
        if (host.isBlank() || pin.isBlank() || token.isBlank()) return null
        return SyncServerConfig(host, pin, token, secrets.read(DEVICE).orEmpty(), secrets.read(ROLE).orEmpty())
    }

    fun save(host: String, pin: String, token: String, deviceId: String, role: String) {
        secrets.write(HOST, host)
        secrets.write(PIN, pin)
        secrets.write(TOKEN, token)
        secrets.write(DEVICE, deviceId)
        secrets.write(ROLE, role)
    }

    fun clear() {
        listOf(HOST, PIN, TOKEN, DEVICE, ROLE).forEach { secrets.write(it, null) }
    }

    fun request(config: SyncServerConfig, method: String, path: String, body: ByteArray = ByteArray(0)): String =
        call(config, method, path, body) { response -> response.body.string() }

    /**
     * GET whose body is read as it arrives, so a large pull page can be applied change by change.
     * Closing the reader releases the connection. A non-2xx status throws before any body is read.
     */
    fun openStream(config: SyncServerConfig, path: String): java.io.Reader =
        call(config, "GET", path, ByteArray(0), keepOpen = true) { response -> response.body.charStream() }

    private fun <T> call(
        config: SyncServerConfig,
        method: String,
        path: String,
        body: ByteArray,
        keepOpen: Boolean = false,
        read: (okhttp3.Response) -> T,
    ): T {
        if (config.pin.isBlank()) error("sync server certificate is not pinned")
        val stamp = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(body)
        val message = "$stamp\n$nonce\n${method.uppercase()}\n$path\n".toByteArray() + hash
        val signature = Base64.encodeToString(identity.sign(message), Base64.NO_WRAP)
        val pinned = pinnedClient(config.host, config.pin)
        // A pull page may be large on a slow receiver: more time in total, still bounded.
        val client = if (keepOpen) streamClient(config.host, pinned) else pinned
        val builder = Request.Builder()
            .url("https://${config.host}:8740$path")
            .header("Authorization", "Bearer ${config.token}")
            .header("X-Timestamp", stamp)
            .header("X-Nonce", nonce)
            .header("X-Signature", signature)
            .header("Connection", "close")
        val request = if (method == "GET") builder.get().build() else builder.post(body.toRequestBody(JSON)).build()
        val response = client.newCall(request).execute()
        try {
            noteCipher(config.host, response)
            if (!response.isSuccessful) error("sync ${response.code}")
            val result = read(response)
            if (!keepOpen) response.close()
            return result
        } catch (error: Throwable) {
            response.close()
            throw error
        }
    }

    /**
     * `version` from the pinned, unauthenticated /api/v1/health. Servers before 0.3.1 answer
     * without it (null); a failure to connect throws.
     */
    fun serverVersion(config: SyncServerConfig): String? {
        val client = pinnedClient(config.host, config.pin)
        val request = Request.Builder().url("https://${config.host}:8740/api/v1/health").header("Connection", "close").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("sync ${response.code}")
            return org.json.JSONObject(response.body.string()).optString("version").ifBlank { null }
        }
    }

    /** Pin already stored for this receiver, if this device was installed or claimed before. */
    fun storedPin(host: String): String? {
        val pin = secrets.read(PIN).orEmpty()
        if (pin.isBlank() || secrets.read(HOST) != host) return null
        return pin
    }

    /**
     * Revokes every device and returns one new pairing code. Does not create a second admin;
     * the caller must pair immediately with [pair].
     */
    fun recover(host: String, pin: String, code: String): String =
        postPinned(host, pin, "/api/v1/recover", """{"code":${json(code)}}""".toByteArray())

    /**
     * Reads the pin from the certificate the sync server presents on port 8740.
     * Takeover uses this so the TV does not need SSH, which often times out on Android.
     */
    fun capturePin(host: String, connectSeconds: Long = 8, readSeconds: Long = 8): String =
        probe(host, connectSeconds, readSeconds).pin

    /**
     * Unauthenticated look at port 8740: the certificate pin (trust on first use, like pairing
     * from the setup wizard) and the version from /api/v1/health. Servers before 0.3.1 answer
     * without a version. Throws when nothing answers or it is not an EnigmaCast sync server.
     */
    fun probe(host: String, connectSeconds: Long = 8, readSeconds: Long = 8): SyncServerProbe {
        var captured: X509Certificate? = null
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                captured = chain?.firstOrNull()
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(trust), SecureRandom())
        val http = OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(connectSeconds, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .callTimeout(connectSeconds + readSeconds + 2, TimeUnit.SECONDS)
            .build()
        val body = try {
            http.newCall(Request.Builder().url("https://$host:8740/api/v1/health").header("Connection", "close").get().build()).execute().use { response ->
                if (!response.isSuccessful) error("Syncserver antwoordde met ${response.code}")
                response.body.string()
            }
        } catch (error: Exception) {
            val reason = error.message.orEmpty()
            if (reason.contains("timeout", true) || reason.contains("failed to connect", true) || reason.contains("socket", true)) {
                error("De tv kan $host poort 8740 niet bereiken. Staat de tv op hetzelfde netwerk als de ontvanger?")
            }
            throw error
        }
        val json = runCatching { org.json.JSONObject(body) }.getOrNull()
        val service = json?.optString("service").orEmpty()
        if (json != null && service.isNotEmpty() && service != "enigmacast-sync") error("$host:8740 is geen EnigmaCast-syncserver")
        val cert = captured ?: error("Geen certificaat ontvangen van $host")
        val pin = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded), Base64.NO_WRAP)
        return SyncServerProbe(host, pin, json?.optString("version")?.ifBlank { null })
    }

    /** A TV with the app joins the server it found. No pairing code. */
    fun join(host: String, pin: String, name: String): String {
        val body = """{"name":${json(name)},"publicKey":${json(Base64.encodeToString(identity.publicKey(), Base64.NO_WRAP))}}""".toByteArray()
        return postPinned(host, pin, "/api/v1/join", body)
    }

    /** Pairing is the one call that has no device signature yet. The pin is still required. */
    fun pair(host: String, pin: String, code: String, name: String): String {
        val body = """{"code":${json(code)},"name":${json(name)},"publicKey":${json(Base64.encodeToString(identity.publicKey(), Base64.NO_WRAP))}}""".toByteArray()
        return postPinned(host, pin, "/api/v1/pair", body)
    }

    private fun postPinned(host: String, pin: String, path: String, body: ByteArray): String {
        if (pin.isBlank()) error("sync server certificate is not pinned")
        val client = pinnedClient(host, pin)
        val request = Request.Builder().url("https://$host:8740$path").post(body.toRequestBody(JSON)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) error(text.ifBlank { "sync ${response.code}" })
            return text
        }
    }

    /**
     * The receiver certificate is self-signed for CN=enigmacast-sync, while the app dials an IP.
     * Trust is the SPKI pin only: a different key is rejected, and the hostname must be the one we pinned.
     */
    private fun pinnedClient(host: String, pin: String): OkHttpClient {
        val chacha = SyncTls.prefersChaCha(host)
        val key = "$host|$pin|$chacha"
        synchronized(clients) {
            clients[key]?.let { return it }
            // A receiver's address, pin or cipher preference changed: drop the old clients for it.
            clients.keys.filter { it.startsWith("$host|") }.forEach { clients.remove(it) }
            return buildPinnedClient(host, pin, chacha).also { clients[key] = it }
        }
    }

    /** One client (thread pool, TLS context) per receiver, pin and cipher choice, instead of one per request. */
    private val clients = HashMap<String, OkHttpClient>()

    /** Pull streams: same pool and pin, a longer bound for the whole page. */
    private val streams = HashMap<OkHttpClient, OkHttpClient>()

    private fun streamClient(host: String, pinned: OkHttpClient): OkHttpClient = synchronized(clients) {
        streams.getOrPut(pinned) {
            streams.keys.removeAll { it !in clients.values }
            pinned.newBuilder().callTimeout(STREAM_CALL_SECONDS, TimeUnit.SECONDS).build()
        }
    }

    /**
     * Drops the cached clients of [host] and its cipher preference: after a sync server update the
     * next call starts with a fresh TLS context (the pin itself is kept; the update keeps cert.pem).
     */
    fun forget(host: String) {
        synchronized(clients) {
            clients.keys.filter { it.startsWith("$host|") }.forEach { key ->
                clients.remove(key)?.let { old ->
                    streams.remove(old)
                    old.connectionPool.evictAll()
                }
            }
        }
        SyncTls.forget(host)
    }

    private fun buildPinnedClient(host: String, pin: String, chacha: Boolean): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: error("sync server presented no certificate")
                val actual = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded), Base64.NO_WRAP)
                if (actual != pin) error("sync server certificate does not match the saved pin")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf(trust), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier { hostname, _ -> hostname == host }
            .connectionSpecs(SyncTls.specs(chacha))
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_SECONDS, TimeUnit.SECONDS)
            // Bounds the whole call (connect, TLS, request, body): nothing waits for minutes in silence.
            .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /** Remembers whether [host] negotiated ChaCha20-Poly1305; logs only the suite name, once per change. */
    private fun noteCipher(host: String, response: okhttp3.Response) {
        val suite = response.handshake?.cipherSuite?.javaName ?: return
        if (SyncTls.record(host, suite)) com.enigmacast.util.EnigmaLog.service("sync: tls $suite")
    }

    private fun json(value: String) = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val HOST = "enigmasync.host"
        const val PIN = "enigmasync.pin"
        const val TOKEN = "enigmasync.token"
        const val DEVICE = "enigmasync.device"
        const val ROLE = "enigmasync.role"
        const val CONNECT_SECONDS = 8L
        const val READ_SECONDS = 30L
        const val CALL_SECONDS = 45L
        /** A pull page of up to 500 changes from a slow receiver. */
        const val STREAM_CALL_SECONDS = 120L
    }
}
