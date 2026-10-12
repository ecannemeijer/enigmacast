package com.enigmacast.data.soccer

import com.enigmacast.BuildConfig
import com.enigmacast.domain.soccer.RequestWindow
import com.enigmacast.domain.soccer.SoccerJson
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What a football-data.org request gave. */
sealed interface FdResult {
    data class Ok(val body: String) : FdResult
    data object NoKey : FdResult
    data object InvalidKey : FdResult
    data class RateLimited(val retryAfterSec: Long) : FdResult
    data object NotInPlan : FdResult
    data class Failed(val message: String) : FdResult
}

/**
 * football-data.org v4 with the `X-Auth-Token` header. The key comes from Settings, else from
 * local.properties via BuildConfig.FOOTBALL_DATA_API_KEY. Every request passes one
 * [RequestWindow] (10 per minute); a 429, or a counter at 0, pauses all requests until reset.
 */
@Singleton
class FootballDataHttp @Inject constructor(private val settings: SoccerSettings) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    val window = RequestWindow(max = 10)

    fun apiKey(): String = settings.values.value.apiKey.ifBlank { BuildConfig.FOOTBALL_DATA_API_KEY }.trim()

    suspend fun get(path: String, params: List<Pair<String, String>> = emptyList()): FdResult {
        val key = apiKey()
        if (key.isBlank()) return FdResult.NoKey
        window.acquire()
        return withContext(Dispatchers.IO) {
            val url = "$BASE$path".toHttpUrl().newBuilder().apply { params.forEach { addQueryParameter(it.first, it.second) } }.build()
            try {
                client.newCall(Request.Builder().url(url).header("X-Auth-Token", key).header("Accept", "application/json").build()).execute().use { r ->
                    val body = r.body.string()
                    val left = r.header("X-Requests-Available-Minute")?.trim()?.toIntOrNull()
                    val reset = RequestWindow.retryAfterSec(r.header("X-RequestCounter-Reset"))
                    if (left == 0) window.pauseUntil(System.currentTimeMillis() + reset * 1000)
                    when {
                        r.isSuccessful -> FdResult.Ok(body)
                        r.code == 429 -> {
                            val wait = RequestWindow.retryAfterSec(r.header("Retry-After") ?: r.header("X-RequestCounter-Reset"))
                            window.pauseUntil(System.currentTimeMillis() + wait * 1000)
                            FdResult.RateLimited(wait)
                        }
                        else -> classify(r.code, body).also { EnigmaLog.connection("football-data $path HTTP ${r.code}") }
                    }
                }
            } catch (e: Exception) {
                EnigmaLog.connection("football-data $path failed: ${e.javaClass.simpleName}")
                FdResult.Failed(e.javaClass.simpleName)
            }
        }
    }

    companion object {
        const val BASE = "https://api.football-data.org/v4"

        /** 400 "Your API token is invalid." and 401 → key; 403 "restricted" → not in the plan. */
        fun classify(code: Int, body: String?): FdResult {
            val message = SoccerJson.errorMessage(body)
            return when {
                code == 401 || (code == 400 && message.contains("token", ignoreCase = true)) -> FdResult.InvalidKey
                code == 403 -> FdResult.NotInPlan
                code == 429 -> FdResult.RateLimited(60)
                else -> FdResult.Failed("HTTP $code ${message.take(80)}".trim())
            }
        }
    }
}
