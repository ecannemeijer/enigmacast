package com.enigmacast.live

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.network.QualityInputs
import com.enigmacast.domain.network.ReceiverRoute
import com.enigmacast.domain.network.StreamLevel
import com.enigmacast.domain.network.StreamProfile
import com.enigmacast.domain.network.StreamQualityPolicy
import com.enigmacast.domain.network.TranscodeUrls
import com.enigmacast.domain.tuner.StreamHealth
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.playback.StreamStats
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Why the stream has its current quality. */
enum class QualityReason { START, STALLS, STABLE, MANUAL, FALLBACK }

/** The quality in use, for the stream info and the quick options bar. */
data class QualityState(
    val level: StreamLevel = StreamLevel.DIRECT,
    val ceiling: StreamLevel = StreamLevel.DIRECT,
    val profile: StreamProfile = StreamProfile.AUTO,
    val transcodingSupported: Boolean? = null,
    val manual: StreamLevel? = null,
    val reason: QualityReason = QualityReason.START,
)

/**
 * Picks direct or transcoded streams ([StreamQualityPolicy]) and adapts while watching:
 * repeated stalls step the quality down, a long stable stretch steps it up, and a
 * transcoded stream that never starts falls back to the original stream for the rest of
 * the session. A change asks the player to restart the stream ([restarts]).
 *
 * Transcoding support comes from OpenWebif's `/api/about` (`transcoding`), read once per
 * receiver and session, and only when the profile is not Direct.
 */
@Singleton
class StreamQualityController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: OpenWebifApi,
    private val playback: LivePlaybackController,
) {
    private class Capability(val transcoding: Boolean, val separator: Char)

    /** What [choose] decided: the level and the parameter separator of the receiver's image. */
    data class Choice(val level: StreamLevel, val separator: Char)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(QualityState())
    val state: StateFlow<QualityState> = _state.asStateFlow()
    private val _restarts = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The stream should be opened again with a new quality. */
    val restarts: SharedFlow<Unit> = _restarts.asSharedFlow()

    private val capabilities = mutableMapOf<String, Capability>()
    private val transcoderFailed = mutableSetOf<String>()
    private var inputs: QualityInputs? = null
    private var inputsKey = ""
    private var level = StreamLevel.DIRECT
    private var manual: StreamLevel? = null
    private var reason = QualityReason.START
    private var lastGood: StreamLevel? = null
    private val stallTimes = ArrayDeque<Long>()
    private var lastStalls = 0
    private var stableSinceMs = now()
    private var streamingTranscoded = false
    private var reachedPlaying = false

    init {
        scope.launch { playback.stats.collect(::onStats) }
        scope.launch { playback.health.collect(::onHealth) }
        scope.launch {
            while (true) {
                delay(STABLE_CHECK_MS)
                checkStable()
            }
        }
    }

    suspend fun choose(config: ReceiverConfig, route: ReceiverRoute): Choice {
        val key = receiverKey(config)
        val capability = if (config.streamProfile == StreamProfile.DIRECT) null else capability(config)
        val supported = capability?.transcoding == true && key !in transcoderFailed
        val next = QualityInputs(
            profile = config.streamProfile,
            preset = config.transcodePreset,
            transcodingSupported = supported,
            internet = route != ReceiverRoute.HOME,
            metered = metered(),
        )
        if (next != inputs || key != inputsKey) {
            level = StreamQualityPolicy.initial(next, lastGood)
            if (reason != QualityReason.FALLBACK) reason = QualityReason.START
            stallTimes.clear()
            stableSinceMs = now()
        }
        inputs = next
        inputsKey = key
        val chosen = manual?.let { if (it.transcoded && !supported) StreamLevel.DIRECT else it } ?: level
        streamingTranscoded = chosen.transcoded
        reachedPlaying = false
        publish(chosen)
        return Choice(chosen, capability?.separator ?: '?')
    }

    /** Quality picked in the quick options bar for this session; null goes back to the setting. */
    fun setManual(choice: StreamLevel?) {
        manual = choice
        reason = if (choice == null) QualityReason.START else QualityReason.MANUAL
        EnigmaLog.service("quality manual ${choice?.name ?: "off"}")
        _restarts.tryEmit(Unit)
    }

    private fun onStats(stats: StreamStats) {
        if (stats.stalls < lastStalls) lastStalls = 0
        if (stats.stalls == lastStalls) return
        val now = now()
        repeat(stats.stalls - lastStalls) { stallTimes.addLast(now) }
        lastStalls = stats.stalls
        stableSinceMs = now
        while (stallTimes.isNotEmpty() && now - stallTimes.first() > StreamQualityPolicy.STALL_WINDOW_MS) stallTimes.removeFirst()
        val current = inputs ?: return
        if (manual != null) return
        StreamQualityPolicy.afterStalls(current, level, stallTimes.toList(), now)?.let { change(it, QualityReason.STALLS) }
    }

    private fun onHealth(health: StreamHealth) {
        when (health) {
            StreamHealth.PLAYING -> reachedPlaying = true
            StreamHealth.NO_TUNER, StreamHealth.UNREACHABLE, StreamHealth.FAILED, StreamHealth.NO_PICTURE -> {
                // A transcoder that never delivered (port closed, busy encoder): use the original stream.
                if (!streamingTranscoded || reachedPlaying || inputsKey.isEmpty()) return
                transcoderFailed += inputsKey
                streamingTranscoded = false
                inputs = inputs?.copy(transcodingSupported = false)
                reason = QualityReason.FALLBACK
                EnigmaLog.service("transcoded stream failed (${health.name.lowercase()}), using the original stream")
                _restarts.tryEmit(Unit)
            }
            else -> Unit
        }
    }

    private fun checkStable() {
        val current = inputs ?: return
        if (manual != null) return
        if (playback.stats.value.bitrateKbps <= 0) {
            stableSinceMs = now()
            return
        }
        StreamQualityPolicy.afterStable(current, level, now() - stableSinceMs)?.let { change(it, QualityReason.STABLE) }
    }

    private fun change(next: StreamLevel, why: QualityReason) {
        level = next
        lastGood = next
        reason = why
        stallTimes.clear()
        stableSinceMs = now()
        publish(next)
        EnigmaLog.service("quality ${next.name.lowercase()} (${why.name.lowercase()})")
        _restarts.tryEmit(Unit)
    }

    private fun publish(chosen: StreamLevel) {
        val current = inputs ?: return
        _state.value = QualityState(
            level = chosen,
            ceiling = StreamQualityPolicy.ceiling(current),
            profile = current.profile,
            transcodingSupported = current.transcodingSupported,
            manual = manual,
            reason = reason,
        )
    }

    private suspend fun capability(config: ReceiverConfig): Capability {
        val key = receiverKey(config)
        capabilities[key]?.let { return it }
        return try {
            val about = api.about(config)
            Capability(about.transcoding, TranscodeUrls.separator(about.imageDistro)).also { capabilities[key] = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            EnigmaLog.service("transcoding check failed: ${error.javaClass.simpleName}")
            Capability(false, '?')
        }
    }

    private fun metered(): Boolean =
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true }.getOrDefault(false)

    private fun receiverKey(config: ReceiverConfig) = "${config.id}|${config.host}:${config.httpPort}"

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val STABLE_CHECK_MS = 30_000L
    }
}
