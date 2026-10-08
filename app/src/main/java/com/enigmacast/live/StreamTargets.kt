package com.enigmacast.live

import com.enigmacast.data.network.ReceiverRouter
import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.network.ReceiverRoute
import com.enigmacast.domain.network.ReceiverRouting
import com.enigmacast.domain.network.StreamLevel
import com.enigmacast.domain.network.TranscodePreset
import com.enigmacast.domain.network.TranscodeUrls
import com.enigmacast.domain.service.ServiceStreams
import com.enigmacast.util.EnigmaLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the player opens for a channel: URL, stream password header, route and quality.
 * [internet] is true when the URL is the channel's own HTTP(S) address, not the receiver.
 */
data class StreamTarget(
    val url: String,
    val authorization: String?,
    val route: ReceiverRoute,
    val level: StreamLevel,
    val internet: Boolean = false,
    /** Same transcoded stream on the normal stream port, when that port differs from the encoder port. */
    val fallbackUrl: String? = null,
)

/**
 * Builds stream URLs for the current route (home, remote profile or tunnel) and quality:
 * the original stream on the stream port, or a transcoded one on the transcoding port.
 * Every decision is logged with the URL (credentials masked), so a log shows what was opened.
 */
@Singleton
class StreamTargets @Inject constructor(
    private val router: ReceiverRouter,
    private val endpoints: OpenWebifEndpoints,
    private val quality: StreamQualityController,
) {
    suspend fun target(config: ReceiverConfig, reference: String): StreamTarget {
        val route = router.route(config)
        val internetUrl = ServiceStreams.internetUrl(reference)
        if (internetUrl != null) {
            EnigmaLog.player(
                "stream target kind=internet route=${route.name.lowercase()} " +
                    "auth=none url=${EnigmaLog.sanitizeUrl(internetUrl)}",
            )
            // External URLs are never transcoded and never use the receiver's stream password.
            return StreamTarget(internetUrl, authorization = null, route = route, level = StreamLevel.DIRECT, internet = true)
        }
        if (ServiceStreams.isStreamPlayer(reference)) {
            EnigmaLog.player("stream target kind=stream-player-without-url; falling back to the receiver stream")
        }
        val effective = ReceiverRouting.effective(config, route)
        val choice = quality.choose(config, route)
        val url = url(endpoints, effective, reference, choice.level)
        val authorization = endpoints.streamAuthorization(effective)
        EnigmaLog.player(
            "stream target kind=enigma2 route=${route.name.lowercase()} profile=${config.streamProfile.name.lowercase()} " +
                "transcoding=${if (choice.transcodingSupported) "yes" else "no"} level=${choice.level.name.lowercase()} " +
                "auth=${if (authorization == null) "none" else "basic"} url=${EnigmaLog.sanitizeUrl(url)}",
        )
        return StreamTarget(url, authorization, route, choice.level, internet = false)
    }

    /**
     * The stream a TV recording should save. [preset] null is the original broadcast.
     * An internet channel has no receiver encoder, so it stays that channel's own URL.
     */
    suspend fun recording(config: ReceiverConfig, reference: String, preset: TranscodePreset?): StreamTarget {
        val route = router.route(config)
        val internetUrl = ServiceStreams.internetUrl(reference)
        if (internetUrl != null) {
            return StreamTarget(internetUrl, authorization = null, route = route, level = StreamLevel.DIRECT, internet = true)
        }
        val effective = ReceiverRouting.effective(config, route)
        val level = preset?.let(StreamLevel::of) ?: StreamLevel.DIRECT
        val url = url(endpoints, effective, reference, level)
        val authorization = endpoints.streamAuthorization(effective)
        val fallback = level.preset?.let { chosen ->
            if (effective.transcodingPort == effective.streamingPort) null
            else TranscodeUrls.build(effective.host, effective.streamingPort, endpoints.encode(reference), chosen)
        }
        EnigmaLog.player(
            "recording target route=${route.name.lowercase()} level=${level.name.lowercase()} " +
                "url=${EnigmaLog.sanitizeUrl(url)}" +
                (fallback?.let { " fallback=${EnigmaLog.sanitizeUrl(it)}" } ?: ""),
        )
        return StreamTarget(url, authorization, route, level, internet = false, fallbackUrl = fallback)
    }

    companion object {
        /** The original stream for [StreamLevel.DIRECT], else the transcoded one; [effective] is already routed. */
        fun url(endpoints: OpenWebifEndpoints, effective: ReceiverConfig, reference: String, level: StreamLevel): String {
            val preset = level.preset ?: return endpoints.stream(effective, reference)
            return TranscodeUrls.build(effective.host, effective.transcodingPort, endpoints.encode(reference), preset)
        }
    }
}
