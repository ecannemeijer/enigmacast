package com.enigmacast.data.soccer

import com.enigmacast.BuildConfig
import com.enigmacast.domain.soccer.RequestWindow
import com.enigmacast.domain.soccer.pitch.PitchJson
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * pitchapi.dev with the `X-API-KEY` header (key from local.properties PITCH_API_KEY via BuildConfig).
 * No daily limit, only a fair-use burst limit: requests are spaced [SPACING_MS] apart and a 429
 * pauses everything for its Retry-After.
 */
@Singleton
class PitchHttp @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gate = Mutex()
    private var lastMs = 0L
    @Volatile private var pausedUntilMs = 0L

    fun apiKey(): String = BuildConfig.PITCH_API_KEY.trim()

    fun pausedMs(now: Long = System.currentTimeMillis()): Long = (pausedUntilMs - now).coerceAtLeast(0)

    suspend fun get(path: String, params: List<Pair<String, String>> = emptyList()): FdResult {
        val key = apiKey()
        if (key.isBlank()) return FdResult.NoKey
        if (pausedMs() > 0) return FdResult.RateLimited(pausedMs() / 1000 + 1)
        gate.withLock {
            val wait = lastMs + SPACING_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastMs = System.currentTimeMillis()
        }
        return withContext(Dispatchers.IO) {
            val url = "$BASE$path".toHttpUrl().newBuilder().apply { params.forEach { addQueryParameter(it.first, it.second) } }.build()
            try {
                client.newCall(Request.Builder().url(url).header("X-API-KEY", key).header("Accept", "application/json").build()).execute().use { r ->
                    val body = r.body.string()
                    when {
                        r.isSuccessful -> FdResult.Ok(body)
                        r.code == 429 -> {
                            val s = RequestWindow.retryAfterSec(r.header("Retry-After"))
                            pausedUntilMs = System.currentTimeMillis() + s * 1000
                            FdResult.RateLimited(s)
                        }
                        r.code == 401 || r.code == 403 -> FdResult.InvalidKey
                        else -> FdResult.Failed("HTTP ${r.code} ${PitchJson.error(body).take(80)}".trim()).also { EnigmaLog.connection("pitchapi $path HTTP ${r.code}") }
                    }
                }
            } catch (e: Exception) {
                EnigmaLog.connection("pitchapi $path failed: ${e.javaClass.simpleName}")
                FdResult.Failed(e.javaClass.simpleName)
            }
        }
    }

    companion object {
        const val BASE = "https://api.pitchapi.dev"
        const val SPACING_MS = 300L
    }
}
