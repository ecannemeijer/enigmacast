package com.enigmacast.data.channel

import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.xtream.IptvPlaylistSync
import com.enigmacast.data.xtream.XtreamRepository
import com.enigmacast.domain.channel.ChannelAlternative
import com.enigmacast.domain.channel.ChannelNameMatcher
import com.enigmacast.domain.channel.ChannelSource
import com.enigmacast.domain.model.IptvSourceMode
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.xtream.XtreamAccount
import com.enigmacast.domain.xtream.XtreamSection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds the same channel in the satellite bouquet and in every available IPTV source. */
@Singleton
class ChannelAlternativesRepository @Inject constructor(
    private val cache: CacheDao,
    private val settings: SettingsRepository,
    private val xtream: XtreamRepository,
    private val playlistSync: IptvPlaylistSync,
) {
    suspend fun find(
        name: String,
        receiverId: String?,
        account: XtreamAccount?,
        playlistMode: Boolean? = null,
    ): List<ChannelAlternative> = withContext(Dispatchers.IO) {
        val current = settings.current()
        val receiver = receiverId ?: current.activeReceiver()?.id
        val fromPlaylist = playlistMode ?: (current.iptvSource == IptvSourceMode.PLAYLIST)
        val out = ArrayList<ChannelAlternative>()

        // Satellite: the cached channel list is complete and cheap to read.
        if (receiver != null) {
            val bouquets = runCatching { cache.bouquets(receiver) }.getOrDefault(emptyList())
                .associate { it.reference to it.name }
            runCatching { cache.channels(receiver) }.getOrDefault(emptyList())
                .asSequence()
                .filter { BouquetChannels.isPlayableChannel(it.reference) }
                .filter { ChannelNameMatcher.matches(name, it.name) }
                .distinctBy { it.reference }
                .forEach { row ->
                    val bouquet = bouquets[row.bouquetReference]?.takeIf { it.isNotBlank() }
                    val orbital = com.enigmacast.domain.osdinfo.ServiceOrigin.orbital(row.reference)
                        ?.label
                    val context = listOfNotNull(bouquet, orbital).joinToString(" · ")
                        .ifBlank { "Satelliet" }
                    out += ChannelAlternative(
                        name = row.name,
                        source = ChannelSource.ENIGMA2,
                        subtitle = "Satelliet",
                        context = context,
                        reference = row.reference,
                    )
                }
        }

        // IPTV: playlist mode reads Room; XStreamity reads its live list from the account.
        val categoryNames = mutableMapOf<String, Map<String, String>>()
        val items = if (fromPlaylist) {
            runCatching {
                playlistSync.categories(XtreamSection.LIVE).associate { it.id to it.name }
            }.getOrNull()?.let { categoryNames[""] = it }
            runCatching { playlistSync.liveItems() }.getOrDefault(emptyList()).map { it to null }
        } else {
            val accounts = if (account != null) {
                listOf(account)
            } else if (receiver != null) {
                runCatching {
                    current.activeReceiver()?.let { xtream.detect(it) }?.accounts.orEmpty()
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            accounts.forEach { login ->
                runCatching { xtream.categories(login, XtreamSection.LIVE) }.getOrNull()
                    ?.let { categories -> categoryNames[login.key] = categories.associate { it.id to it.name } }
            }
            accounts.flatMap { login ->
                runCatching { xtream.liveStreams(login) }.getOrDefault(emptyList()).map { it to login }
            }
        }
        items.asSequence()
            .filter { (item, _) -> item.section == XtreamSection.LIVE }
            .filter { (item, _) -> ChannelNameMatcher.matches(name, item.name) }
            .distinctBy { (item, login) -> "${login?.key}:${item.id}" }
            .forEach { (item, login) ->
                val category = categoryNames[login?.key.orEmpty()]?.get(item.categoryId)
                val provider = item.providerName.takeIf { it.isNotBlank() }
                val context = listOfNotNull(provider, category?.takeIf { it.isNotBlank() })
                    .joinToString(" · ")
                    .ifBlank { "IPTV" }
                out += ChannelAlternative(
                    name = item.name,
                    source = ChannelSource.IPTV,
                    subtitle = provider ?: "IPTV",
                    context = context,
                    reference = "iptv:${item.providerId}:${login?.key.orEmpty()}:${item.id}",
                    item = item,
                    account = login,
                )
            }

        // The source that is playing comes first; the alternatives after it.
        val preferred = if (fromPlaylist || account != null) ChannelSource.IPTV else ChannelSource.ENIGMA2
        out.distinctBy { "${it.source}:${it.reference}" }
            .sortedWith(compareBy({ if (it.source == preferred) 0 else 1 }, { it.name.lowercase() }))
            .take(MAX)
    }

    private companion object {
        const val MAX = 40
    }
}
