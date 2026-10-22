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

    fun request(config: SyncServerConfig, method: String, path: String, body: ByteArray = ByteArray(0)): String {
        if (config.pin.isBlank()) error("sync server certificate is not pinned")
        val stamp = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(body)
        val message = "$stamp\n$nonce\n${method.uppercase()}\n$path\n".toByteArray() + hash
        val signature = Base64.encodeToString(identity.sign(message), Base64.NO_WRAP)
        val client = pinnedClient(config.host, config.pin)
        val builder = Request.Builder()
            .url("https://${config.host}:8740$path")
            .header("Authorization", "Bearer ${config.token}")
            .header("X-Timestamp", stamp)
            .header("X-Nonce", nonce)
            .header("X-Signature", signature)
            .header("Connection", "close")
        val request = if (method == "GET") builder.get().build() else builder.post(body.toRequestBody(JSON)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) error("sync ${response.code}")
            return text
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
    fun capturePin(host: String, connectSeconds: Long = 8, readSeconds: Long = 8): String {
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
            .build()
        try {
            http.newCall(Request.Builder().url("https://$host:8740/api/v1/health").get().build()).execute().use { response ->
                if (!response.isSuccessful) error("Syncserver antwoordde met ${response.code}")
            }
        } catch (error: Exception) {
            val reason = error.message.orEmpty()
            if (reason.contains("timeout", true) || reason.contains("failed to connect", true) || reason.contains("socket", true)) {
                error("De tv kan $host poort 8740 niet bereiken. Staat de tv op hetzelfde netwerk als de ontvanger?")
            }
            throw error
        }
        val cert = captured ?: error("Geen certificaat ontvangen van $host")
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded), Base64.NO_WRAP)
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
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
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
    }
}
