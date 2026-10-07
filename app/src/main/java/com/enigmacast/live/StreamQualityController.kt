package com.enigmacast.live

import android.os.SystemClock
import com.enigmacast.R
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.network.QualityInputs
import com.enigmacast.domain.network.QualityReason
import com.enigmacast.domain.network.QualitySession
import com.enigmacast.domain.network.ReceiverRoute
import com.enigmacast.domain.network.StreamLevel
import com.enigmacast.domain.network.StreamProfile
import com.enigmacast.domain.network.StreamQualityPolicy
import com.enigmacast.domain.tuner.StreamHealth
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.playback.StreamStats
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
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

/** The quality in use, for the stream info and the quick options bar. */
data class QualityState(
    val level: StreamLevel = StreamLevel.DIRECT,
    val ceiling: StreamLevel = StreamLevel.DIRECT,
    val profile: StreamProfile = StreamProfile.AUTO,
    val transcodingSupported: Boolean? = null,
    val manual: StreamLevel? = null,
    val reason: QualityReason = QualityReason.START,
    /** A transcoded start failed in this session; transcoded choices play Normal until the settings change. */
    val transcoderFailed: Boolean = false,
)

/** A request to open the channel again with another quality; [manual] when the user chose it. */
data class QualityRestart(val manual: Boolean)

/**
 * Picks direct or transcoded streams ([QualitySession], [StreamQualityPolicy]) and adapts
 * while watching away from home: repeated stalls step the quality down, a long stable
 * stretch steps it up, and a transcoded stream that never starts falls back to the original
 * stream for the rest of the session (once). A change asks the player to restart ([restarts]).
 *
 * Transcoding support comes from OpenWebif's `/api/about` (`transcoding`), read once per
 * receiver and session, and only for Automatic away from home, so a home stream never waits
 * for it. Transcoded is the user's explicit choice and is tried without asking.
 */
@Singleton
class StreamQualityController @Inject constructor(
    private val api: OpenWebifApi,
    private val playback: LivePlaybackController,
) {
    /** What [choose] decided. */
    data class Choice(val level: StreamLevel, val transcodingSupported: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(QualityState())
    val state: StateFlow<QualityState> = _state.asStateFlow()
    private val _restarts = MutableSharedFlow<QualityRestart>(extraBufferCapacity = 1)

    /** The stream should be opened again with a new quality. */
    val restarts: SharedFlow<QualityRestart> = _restarts.asSharedFlow()
    private val _notices = MutableSharedFlow<UiText>(extraBufferCapacity = 2)

    /** Messages for the screen, such as "transcoded stream unavailable, switched to Normal". */
    val notices: SharedFlow<UiText> = _notices.asSharedFlow()

    private val capabilities = mutableMapOf<String, Boolean>()
    private val session = QualitySession()
    private val stallTimes = ArrayDeque<Long>()
    private var lastStalls = 0
    private var stableSinceMs = now()

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
        val remote = route != ReceiverRoute.HOME
        // Only Automatic away from home depends on what /api/about says; Transcoded is forced.
        val askReceiver = config.streamProfile == StreamProfile.AUTO && remote && !session.transcoderFailed(key)
        val base = QualityInputs(
            profile = config.streamProfile,
            preset = config.transcodePreset,
            transcodingSupported = askReceiver && transcodingReported(config),
            remote = remote,
        )
        val previous = session.inputs
        val chosen = session.choose(key, base)
        if (session.inputs != previous) {
            stallTimes.clear()
            stableSinceMs = now()
        }
        publish(chosen)
        return Choice(chosen, chosen.transcoded)
    }

    /** Quality picked in the quick options bar for this session; null goes back to the setting. */
    fun setManual(choice: StreamLevel?) {
        session.setManual(choice)
        EnigmaLog.service("quality manual ${choice?.name ?: "off"}")
        _restarts.tryEmit(QualityRestart(manual = true))
    }

    /** The stream type, preset or port changed in the settings: give the transcoder a new chance. */
    fun settingsChanged() {
        session.forgetFailures()
        _state.value = _state.value.copy(transcoderFailed = false)
    }

    private fun onStats(stats: StreamStats) {
        if (stats.stalls < lastStalls) lastStalls = 0
        if (stats.stalls == lastStalls) return
        val now = now()
        repeat(stats.stalls - lastStalls) { stallTimes.addLast(now) }
        lastStalls = stats.stalls
        stableSinceMs = now
        while (stallTimes.isNotEmpty() && now - stallTimes.first() > StreamQualityPolicy.STALL_WINDOW_MS) stallTimes.removeFirst()
        session.afterStalls(stallTimes.toList(), now)?.let { changed(it) }
    }

    private fun onHealth(health: StreamHealth) {
        when (health) {
            StreamHealth.PLAYING -> session.onPlaying()
            StreamHealth.NO_TUNER, StreamHealth.UNREACHABLE, StreamHealth.FAILED, StreamHealth.NO_PICTURE -> {
                // A transcoder that never delivered (port closed, busy encoder): use the original stream, once.
                if (!session.onStartFailed()) return
                publish(StreamLevel.DIRECT)
                EnigmaLog.service("transcoded stream failed (${health.name.lowercase()}), switched to the normal stream")
                _notices.tryEmit(UiText.res(R.string.quality_transcode_unavailable))
                _restarts.tryEmit(QualityRestart(manual = false))
            }
            else -> Unit
        }
    }

    private fun checkStable() {
        if (playback.stats.value.bitrateKbps <= 0) {
            stableSinceMs = now()
            return
        }
        session.afterStable(now() - stableSinceMs)?.let { changed(it) }
    }

    private fun changed(next: StreamLevel) {
        stallTimes.clear()
        stableSinceMs = now()
        publish(next)
        EnigmaLog.service("quality ${next.name.lowercase()} (${session.reason.name.lowercase()})")
        _restarts.tryEmit(QualityRestart(manual = false))
    }

    private fun publish(chosen: StreamLevel) {
        val current = session.inputs ?: return
        _state.value = QualityState(
            level = chosen,
            ceiling = session.ceiling(),
            profile = current.profile,
            transcodingSupported = current.transcodingSupported,
            manual = session.manual,
            reason = session.reason,
            transcoderFailed = current.transcoderFailed,
        )
    }

    private suspend fun transcodingReported(config: ReceiverConfig): Boolean {
        val key = receiverKey(config)
        capabilities[key]?.let { return it }
        return try {
            api.about(config).transcoding.also { capabilities[key] = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            EnigmaLog.service("transcoding check failed: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun receiverKey(config: ReceiverConfig) = "${config.id}|${config.host}:${config.httpPort}"

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val STABLE_CHECK_MS = 30_000L
    }
}
