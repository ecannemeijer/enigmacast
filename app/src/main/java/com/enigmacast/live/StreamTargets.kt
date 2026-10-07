package com.enigmacast.live

import com.enigmacast.data.network.ReceiverRouter
import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.network.ReceiverRoute
import com.enigmacast.domain.network.ReceiverRouting
import com.enigmacast.domain.network.StreamLevel
import com.enigmacast.domain.network.TranscodeUrls
import com.enigmacast.util.EnigmaLog
import javax.inject.Inject
import javax.inject.Singleton

/** What the player opens for a channel: URL, stream password header, route and quality. */
data class StreamTarget(val url: String, val authorization: String?, val route: ReceiverRoute, val level: StreamLevel)

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
        val effective = ReceiverRouting.effective(config, route)
        val choice = quality.choose(config, route)
        val url = url(endpoints, effective, reference, choice.level)
        val authorization = endpoints.streamAuthorization(effective)
        EnigmaLog.player(
            "stream target route=${route.name.lowercase()} profile=${config.streamProfile.name.lowercase()} " +
                "transcoding=${if (choice.transcodingSupported) "yes" else "no"} level=${choice.level.name.lowercase()} " +
                "auth=${if (authorization == null) "none" else "basic"} url=${EnigmaLog.sanitizeUrl(url)}",
        )
        return StreamTarget(url, authorization, route, choice.level)
    }

    companion object {
        /** The original stream for [StreamLevel.DIRECT], else the transcoded one; [effective] is already routed. */
        fun url(endpoints: OpenWebifEndpoints, effective: ReceiverConfig, reference: String, level: StreamLevel): String {
            val preset = level.preset ?: return endpoints.stream(effective, reference)
            return TranscodeUrls.build(effective.host, effective.transcodingPort, endpoints.encode(reference), preset)
        }
    }
}
