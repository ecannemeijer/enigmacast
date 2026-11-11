package com.enigmacast.data.xtream

import com.enigmacast.domain.xtream.IptvHeaders
import com.enigmacast.domain.xtream.IptvRequestHeaders
import com.enigmacast.domain.xtream.XtreamAccount
import com.enigmacast.domain.xtream.XtreamItem
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves the HTTP user-agent and headers for a provider and, when playing, a stream. */
@Singleton
class IptvHeaderResolver @Inject constructor(
    private val store: IptvPlaylistStore,
) {
    fun resolve(provider: IptvProvider?, item: XtreamItem? = null): IptvRequestHeaders {
        val stream = IptvHeaders.parseLines(item?.httpHeaders.orEmpty())
        return IptvHeaders.resolve(
            defaultUserAgent = XtreamRepository.USER_AGENT,
            providerUserAgent = provider?.userAgent.orEmpty(),
            providerHeaders = IptvHeaders.parseLines(provider?.httpHeaders.orEmpty()),
            streamUserAgent = IptvHeaders.userAgentOf(stream),
            streamHeaders = IptvHeaders.withoutUserAgent(stream),
        )
    }

    fun forProviderId(providerId: String, item: XtreamItem? = null): IptvRequestHeaders {
        val provider = store.providers.value.firstOrNull { it.id == providerId }
        return resolve(provider, item)
    }

    fun forAccount(account: XtreamAccount): IptvRequestHeaders {
        val provider = store.providers.value.firstOrNull { candidate ->
            val other = candidate.account() ?: return@firstOrNull false
            other.server == account.server && other.username == account.username
        }
        return resolve(provider)
    }

    fun forItem(item: XtreamItem): IptvRequestHeaders = forProviderId(item.providerId, item)
}
