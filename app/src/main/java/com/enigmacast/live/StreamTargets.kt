package com.enigmacast.live

import com.enigmacast.data.network.ReceiverRouter
import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.network.ReceiverRoute
import com.enigmacast.domain.network.ReceiverRouting
import com.enigmacast.domain.network.StreamLevel
import com.enigmacast.domain.network.TranscodeUrls
import javax.inject.Inject
import javax.inject.Singleton

/** What the player opens for a channel: URL, stream password header, route and quality. */
data class StreamTarget(val url: String, val authorization: String?, val route: ReceiverRoute, val level: StreamLevel)

/**
 * Builds stream URLs for the current route (home, remote profile or tunnel) and quality:
 * the original stream on the stream port, or a transcoded one on the transcoding port.
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
        val preset = choice.level.preset
        val url = if (preset == null) {
            endpoints.stream(effective, reference)
        } else {
            TranscodeUrls.build(effective.host, effective.transcodingPort, endpoints.encode(reference), preset, choice.separator)
        }
        return StreamTarget(url, endpoints.streamAuthorization(effective), route, choice.level)
    }
}
