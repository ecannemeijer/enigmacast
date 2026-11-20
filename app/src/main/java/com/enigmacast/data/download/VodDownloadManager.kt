package com.enigmacast.data.download

import android.content.Context
import android.content.Intent
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.xtream.IptvHeaderResolver
import com.enigmacast.domain.download.VodDownload
import com.enigmacast.domain.download.VodDownloadFiles
import com.enigmacast.domain.download.VodDownloadState
import com.enigmacast.domain.xtream.IptvHeaders
import com.enigmacast.domain.xtream.XtreamAccount
import com.enigmacast.domain.xtream.XtreamEpisode
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamSection
import com.enigmacast.domain.xtream.XtreamUrls
import com.enigmacast.live.VodDownloadService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
class VodDownloadManager @Inject constructor(
    private val store: VodDownloadStore,
    private val headers: IptvHeaderResolver,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val items: StateFlow<List<VodDownload>> = store.downloads().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val byKey: StateFlow<Map<String, VodDownload>> = store.ofActive().stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val pendingPlay = MutableStateFlow<VodDownload?>(null)
    fun requestPlay(item: VodDownload) { pendingPlay.value = item }
    fun consumePlay() { pendingPlay.value = null }

    fun of(source: String, kind: String, id: String): VodDownload? = byKey.value["$source/$kind/$id"]

    fun fileUri(item: VodDownload): String? {
        val file = store.fileFor(item)
        return if (item.state == VodDownloadState.DONE && file.exists()) android.net.Uri.fromFile(file).toString() else null
    }

    fun enqueueMovie(account: XtreamAccount, item: XtreamItem) {
        scope.launch {
            enqueue(
                source = account.key,
                kind = "m",
                streamId = item.id,
                title = item.name,
                poster = item.icon.takeIf { it.isNotBlank() },
                url = item.url.ifBlank { XtreamUrls.movie(account, item.id, item.extension) },
                extension = item.extension,
                item = item,
            )
        }
    }

    fun enqueueEpisode(account: XtreamAccount, show: XtreamItem, episode: XtreamEpisode, episodeUrl: String = "") {
        scope.launch {
            enqueue(
                source = account.key,
                kind = "e",
                streamId = episode.id,
                title = episode.title.ifBlank { show.name } + if (episode.season > 0) " · S${episode.season}E${episode.episode}" else "",
                seriesId = show.id,
                seriesTitle = show.name,
                season = episode.season,
                episode = episode.episode,
                poster = episode.cover.ifBlank { show.icon }.takeIf { it.isNotBlank() },
                url = episodeUrl.ifBlank { XtreamUrls.episode(account, episode.id, episode.extension) },
                extension = episode.extension,
                item = show,
            )
        }
    }

    fun enqueueSeason(account: XtreamAccount, show: XtreamItem, season: Int, episodes: List<XtreamEpisode>, urlOf: (XtreamEpisode) -> String) {
        episodes.filter { it.season == season }.forEach { episode ->
            enqueueEpisode(account, show, episode, urlOf(episode))
        }
    }

    fun pause(id: String) { scope.launch { store.pause(id); wake() } }
    fun resume(id: String) { scope.launch { store.resume(id); wake() } }
    fun cancel(id: String) { scope.launch { store.cancel(id); wake() } }
    fun delete(id: String) { scope.launch { store.delete(id) } }

    fun wake() {
        val intent = Intent(context, VodDownloadService::class.java)
        runCatching { context.startForegroundService(intent) }
    }

    private suspend fun enqueue(
        source: String,
        kind: String,
        streamId: String,
        title: String,
        seriesId: String? = null,
        seriesTitle: String? = null,
        season: Int = 0,
        episode: Int = 0,
        poster: String?,
        url: String,
        extension: String,
        item: XtreamItem,
    ) {
        val resolved = headers.forItem(item)
        val volume = settings.settings.first().iptvRecordVolume
        val now = System.currentTimeMillis()
        val job = VodDownload(
            id = "",
            profileId = "",
            source = source,
            kind = kind,
            streamId = streamId,
            title = title,
            seriesId = seriesId,
            seriesTitle = seriesTitle,
            season = season,
            episode = episode,
            poster = poster,
            url = url,
            extension = extension,
            userAgent = resolved.userAgent,
            httpHeaders = IptvHeaders.formatLines(resolved.headers),
            volumeIndex = volume,
            fileName = VodDownloadFiles.safeName(kind, streamId, extension),
            addedMs = now,
            updatedMs = now,
        )
        val created = store.enqueue(job)
        if (created.state == VodDownloadState.QUEUED || created.state == VodDownloadState.RUNNING) wake()
    }
}
