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
            val built = baseBuilder(timeoutSeconds.coerceIn(1, 60).toLong())
                .addInterceptor(RedactingLogInterceptor())
                .apply { if (allowInsecureCertificates) trustInsecureCertificates() }
                .build()
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
            val created = OneEnigmaStream(streamClient())
            oneStream = created
            return created
        }
    }

    /** Drops the open Enigma2 stream socket so the receiver frees its tuner. */
    fun cancelStream() {
        oneStream?.cancel()
    }

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

private class RedactingLogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val url = EnigmaLog.sanitizeUrl(request.url.toString())
        if (com.enigmacast.util.DebugFlags.verbose) {
            EnigmaLog.network("${request.method} $url")
        }
        return try {
            val response = chain.proceed(request)
            if (!response.isSuccessful) {
                EnigmaLog.networkError("${response.code} $url")
            }
            response
        } catch (error: Exception) {
            EnigmaLog.networkError("failed $url ${error.javaClass.simpleName}")
            throw error
        }
    }
}
