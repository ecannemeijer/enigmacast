package com.enigmacast.data.network

import com.enigmacast.util.EnigmaLog
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@javax.inject.Singleton
class OkHttpClientFactory @javax.inject.Inject constructor() {
    @Volatile
    private var webifClient: OkHttpClient? = null

    @Volatile
    private var webifAllowsInsecure: Boolean = false

    @Volatile
    private var streamClient: OkHttpClient? = null

    @Volatile
    private var oneStream: OneEnigmaStream? = null

    fun webifClient(allowInsecureCertificates: Boolean, timeoutSeconds: Int): OkHttpClient {
        val existing = webifClient
        if (existing != null && webifAllowsInsecure == allowInsecureCertificates) {
            return existing
        }
        synchronized(this) {
            val again = webifClient
            if (again != null && webifAllowsInsecure == allowInsecureCertificates) return again
            if (allowInsecureCertificates) {
                EnigmaLog.connection("Insecure certificates enabled for the configured receiver")
            }
            var poolRef: okhttp3.ConnectionPool? = null
            val built = baseBuilder(timeoutSeconds.coerceIn(1, 60).toLong())
                .addInterceptor(RedactingLogInterceptor { poolRef })
                .apply { if (allowInsecureCertificates) trustInsecureCertificates() }
                .build()
            poolRef = built.connectionPool
            webifClient = built
            webifAllowsInsecure = allowInsecureCertificates
            return built
        }
    }

    fun streamClient(): OkHttpClient {
        streamClient?.let { return it }
        synchronized(this) {
            streamClient?.let { return it }
            val built = baseBuilder(readTimeoutSeconds = 0)
                .retryOnConnectionFailure(true)
                .build()
            streamClient = built
            return built
        }
    }

    /** Stream requests for the player. A new Enigma2 stream closes the previous one. */
    fun streamCalls(): Call.Factory {
        oneStream?.let { return it }
        synchronized(this) {
            oneStream?.let { return it }
            // A pulled cable leaves the open live socket with no bytes. Eight seconds of
            // silence ends that read, so playback can retry and then give the channel up.
            val live = streamClient().newBuilder().readTimeout(8, TimeUnit.SECONDS).build()
            val created = OneEnigmaStream(live)
            oneStream = created
            return created
        }
    }

    /** Drops the open Enigma2 stream socket so the receiver frees its tuner. */
    fun cancelStream() {
        oneStream?.cancel()
    }

    /** Closes the stream and ignores new Enigma2 stream calls until [releaseStream]. */
    fun holdStreamClosed(): Int = streamCalls().let { (oneStream ?: return 0).holdClosed() }

    /** Lets the next Enigma2 stream open when [ticket] is still the active hold. */
    fun releaseStream(ticket: Int): Boolean = oneStream?.release(ticket) ?: true

    fun streamHoldTicket(): Int = oneStream?.currentTicket() ?: 0

    private fun baseBuilder(readTimeoutSeconds: Long): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
    }

    private fun OkHttpClient.Builder.trustInsecureCertificates() {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        sslSocketFactory(context.socketFactory, trustManager)
        hostnameVerifier { _, _ -> true }
    }
}

/**
 * Logs OpenWebif calls, caps how many run at once per receiver and retries a failed GET once.
 *
 * OpenWebif serves few requests in parallel and a receiver (or a network switch to another box)
 * leaves pooled keep-alive sockets dead: the next call on one fails within milliseconds with an
 * IOException. A GET is idempotent, so it is tried once more on a fresh connection.
 */
private class RedactingLogInterceptor(private val pool: () -> okhttp3.ConnectionPool?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val url = EnigmaLog.sanitizeUrl(request.url.toString())
        if (com.enigmacast.util.DebugFlags.verbose) {
            EnigmaLog.network("${request.method} $url")
        }
        val gate = WebifGates.forHost(request.url.host)
        gate.acquire()
        try {
            return try {
                proceedLogged(chain, request, url)
            } catch (error: java.io.IOException) {
                if (chain.call().isCanceled()) {
                    EnigmaLog.networkError("cancelled $url")
                    throw error
                }
                if (request.method != "GET") throw error
                // A stale pooled socket: drop idle connections and try once on a new one.
                pool()?.evictAll()
                EnigmaLog.network("retry on a fresh connection: $url")
                proceedLogged(chain, request.newBuilder().header("Connection", "close").build(), url)
            }
        } finally {
            gate.release()
        }
    }

    private fun proceedLogged(chain: Interceptor.Chain, request: okhttp3.Request, url: String): okhttp3.Response {
        val started = System.nanoTime()
        try {
            val response = chain.proceed(request)
            if (!response.isSuccessful) {
                EnigmaLog.networkError("${response.code} $url")
            }
            return response
        } catch (error: Exception) {
            val ms = (System.nanoTime() - started) / 1_000_000
            val cause = error.cause?.let { " cause=${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
            EnigmaLog.networkError("failed $url after ${ms}ms ${error.javaClass.simpleName}: ${error.message}$cause")
            throw error
        }
    }
}

/** At most [PER_HOST] OpenWebif calls at once per receiver; the rest wait their turn. */
internal object WebifGates {
    const val PER_HOST = 4
    private val gates = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Semaphore>()

    fun forHost(host: String): java.util.concurrent.Semaphore =
        gates.getOrPut(host.lowercase()) { java.util.concurrent.Semaphore(PER_HOST, true) }
}
