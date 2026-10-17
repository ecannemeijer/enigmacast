package com.enigmacast.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.db.CacheMappers
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.xtream.IptvPlaylistStore
import com.enigmacast.data.xtream.IptvPlaylistSync
import com.enigmacast.data.xtream.XtreamRepository
import com.enigmacast.domain.model.IptvSourceMode
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.xtream.XtreamAccount
import com.enigmacast.domain.xtream.XtreamSection
import com.enigmacast.domain.xtream.XtreamUrls
import com.enigmacast.live.StreamTargets
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PipSource { SAT, IPTV }

data class PipChoice(val id: String, val name: String)

/** One channel the inset can play. [serviceRef] is resolved when the inset starts. */
data class PipEntry(
    val name: String,
    val url: String = "",
    val authorization: String? = null,
    val serviceRef: String = "",
    /** Encoder on the stream port, tried when the transcoding port does not answer. */
    val fallbackUrl: String = "",
    /** Original stream, tried when the encoder slot cannot be opened. */
    val directUrl: String = "",
)

/**
 * The small picture. Its player never claims [PlaybackOwner], so the large picture keeps
 * playing and keeps the sound until the two are swapped.
 */
@Singleton
class PipController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val cache: CacheDao,
    private val playlistStore: IptvPlaylistStore,
    private val playlistSync: IptvPlaylistSync,
    private val repository: XtreamRepository,
    private val targets: StreamTargets,
) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private val _swapped = MutableStateFlow(false)
    val swapped: StateFlow<Boolean> = _swapped.asStateFlow()
    private val _listOpen = MutableStateFlow(false)
    val listOpen: StateFlow<Boolean> = _listOpen.asStateFlow()
    private val _names = MutableStateFlow<List<String>>(emptyList())
    val names: StateFlow<List<String>> = _names.asStateFlow()

    private var entries: List<PipEntry> = emptyList()
    private var index = 0
    private var player: ExoPlayer? = null

    @Volatile
    private var authorization: String? = null

    private var sawFrame = false

    fun player(): Player = ensure()

    fun playing(): Boolean = _active.value

    suspend fun categories(source: PipSource): List<PipChoice> = when (source) {
        PipSource.SAT -> {
            val receiver = settings.current().activeReceiver() ?: return emptyList()
            val key = CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)
            cache.bouquets(key)
                .filterNot { BouquetChannels.isRadioBouquet(it.reference) }
                .map { PipChoice(it.reference, it.name) }
        }
        PipSource.IPTV -> iptvCategories()
    }

    suspend fun channels(source: PipSource, categoryId: String): List<PipEntry> = when (source) {
        PipSource.SAT -> {
            val receiver = settings.current().activeReceiver() ?: return emptyList()
            val key = CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)
            cache.channelsIn(key, categoryId).map { PipEntry(name = it.name, serviceRef = it.reference) }
        }
        PipSource.IPTV -> iptvChannels(categoryId)
    }

    fun start(list: List<PipEntry>, at: Int) {
        if (list.isEmpty()) return
        entries = list
        index = at.coerceIn(0, list.lastIndex)
        _names.value = list.map { it.name }
        _swapped.value = false
        _listOpen.value = false
        _active.value = true
        playCurrent()
    }

    fun stop() {
        _active.value = false
        _swapped.value = false
        _listOpen.value = false
        _names.value = emptyList()
        entries = emptyList()
        player?.let { exo ->
            exo.playWhenReady = false
            exo.volume = 0f
            exo.stop()
            exo.clearMediaItems()
        }
    }

    fun swap() {
        if (_active.value) _swapped.value = !_swapped.value
    }

    fun openList() {
        if (_active.value && entries.isNotEmpty()) _listOpen.value = true
    }

    fun closeList() {
        _listOpen.value = false
    }

    /** Another channel of the inset list. After a swap this is the large picture. */
    fun zap(step: Int) {
        if (entries.isEmpty()) return
        index = (index + step).mod(entries.size)
        playCurrent()
    }

    fun choose(at: Int) {
        if (entries.isEmpty()) return
        index = at.coerceIn(0, entries.lastIndex)
        _listOpen.value = false
        playCurrent()
    }

    private fun playCurrent() {
        val entry = entries.getOrNull(index) ?: return
        val exo = ensure()
        exo.volume = if (_swapped.value) 1f else 0f
        // A satellite address is built when the row is chosen, off the click path's caller.
        if (entry.url.isBlank() && entry.serviceRef.isNotBlank()) {
            // Resolved by [resolve] before prepare; start() already has urls for IPTV.
        }
        authorization = entry.authorization
        val url = entry.url
        if (url.isBlank()) return
        sawFrame = false
        exo.setMediaItem(item(url))
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * The encoder slot failed before a frame. Try the encoder on the stream port, then the
     * direct stream. This player has its own HTTP stack, so that request does not hang up
     * the large picture.
     */
    private fun playFallback() {
        if (sawFrame) return
        val entry = entries.getOrNull(index) ?: return
        val next = when {
            entry.fallbackUrl.isNotBlank() -> entry.fallbackUrl
            entry.directUrl.isNotBlank() && entry.directUrl != entry.url -> entry.directUrl
            else -> return
        }
        val updated = entry.copy(
            url = next,
            fallbackUrl = "",
            directUrl = if (next == entry.directUrl) "" else entry.directUrl,
        )
        entries = entries.toMutableList().also { it[index] = updated }
        val exo = player ?: return
        exo.setMediaItem(item(next))
        exo.prepare()
        exo.playWhenReady = true
    }

    /** Fills satellite urls, then starts. Call from a coroutine. */
    suspend fun startResolved(list: List<PipEntry>, at: Int) {
        val receiver = settings.current().activeReceiver()
        val resolved = list.map { entry ->
            if (entry.url.isNotBlank() || entry.serviceRef.isBlank() || receiver == null) entry
            else {
                val target = targets.inset(receiver, entry.serviceRef)
                entry.copy(url = target.url, authorization = target.authorization)
            }
        }
        start(resolved, at)
    }

    private suspend fun iptvCategories(): List<PipChoice> {
        val settingsNow = settings.current()
        if (settingsNow.iptvSource == IptvSourceMode.PLAYLIST || playlistSync.count() > 0) {
            return playlistSync.categories(XtreamSection.LIVE).map { PipChoice(it.id, it.name) }
        }
        val account = xstreamity() ?: return emptyList()
        return repository.categories(account, XtreamSection.LIVE).map { PipChoice(it.id, it.name) }
    }

    private suspend fun iptvChannels(categoryId: String): List<PipEntry> {
        val settingsNow = settings.current()
        if (settingsNow.iptvSource == IptvSourceMode.PLAYLIST || playlistSync.count() > 0) {
            val account = playlistStore.login.value.xtreamAccount()
            return playlistSync.items(XtreamSection.LIVE, categoryId).map { item ->
                val url = item.url.ifBlank { account?.let { XtreamUrls.live(it, item.id) }.orEmpty() }
                PipEntry(item.name, url)
            }
        }
        val account = xstreamity() ?: return emptyList()
        return repository.items(account, XtreamSection.LIVE, categoryId).map { item ->
            PipEntry(item.name, item.url.ifBlank { XtreamUrls.live(account, item.id) })
        }
    }

    private suspend fun xstreamity(): XtreamAccount? {
        val receiver = settings.current().activeReceiver() ?: return null
        val found = repository.cachedDetection(receiver.id) ?: repository.detect(receiver)
        return found.accounts.firstOrNull()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun ensure(): ExoPlayer {
        player?.let { return it }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(XtreamRepository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        val withAuth = ResolvingDataSource.Factory(http) { spec: DataSpec ->
            val header = authorization
            if (header == null) spec else spec.withAdditionalHeaders(mapOf("Authorization" to header))
        }
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, withAuth))
                    .setLoadErrorHandlingPolicy(InsetLoadPolicy()),
            )
            .build()
            .also { exo ->
                exo.volume = 0f
                exo.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        sawFrame = true
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        playFallback()
                    }
                })
                player = exo
            }
    }

    /** A closed encoder port should fall through at once, not after several retries. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private class InsetLoadPolicy : DefaultLoadErrorHandlingPolicy(1) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val code = (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (code != null && code in 400..499) return C.TIME_UNSET
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    private fun item(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        val path = url.substringBefore('?')
        if (path.endsWith(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        else if (path.endsWith(".ts")) builder.setMimeType(MimeTypes.VIDEO_MP2T)
        return builder.build()
    }
}
