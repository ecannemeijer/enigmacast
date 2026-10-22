package com.enigmacast.companionbridge

import android.content.Context
import com.enigmacast.R
import com.enigmacast.data.security.SettingsLock
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.MotionQuality
import com.enigmacast.domain.model.OsdSkinId
import com.enigmacast.domain.model.RecordingTarget
import com.enigmacast.domain.model.StartupLive
import com.enigmacast.domain.model.TvFallback
import com.enigmacast.domain.model.VideoCompatibility
import com.enigmacast.domain.model.VideoDecoderMode
import com.enigmacast.domain.security.PinCheck
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Catalogus van de gebruikersinstellingen die de telefoon mag lezen en wijzigen.
 * De TV schrijft via dezelfde [SettingsRepository.update] als zijn eigen UI; profielgebonden
 * waarden (skin, motion, geluiden, startup) verhuizen mee bij een profielwissel.
 */
@Singleton
class CompanionSettingsHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val settingsLock: SettingsLock,
) {
    data class Option(val value: String, val label: String)

    data class Entry(
        val path: String,
        val label: String,
        val description: String,
        val category: String,
        val type: String,
        val value: JsonElement,
        val options: List<Option> = emptyList(),
        val min: Double? = null,
        val max: Double? = null,
        val step: Double? = null,
        val dangerous: Boolean = false,
        val requiresRestart: Boolean = false,
    )

    data class State(val revision: Long, val entries: List<Entry>, val locked: Boolean)

    data class Op(val path: String, val value: JsonElement?)

    sealed interface ApplyResult {
        data object Applied : ApplyResult
        data object Conflict : ApplyResult
        data object Locked : ApplyResult
        data class Rejected(val path: String) : ApplyResult
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()
    private var revision = 0L

    init {
        scope.launch {
            combine(
                settings.settings,
                settingsLock.enabled,
                settingsLock.unlocked,
            ) { current, enabled, unlocked -> Triple(current, enabled, unlocked) }
                .collect { (current, enabled, unlocked) ->
                    revision += 1
                    _state.value = State(revision, entries(current), locked = enabled && !unlocked)
                }
        }
    }

    suspend fun apply(baseRevision: Long, ops: List<Op>, pin: String?): ApplyResult {
        val current = _state.value ?: return ApplyResult.Conflict
        if (baseRevision != current.revision) return ApplyResult.Conflict
        val unknown = ops.firstOrNull { it.path !in SPECS }
        if (unknown != null) return ApplyResult.Rejected(unknown.path)
        val dangerous = ops.any { it.path in DANGEROUS_PATHS }
        if (settingsLock.enabled.value && !settingsLock.unlocked.value && dangerous) {
            if (pin.isNullOrBlank()) return ApplyResult.Locked
            if (settingsLock.unlock(pin) != PinCheck.Ok) return ApplyResult.Locked
        }
        settings.update { start ->
            ops.fold(start) { acc, op -> SPECS.getValue(op.path)(acc, op.value) }
        }
        return ApplyResult.Applied
    }

    private fun entries(settings: AppSettings): List<Entry> {
        val display = context.getString(R.string.companion_cat_display)
        val playback = context.getString(R.string.companion_cat_playback)
        val startup = context.getString(R.string.companion_cat_startup)
        val remote = context.getString(R.string.companion_cat_remote)
        val debug = context.getString(R.string.companion_cat_debug)
        val all = listOf(
            choice(
                "osdSkin", R.string.nav_osd_skins, R.string.companion_setting_skin_desc, display,
                settings.osdSkin.name,
                OsdSkinId.entries.map { Option(it.name, context.getString(it.label)) },
            ),
            choice(
                "motionQuality", R.string.motion_quality, R.string.motion_quality_desc, display,
                settings.motionQuality.name,
                listOf(
                    Option(MotionQuality.PREMIUM.name, context.getString(R.string.motion_premium)),
                    Option(MotionQuality.PERFORMANCE.name, context.getString(R.string.motion_performance)),
                ),
            ),
            bool("navigationSounds", R.string.navigation_sounds, R.string.navigation_sounds_desc, display, settings.navigationSounds),
            number(
                "osd.transparency", R.string.osd_transparency, R.string.osd_transparency_desc, display,
                settings.osd.transparencyPercent, 0.0, 100.0, 1.0,
            ),
            bool("osd.fastPolling", R.string.companion_setting_fast_poll, 0, display, settings.osd.fastPolling),
            number(
                "osd.hideTimeoutMs", R.string.companion_setting_hide_timeout, 0, display,
                settings.osd.hideTimeoutMs.toInt(), 1_000.0, 10_000.0, 500.0,
            ),
            bool("osd.debugBorders", R.string.osd_debug_border, R.string.companion_setting_debug_desc, display, settings.osd.debugBorders),

            bool(
                "playback.subtitlesEnabled", R.string.subtitles, R.string.companion_setting_subtitles_desc, playback,
                settings.playback.subtitlesEnabled,
            ),
            bool("playback.lowLatency", R.string.companion_setting_low_latency, 0, playback, settings.playback.lowLatency),
            bool("playback.hardwareDecoder", R.string.companion_setting_hw_decoder, 0, playback, settings.playback.hardwareDecoder),
            bool("playback.autoReconnect", R.string.auto_reconnect, 0, playback, settings.playback.autoReconnect),
            bool(
                "playback.hdmiPassthrough", R.string.hdmi_passthrough, 0, playback,
                settings.playback.hdmiPassthrough, dangerous = true, requiresRestart = true,
            ),
            number(
                "playback.audioDelayPcmMs", R.string.companion_setting_audio_delay_pcm, 0, playback,
                settings.playback.audioDelayPcmMs, -500.0, 500.0, 10.0,
            ),
            number(
                "playback.audioDelayPassthroughMs", R.string.companion_setting_audio_delay_passthrough, 0, playback,
                settings.playback.audioDelayPassthroughMs, -500.0, 500.0, 10.0,
            ),
            number(
                "playback.subtitleDelayMs", R.string.quick_subtitle_delay, R.string.quick_subtitle_delay_hint, playback,
                settings.playback.subtitleDelayMs, -5_000.0, 5_000.0, 50.0,
            ),
            choice(
                "playback.videoDecoder", R.string.video_decoder, R.string.video_decoder_desc, playback,
                settings.playback.videoDecoder.name,
                listOf(
                    Option(VideoDecoderMode.AUTO.name, context.getString(R.string.companion_opt_auto)),
                    Option(VideoDecoderMode.HARDWARE.name, context.getString(R.string.video_decoder_hardware)),
                    Option(VideoDecoderMode.SOFTWARE.name, context.getString(R.string.video_decoder_software)),
                ),
                dangerous = true, requiresRestart = true,
            ),
            choice(
                "playback.videoCompatibility", R.string.companion_setting_video_compat, 0, playback,
                settings.playback.videoCompatibility.name,
                listOf(
                    Option(VideoCompatibility.AUTO.name, context.getString(R.string.companion_opt_auto)),
                    Option(VideoCompatibility.ON.name, context.getString(R.string.on)),
                    Option(VideoCompatibility.OFF.name, context.getString(R.string.off)),
                ),
                dangerous = true,
            ),
            choice(
                "playback.recordingTarget", R.string.record_target, R.string.record_target_desc, playback,
                settings.playback.recordingTarget.name,
                listOf(
                    Option(RecordingTarget.TV.name, context.getString(R.string.record_target_tv)),
                    Option(RecordingTarget.RECEIVER.name, context.getString(R.string.record_target_receiver)),
                ),
            ),

            choice(
                "startupLive", R.string.startup_live, R.string.startup_live_desc, startup,
                settings.startupLive.name,
                listOf(
                    Option(StartupLive.OFF.name, context.getString(R.string.startup_live_off)),
                    Option(StartupLive.SATELLITE.name, context.getString(R.string.startup_live_satellite)),
                    Option(StartupLive.IPTV.name, context.getString(R.string.startup_live_iptv)),
                ),
            ),
            choice(
                "tvFallback", R.string.tv_fallback, R.string.tv_fallback_desc, startup,
                settings.tvFallback.name,
                listOf(
                    Option(TvFallback.OFF.name, context.getString(R.string.off)),
                    Option(TvFallback.SAT_TO_IPTV.name, context.getString(R.string.tv_fallback_sat_iptv)),
                    Option(TvFallback.IPTV_TO_SAT.name, context.getString(R.string.tv_fallback_iptv_sat)),
                    Option(TvFallback.BOTH.name, context.getString(R.string.tv_fallback_both)),
                ),
            ),

            number(
                "remote.keyRepeatIntervalMs", R.string.companion_setting_key_repeat, 0, remote,
                settings.remote.keyRepeatIntervalMs.toInt(), 50.0, 500.0, 10.0,
            ),
            number(
                "remote.longPressMs", R.string.companion_setting_long_press, 0, remote,
                settings.remote.longPressMs.toInt(), 300.0, 1_000.0, 50.0,
            ),

            bool("debug.overlay", R.string.debug_overlay, 0, debug, settings.debug.overlay, dangerous = true),
            bool("debug.verboseLogging", R.string.verbose_logging, 0, debug, settings.debug.verboseLogging, dangerous = true),
        )
        // Met een Settings-PIN blijven de gevorderde instellingen zichtbaar, maar de TV
        // vraagt de pincode voordat hij ze wijzigt.
        return all
    }

    private fun bool(
        path: String,
        label: Int,
        description: Int,
        category: String,
        value: Boolean,
        dangerous: Boolean = false,
        requiresRestart: Boolean = false,
    ) = Entry(
        path = path,
        label = context.getString(label),
        description = if (description == 0) "" else context.getString(description),
        category = category,
        type = "BOOL",
        value = JsonPrimitive(value),
        dangerous = dangerous,
        requiresRestart = requiresRestart,
    )

    private fun choice(
        path: String,
        label: Int,
        description: Int,
        category: String,
        value: String,
        options: List<Option>,
        dangerous: Boolean = false,
        requiresRestart: Boolean = false,
    ) = Entry(
        path = path,
        label = context.getString(label),
        description = if (description == 0) "" else context.getString(description),
        category = category,
        type = "CHOICE",
        value = JsonPrimitive(value),
        options = options,
        dangerous = dangerous,
        requiresRestart = requiresRestart,
    )

    private fun number(
        path: String,
        label: Int,
        description: Int,
        category: String,
        value: Int,
        min: Double,
        max: Double,
        step: Double,
    ) = Entry(
        path = path,
        label = context.getString(label),
        description = if (description == 0) "" else context.getString(description),
        category = category,
        type = "NUMBER",
        value = JsonPrimitive(value),
        min = min,
        max = max,
        step = step,
    )

    private companion object {
        val DANGEROUS_PATHS = setOf(
            "playback.hdmiPassthrough",
            "playback.videoDecoder",
            "playback.videoCompatibility",
            "debug.overlay",
            "debug.verboseLogging",
        )

        val SPECS: Map<String, (AppSettings, JsonElement?) -> AppSettings> = mapOf(
            "osdSkin" to { s, v ->
                s.copy(osdSkin = runCatching { OsdSkinId.valueOf(v.string(s.osdSkin.name)) }.getOrDefault(s.osdSkin))
            },
            "motionQuality" to { s, v ->
                s.copy(
                    motionQuality = runCatching { MotionQuality.valueOf(v.string(s.motionQuality.name)) }
                        .getOrDefault(s.motionQuality),
                )
            },
            "navigationSounds" to { s, v -> s.copy(navigationSounds = v.bool(s.navigationSounds)) },
            "osd.transparency" to { s, v ->
                s.copy(osd = s.osd.copy(transparencyPercent = v.int(s.osd.transparencyPercent).coerceIn(0, 100)))
            },
            "osd.fastPolling" to { s, v -> s.copy(osd = s.osd.copy(fastPolling = v.bool(s.osd.fastPolling))) },
            "osd.hideTimeoutMs" to { s, v ->
                s.copy(osd = s.osd.copy(hideTimeoutMs = v.int(s.osd.hideTimeoutMs.toInt()).coerceIn(1_000, 10_000).toLong()))
            },
            "osd.debugBorders" to { s, v ->
                s.copy(osd = s.osd.copy(debugBorders = v.bool(s.osd.debugBorders)))
            },
            "playback.subtitlesEnabled" to { s, v ->
                s.copy(playback = s.playback.copy(subtitlesEnabled = v.bool(s.playback.subtitlesEnabled)))
            },
            "playback.lowLatency" to { s, v -> s.copy(playback = s.playback.copy(lowLatency = v.bool(s.playback.lowLatency))) },
            "playback.hardwareDecoder" to { s, v ->
                s.copy(playback = s.playback.copy(hardwareDecoder = v.bool(s.playback.hardwareDecoder)))
            },
            "playback.autoReconnect" to { s, v ->
                s.copy(playback = s.playback.copy(autoReconnect = v.bool(s.playback.autoReconnect)))
            },
            "playback.hdmiPassthrough" to { s, v ->
                s.copy(playback = s.playback.copy(hdmiPassthrough = v.bool(s.playback.hdmiPassthrough)))
            },
            "playback.audioDelayPcmMs" to { s, v ->
                s.copy(playback = s.playback.copy(audioDelayPcmMs = v.int(s.playback.audioDelayPcmMs).coerceIn(-500, 500)))
            },
            "playback.audioDelayPassthroughMs" to { s, v ->
                s.copy(
                    playback = s.playback.copy(
                        audioDelayPassthroughMs = v.int(s.playback.audioDelayPassthroughMs).coerceIn(-500, 500),
                    ),
                )
            },
            "playback.subtitleDelayMs" to { s, v ->
                s.copy(
                    playback = s.playback.copy(
                        subtitleDelayMs = v.int(s.playback.subtitleDelayMs).coerceIn(-5_000, 5_000),
                    ),
                )
            },
            "playback.videoDecoder" to { s, v ->
                s.copy(
                    playback = s.playback.copy(
                        videoDecoder = runCatching { VideoDecoderMode.valueOf(v.string(s.playback.videoDecoder.name)) }
                            .getOrDefault(s.playback.videoDecoder),
                    ),
                )
            },
            "playback.videoCompatibility" to { s, v ->
                s.copy(
                    playback = s.playback.copy(
                        videoCompatibility = runCatching {
                            VideoCompatibility.valueOf(v.string(s.playback.videoCompatibility.name))
                        }.getOrDefault(s.playback.videoCompatibility),
                    ),
                )
            },
            "playback.recordingTarget" to { s, v ->
                s.copy(
                    playback = s.playback.copy(
                        recordingTarget = runCatching {
                            RecordingTarget.valueOf(v.string(s.playback.recordingTarget.name))
                        }.getOrDefault(s.playback.recordingTarget),
                    ),
                )
            },
            "startupLive" to { s, v ->
                s.copy(
                    startupLive = runCatching { StartupLive.valueOf(v.string(s.startupLive.name)) }
                        .getOrDefault(s.startupLive),
                )
            },
            "tvFallback" to { s, v ->
                s.copy(tvFallback = runCatching { TvFallback.valueOf(v.string(s.tvFallback.name)) }.getOrDefault(s.tvFallback))
            },
            "remote.keyRepeatIntervalMs" to { s, v ->
                s.copy(
                    remote = s.remote.copy(
                        keyRepeatIntervalMs = v.int(s.remote.keyRepeatIntervalMs.toInt()).coerceIn(50, 500).toLong(),
                    ),
                )
            },
            "remote.longPressMs" to { s, v ->
                s.copy(
                    remote = s.remote.copy(
                        longPressMs = v.int(s.remote.longPressMs.toInt()).coerceIn(300, 1_000).toLong(),
                    ),
                )
            },
            "debug.overlay" to { s, v -> s.copy(debug = s.debug.copy(overlay = v.bool(s.debug.overlay))) },
            "debug.verboseLogging" to { s, v ->
                s.copy(debug = s.debug.copy(verboseLogging = v.bool(s.debug.verboseLogging)))
            },
        )
    }
}

private fun JsonElement?.string(fallback: String): String =
    (this as? JsonPrimitive)?.contentOrNull ?: fallback

private fun JsonElement?.bool(fallback: Boolean): Boolean =
    (this as? JsonPrimitive)?.booleanOrNull ?: fallback

private fun JsonElement?.int(fallback: Int): Int =
    (this as? JsonPrimitive)?.intOrNull ?: fallback
