package com.enigmacast.data.oscam

import com.enigmacast.R
import com.enigmacast.data.network.OkHttpClientFactory
import com.enigmacast.data.network.ReceiverRouter
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.oscam.HttpDigest
import com.enigmacast.domain.oscam.OscamLogPage
import com.enigmacast.domain.oscam.OscamServer
import com.enigmacast.domain.oscam.OscamStatus
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.HasUiText
import com.enigmacast.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException

/** Oscam's own web interface. OpenWebif cannot start, stop or restart Oscam. */
class OscamException(
    val kind: Kind,
    override val uiText: UiText,
) : IOException(kind.name), HasUiText {
    enum class Kind { AUTH, UNREACHABLE, HTTP }
}

@Singleton
class OscamClient @Inject constructor(
    private val clients: OkHttpClientFactory,
    private val router: ReceiverRouter,
) {
    private val random = SecureRandom()
    private val lock = Any()
    private var sessionKey: String? = null
    private var session = AuthSession(cnonce())

    suspend fun snapshot(config: ReceiverConfig): OscamServer =
        OscamStatus.snapshot(fetch(config, "/oscamapi.json?part=status"))

    suspend fun log(config: ReceiverConfig, lastId: Long): OscamLogPage =
        OscamStatus.logPage(fetch(config, "/logpoll.html?lastid=$lastId"))

    suspend fun restart(config: ReceiverConfig) {
        fetch(config, "/shutdown.html?action=Restart")
    }

    suspend fun stop(config: ReceiverConfig) {
        fetch(config, "/shutdown.html?action=Shutdown")
    }

    /** Reconnects one reader or proxy. The label is the name Oscam shows on the status page. */
    suspend fun restartReader(config: ReceiverConfig, label: String) {
        fetch(config, "/status.html?action=restart&label=${encode(label)}")
    }

    suspend fun resetReaderStats(config: ReceiverConfig) {
        fetch(config, "/status.html?action=resetreaderstats")
    }

    suspend fun resetUserStats(config: ReceiverConfig) {
        fetch(config, "/status.html?action=resetuserstats")
    }

    /** Oscam debug bitmask. 0 is the normal log; 65535 logs everything. */
    suspend fun setDebug(config: ReceiverConfig, level: Int) {
        fetch(config, "/status.html?debug=$level")
    }

    private suspend fun fetch(config: ReceiverConfig, path: String): String {
        val effective = router.effective(config)
        val url = base(effective) + path
        val uri = requestUri(url)
        val auth = sessionFor(effective)
        var authorization = auth.header(effective, uri)
        repeat(2) { attempt ->
            val response = try {
                execute(effective, url, authorization)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: IOException) {
                throw OscamException(OscamException.Kind.UNREACHABLE, UiText.res(R.string.oscam_unreachable))
            }
            if (response.code == 401 && attempt == 0 && auth.challengeFrom(response.authenticate, effective)) {
                authorization = auth.header(effective, uri)
                return@repeat
            }
            if (response.code == 401) {
                throw OscamException(OscamException.Kind.AUTH, UiText.res(R.string.oscam_auth_failed))
            }
            if (response.code !in 200..299) {
                throw OscamException(OscamException.Kind.HTTP, UiText.res(R.string.oscam_request_failed))
            }
            return response.body
        }
        throw OscamException(OscamException.Kind.AUTH, UiText.res(R.string.oscam_auth_failed))
    }

    private fun base(config: ReceiverConfig): String {
        val host = httpHost(config.host)
        val port = config.oscamPort.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        return "http://$host:$port"
    }

    private fun sessionFor(config: ReceiverConfig): AuthSession {
        val key = "${config.host}|${config.oscamPort}|${config.oscamUsername}|${config.oscamPassword}"
        synchronized(lock) {
            if (sessionKey != key) {
                sessionKey = key
                session = AuthSession(cnonce())
            }
            return session
        }
    }

    private fun cnonce(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(Locale.US, it) }
    }

    private suspend fun execute(config: ReceiverConfig, url: String, authorization: String?): HttpResult {
        val client = clients.webifClient(config.allowInsecureCertificates, config.timeoutSeconds)
        val builder = Request.Builder().url(url).header("Accept", "application/json, text/plain, */*")
        if (!authorization.isNullOrEmpty()) builder.header("Authorization", authorization)
        EnigmaLog.connection("oscam ${url.substringAfter("://").substringAfter("/")}")
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (call.isCanceled()) return
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { open ->
                            val challenges = open.headers("WWW-Authenticate")
                            Result.success(
                                HttpResult(
                                    code = open.code,
                                    body = open.body.bytes().toString(Charsets.UTF_8),
                                    authenticate = challenges.firstOrNull { it.startsWith("Digest", true) }
                                        ?: challenges.firstOrNull(),
                                ),
                            )
                        }
                    } catch (error: Exception) {
                        Result.failure(error as? IOException ?: IOException(error))
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }

    private class AuthSession(val cnonce: String) {
        private var mode = Mode.NONE
        private var challenge: HttpDigest.Challenge? = null
        private var nc = 0

        fun header(config: ReceiverConfig, uri: String): String? {
            if (config.oscamUsername.isEmpty()) return null
            return when (mode) {
                Mode.NONE -> null
                Mode.BASIC -> Credentials.basic(config.oscamUsername, config.oscamPassword)
                Mode.DIGEST -> {
                    val current = challenge ?: return null
                    nc += 1
                    HttpDigest.authorization(
                        username = config.oscamUsername,
                        password = config.oscamPassword,
                        method = "GET",
                        uri = uri,
                        challenge = current,
                        nc = nc,
                        cnonce = cnonce,
                    )
                }
            }
        }

        fun challengeFrom(header: String?, config: ReceiverConfig): Boolean {
            if (config.oscamUsername.isEmpty()) return false
            val digest = header?.let { HttpDigest.parse(it) }
            if (digest != null) {
                mode = Mode.DIGEST
                challenge = digest
                nc = 0
                return true
            }
            if (header != null && header.startsWith("Basic", ignoreCase = true)) {
                mode = Mode.BASIC
                return true
            }
            return false
        }
    }

    private enum class Mode { NONE, BASIC, DIGEST }

    private data class HttpResult(val code: Int, val body: String, val authenticate: String?)

    private companion object {
        const val DEFAULT_PORT = 8888

        fun httpHost(host: String): String {
            val trimmed = host.trim()
            return if (trimmed.contains(':') && !trimmed.startsWith("[")) "[$trimmed]" else trimmed
        }

        fun requestUri(url: String): String {
            val parsed = url.toHttpUrl()
            val query = parsed.encodedQuery
            return if (query == null) parsed.encodedPath else "${parsed.encodedPath}?$query"
        }

        fun encode(value: String): String =
            java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}
