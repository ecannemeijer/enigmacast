package com.enigmacast.data.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** What `/api/about` says about a host. */
sealed interface AboutVerdict {
    data class Receiver(
        val model: String,
        val image: String,
        val loginRequired: Boolean,
    ) : AboutVerdict

    data object NotEnigma2 : AboutVerdict
}

/**
 * Decides from the `/api/about` answer whether a host is an Enigma2 receiver with OpenWebif.
 * OpenWebif answers `{"info": {"brand": …, "model": …, "webifver": …}, "service": {…}}`;
 * older or other images put the keys at the top level. A 401 means OpenWebif wants a login,
 * which still identifies a receiver (printers and routers answer 404 or HTML there).
 */
object AboutClassifier {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val IDENTITY_KEYS = listOf("model", "boxtype", "webifver", "enigmaver", "imagedistro", "friendlyimagedistro", "machinebuild")

    fun classify(code: Int, body: String?): AboutVerdict {
        if (code == 401) return AboutVerdict.Receiver(model = "", image = "", loginRequired = true)
        if (code !in 200..299 || body.isNullOrBlank()) return AboutVerdict.NotEnigma2
        val root = runCatching { json.parseToJsonElement(body.trim()) as? JsonObject }.getOrNull() ?: return AboutVerdict.NotEnigma2
        val info = root["info"] as? JsonObject ?: root
        fun text(key: String): String = ((info[key] ?: root[key]) as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content?.trim().orEmpty()
        val hasIdentity = IDENTITY_KEYS.any { text(it).isNotBlank() } || (root["info"] is JsonObject && text("brand").isNotBlank())
        if (!hasIdentity) return AboutVerdict.NotEnigma2
        val brand = text("brand")
        val model = text("model").ifBlank { text("boxtype").uppercase() }
        val name = when {
            model.isBlank() -> brand
            brand.isBlank() || model.startsWith(brand, ignoreCase = true) -> model
            else -> "$brand $model"
        }
        val image = listOf(text("friendlyimagedistro").ifBlank { text("imagedistro") }, text("imagever")).filter { it.isNotBlank() }.joinToString(" ")
        return AboutVerdict.Receiver(model = name, image = image, loginRequired = false)
    }
}

/**
 * Asks one host `/api/about` with short timeouts and no login, outside the receiver routing
 * (no remote profile, no WireGuard, no shared 8 s client), so a scan never disturbs the
 * configured receiver and a slow device cannot hold it up.
 */
class AboutProbe(
    private val client: OkHttpClient = defaultClient(),
    private val log: (String) -> Unit = {},
) {
    suspend fun probe(host: String, port: Int, https: Boolean): DiscoveredReceiver? {
        val scheme = if (https) "https" else "http"
        val url = "$scheme://$host:$port/api/about"
        val started = System.currentTimeMillis()
        return try {
            val (code, body) = runInterruptible(Dispatchers.IO) {
                client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                    response.code to response.peekBody(MAX_BODY).string()
                }
            }
            val verdict = AboutClassifier.classify(code, body)
            val ms = System.currentTimeMillis() - started
            when (verdict) {
                is AboutVerdict.Receiver -> {
                    log("about $host:$port -> HTTP $code, Enigma2 ${verdict.model.ifBlank { "(login required)" }} in $ms ms")
                    DiscoveredReceiver(host, port, https, verdict.model, verdict.image, verdict.loginRequired)
                }
                AboutVerdict.NotEnigma2 -> {
                    log("about $host:$port -> HTTP $code, not Enigma2 (${body.take(40).replace('\n', ' ')}) in $ms ms")
                    null
                }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            log("about $host:$port failed: ${error.javaClass.simpleName} ${error.message.orEmpty().take(80)}")
            null
        }
    }

    /** A small video grab for the result row. Only when OpenWebif did not ask for a login. */
    suspend fun preview(receiver: DiscoveredReceiver): ByteArray? {
        if (receiver.loginRequired) return null
        val scheme = if (receiver.useHttps) "https" else "http"
        val url = "$scheme://${receiver.host}:${receiver.httpPort}/grab?format=jpg&mode=video"
        return try {
            runInterruptible(Dispatchers.IO) {
                client.newCall(Request.Builder().url(url).header("Accept", "image/jpeg").build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val type = response.header("Content-Type").orEmpty()
                    if (!type.startsWith("image/")) return@use null
                    response.body?.bytes()?.takeIf { it.size > 32 }
                }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }
    }

    companion object {
        private const val MAX_BODY = 512L * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(1_500, TimeUnit.MILLISECONDS)
            .readTimeout(2_500, TimeUnit.MILLISECONDS)
            .callTimeout(4_000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
