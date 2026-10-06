package com.enigmacast.live

import com.enigmacast.data.network.ReceiverRouter
import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.network.ReceiverRoute
import com.enigmacast.domain.network.ReceiverRouting
import javax.inject.Inject
import javax.inject.Singleton

/** What the player opens for a channel: URL, stream password header and the route used. */
data class StreamTarget(val url: String, val authorization: String?, val route: ReceiverRoute)

/** Builds stream URLs for the current route (home, remote profile or tunnel). */
@Singleton
class StreamTargets @Inject constructor(
    private val router: ReceiverRouter,
    private val endpoints: OpenWebifEndpoints,
) {
    suspend fun target(config: ReceiverConfig, reference: String): StreamTarget {
        val route = router.route(config)
        val effective = ReceiverRouting.effective(config, route)
        return StreamTarget(endpoints.stream(effective, reference), endpoints.streamAuthorization(effective), route)
    }
}
