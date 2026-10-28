package com.enigmacast.data.tvhome

import android.content.Context
import android.util.Log
import coil3.toBitmap
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.enigmacast.R
import com.enigmacast.data.hotel.HotelStore
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.xtream.IptvPlaylistStore
import com.enigmacast.data.xtream.RecentIptvStore
import com.enigmacast.domain.hotel.HotelRuntime
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelMemory
import com.enigmacast.domain.model.LastLiveSource
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.recording.RecordingPlace
import com.enigmacast.domain.recording.RecordingStatus
import com.enigmacast.domain.source.TunerStatusSource
import com.enigmacast.domain.sync.GuideCacheReader
import com.enigmacast.domain.tvhome.TvHome
import com.enigmacast.domain.tvhome.TvHomeArt
import com.enigmacast.data.xtream.XtreamRepository
import com.enigmacast.live.RecordingArtwork
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Android TV home-screen rows: "EnigmaCast – Favorieten" (recent live channels with the
 * programme on now), "– IPTV-favorieten" (starred films and series), "– Verder kijken" (IPTV
 * films, episodes and receiver recordings with a saved place) and "– Opnames" (newest receiver
 * recordings). The rows are rebuilt by the background refresh, when the launcher asks, and when
 * the app goes to the background. Off in hotel mode and when the setting is off: then all rows
 * are removed. Watch Next is [com.enigmacast.data.xtream.WatchNextPublisher]'s and is not touched here.
 */
@Singleton
class TvHomePublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val hotel: HotelStore,
    private val recentIptv: RecentIptvStore,
    private val playlists: IptvPlaylistStore,
    private val guide: GuideCacheReader,
    private val tuner: TunerStatusSource,
    private val api: OpenWebifApi,
    private val artwork: RecordingArtwork,
    private val library: com.enigmacast.data.xtream.IptvLibrary,
    private val resumeDao: com.enigmacast.data.db.ResumeDao,
    private val profiles: com.enigmacast.data.profile.ProfileStore,
    private val gate: com.enigmacast.live.PremiumGate,
    private val xtream: XtreamRepository,
    private val playlistSync: com.enigmacast.data.xtream.IptvPlaylistSync,
) {
    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences("tvhome", Context.MODE_PRIVATE) }
    private val _browsablePending = MutableStateFlow<List<Long>>(emptyList())
    private val logoClient by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }
    /** SVG logos (BitmapFactory cannot read them); only built when one turns up. */
    private val svgLoader by lazy {
        coil3.ImageLoader.Builder(context)
            .components { add(coil3.svg.SvgDecoder.Factory()) }
            .build()
    }

    /**
     * Rows the launcher only shows after the user agrees (every row but the first). MainActivity
     * asks for them one by one while it is in the foreground (Android cancels the question from
     * the background) and reports each one with [browsableAsked].
     */
    val browsablePending: StateFlow<List<Long>> = _browsablePending.asStateFlow()

    /** The question for [channelId] was shown (or the TV has none): not asked again by itself. */
    fun browsableAsked(channelId: Long) {
        prefs.edit().putBoolean("asked:$channelId", true).apply()
        _browsablePending.update { it - channelId }
    }

    private fun queueBrowsable(channelId: Long) {
        _browsablePending.update { if (channelId in it) it else it + channelId }
    }

    /** Google TV (its launcher decides which app rows it shows; see the note in Settings). */
    fun isGoogleTv(): Boolean = TvHomeDevice.isGoogleTv(context)

    /**
     * Settings › "Rijen op startscherm toevoegen": builds the rows now and asks again for every
     * row that is still hidden. The number of rows asked for; 0 when all are shown already.
     */
    suspend fun requestRows(): Int {
        refresh()
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (!supported()) return@runCatching 0
                    val hidden = helper().allChannels.filter { it.internalProviderId in TvHome.ALL_KEYS && !it.isBrowsable }
                    hidden.forEach { channel ->
                        if (channel.internalProviderId == TvHome.FAVORITES_KEY) {
                            requestDefault(channel.id)
                        } else {
                            queueBrowsable(channel.id)
                        }
                    }
                    hidden.size
                }.getOrDefault(0)
            }
        }
    }

    suspend fun refresh() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (!supported()) return@withContext
                val current = settings.current()
                val rows = TvHome.rows(current, hotel.current.enabled || HotelRuntime.active)
                if (rows.isEmpty()) {
                    removeChannels()
                } else {
                    publish(current, rows)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EnigmaLog.service("tv home rows not updated: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Hotel mode turned on or the setting off: all rows go, right away. */
    suspend fun removeAll() = mutex.withLock {
        withContext(Dispatchers.IO) { runCatching { if (supported()) removeChannels() } }
    }

    private fun supported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private suspend fun publish(current: AppSettings, rows: List<TvHome.Row>) {
        val receiver = current.activeReceiver()
        migrateLayout(rows)
        val ids = LinkedHashMap<TvHome.Row, Long>()
        val hasDefault = helper().allChannels.any { it.internalProviderId == TvHome.FAVORITES_KEY }
        for (row in TvHome.Row.entries) {
            if (row in rows || row == TvHome.Row.FAVORITES) {
                // Favorieten is the default channel: kept (emptied) when only its own switch is off,
                // because a removed default channel comes back at the bottom only after the user adds it.
                if (row == TvHome.Row.FAVORITES && row !in rows && !hasDefault) continue
                ids[row] = ensureChannel(row.key, rowName(row), default = row == TvHome.Row.FAVORITES)
            } else {
                deleteChannel(row.key)
            }
        }
        val used = HashSet<String>()
        val channels = BouquetIndex(receiver)
        ids[TvHome.Row.FAVORITES]?.let { favorites ->
            val live = if (TvHome.Row.FAVORITES in rows) liveCards(current) else emptyList()
            val stats = LogoStats()
            replace(favorites, live.mapIndexed { index, card -> liveProgram(favorites, card, index, live.size, receiver, channels, used, stats) })
            if (stats.total > 0) Log.i(TAG, "favorites row: ${stats.total} iptv logos, ${stats.own} own, ${stats.playlist} playlist, ${stats.picon} picon, ${stats.banner} banner")
        }
        val needMovies = TvHome.Row.RECORDINGS in rows || TvHome.Row.CONTINUE in rows
        val movies = if (needMovies) receiver?.let { r -> runCatching { api.movies(r) }.getOrNull() } else null
        var keepArt = false
        val recordings = ids[TvHome.Row.RECORDINGS]
        // An unreachable receiver keeps the last row instead of emptying it.
        if (recordings != null && receiver != null && movies == null) keepArt = true
        if (recordings != null && movies != null && receiver != null) {
            val newest = movies.filter { it.filename.isNotBlank() }.sortedByDescending { it.begin }.take(TvHome.MAX_RECORDINGS)
            val items = newest.map { movie -> movie to recordingOf(receiver, movie) }
            // The same TMDB match (and Room cache) as the recordings in My items; no key = picons only.
            runCatching { artwork.resolve(items.map { it.second }) }
            val matches = artwork.movies.value
            replace(
                recordings,
                items.mapIndexed { index, (movie, item) ->
                    val tmdb = matches[item.id]
                    val picture = TvHomeArt.recordingPicture(tmdb)
                    val art = picture?.let { Uri.parse(it.url) }
                        ?: piconArt(receiver, channels.piconPaths("", movie.serviceName), used)
                    val aspect = if (picture?.shape == TvHomeArt.Shape.POSTER) {
                        TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
                    } else {
                        TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
                    }
                    val date = movie.begin.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it * 1000)) }
                    PreviewProgram.Builder()
                        .setChannelId(recordings)
                        .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                        .setTitle(movie.name.ifBlank { movie.filename.substringAfterLast('/') })
                        .setDescription(TvHomeArt.recordingDescription(movie.description, tmdb, movie.serviceName, date))
                        .setPosterArtUri(art ?: banner())
                        .setPosterArtAspectRatio(aspect)
                        .setDurationMillis((movie.lengthSec * 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                        .setIntentUri(Uri.parse(TvHome.uri(TvHome.Link.Recording(movie.filename))))
                        .setInternalProviderId("rec:" + hash(movie.filename))
                        .setWeight(newest.size - index)
                        .build()
                },
            )
        }
        val sources = if (TvHome.Row.IPTV_FAVORITES in rows || TvHome.Row.CONTINUE in rows) librarySources(current) else emptySet()
        ids[TvHome.Row.IPTV_FAVORITES]?.let { channelId ->
            val cards = TvHome.iptvFavorites(runCatching { library.favoritesNow() }.getOrDefault(emptyList()), sources)
            replace(channelId, cards.mapIndexed { index, card -> libraryProgram(channelId, card, index, cards.size, receiver, channels, used, "fav:") })
        }
        ids[TvHome.Row.CONTINUE]?.let { channelId ->
            if (receiver != null && movies == null) keepArt = true
            val boxResumes = movies?.let { list -> recordingResumes(list) }.orEmpty()
            val cards = TvHome.continueCards(runCatching { library.resumesNow() }.getOrDefault(emptyList()), sources, boxResumes)
            // Recordings come from the receiver: when it does not answer, the IPTV part still updates.
            if (movies != null || receiver == null || cards.isNotEmpty()) {
                replace(channelId, cards.mapIndexed { index, card -> libraryProgram(channelId, card, index, cards.size, receiver, channels, used, "cont:") })
            }
        }
        cleanArt(used, keepAll = keepArt)
    }

    private fun rowName(row: TvHome.Row): String = context.getString(
        when (row) {
            TvHome.Row.FAVORITES -> R.string.tvhome_favorites
            TvHome.Row.IPTV_FAVORITES -> R.string.tvhome_iptv_favorites
            TvHome.Row.CONTINUE -> R.string.tvhome_continue
            TvHome.Row.RECORDINGS -> R.string.tvhome_recordings
        },
    )

    /**
     * Earlier versions made "Opnames" the second row. When the new rows are added for the first
     * time and Opnames was never shown (still waiting for the user's OK), it is made again after
     * them, so the order is Favorieten, IPTV-favorieten, Verder kijken, Opnames. A shown Opnames
     * row is left alone (the user would have to agree again).
     */
    private fun migrateLayout(rows: List<TvHome.Row>) {
        if (prefs.getInt("layout", 1) >= LAYOUT) return
        val helper = helper()
        val all = helper.allChannels
        val newRows = all.any { it.internalProviderId == TvHome.IPTV_FAVORITES_KEY || it.internalProviderId == TvHome.CONTINUE_KEY }
        val adding = TvHome.Row.IPTV_FAVORITES in rows || TvHome.Row.CONTINUE in rows
        if (!newRows && adding) {
            all.filter { it.internalProviderId == TvHome.RECORDINGS_KEY && !it.isBrowsable }.forEach {
                helper.deletePreviewChannel(it.id)
                prefs.edit().remove("rows:${it.id}").remove("asked:${it.id}").apply()
            }
        }
        prefs.edit().putInt("layout", LAYOUT).apply()
    }

    /** Playlist accounts in use (as Library shows them); none without IPTV Premium or for a viewer without playlists. */
    private suspend fun librarySources(current: AppSettings): Set<String> = runCatching {
        if (!gate.allows(com.enigmacast.domain.account.PremiumFeature.XTREAM)) return@runCatching emptySet()
        val out = LinkedHashSet<String>()
        if (current.iptvSource == com.enigmacast.domain.model.IptvSourceMode.PLAYLIST) {
            if (!profiles.seesPlaylist()) return@runCatching emptySet()
            val providers = playlists.enabledProviders()
            if (providers.isNotEmpty()) {
                providers.mapNotNullTo(out) { it.account()?.key }
            } else {
                val login = playlists.login.value
                when (current.iptvPlaylistKind) {
                    com.enigmacast.domain.model.IptvPlaylistKind.XTREAM -> login.xtreamAccount()?.key?.let(out::add)
                    com.enigmacast.domain.model.IptvPlaylistKind.M3U ->
                        if (login.configured(com.enigmacast.domain.model.IptvPlaylistKind.M3U)) out += com.enigmacast.ui.xtream.XtreamViewModel.M3U_ACCOUNT.key
                }
            }
        } else {
            val receiver = current.activeReceiver()
            if (receiver != null) xtream.cachedDetection(receiver.id)?.accounts?.mapTo(out) { it.key }
            // The receiver's IPTV accounts are only known after a detection; the last-watched ones are.
            current.lastIptvChannels.keys.filterTo(out) { it.isNotBlank() }
        }
        out - com.enigmacast.domain.torrent.TorrentLibraryRows.SOURCE
    }.getOrDefault(emptySet())

    /** Box recordings with a saved place (the viewer's own), by file name. */
    private suspend fun recordingResumes(movies: List<com.enigmacast.data.openwebif.OpenWebifJson.ReceiverMovie>): List<TvHome.RecordingResume> {
        val profile = profiles.state.value.activeId
        val points = runCatching { resumeDao.all().first() }.getOrDefault(emptyList()).mapNotNull { row ->
            com.enigmacast.domain.profile.ProfileRules.unscope(profile, row.key)?.let { it to row }
        }.toMap()
        return movies.mapNotNull { movie ->
            val row = points[com.enigmacast.live.RecordingBook.BOX_MOVIE + movie.filename] ?: return@mapNotNull null
            TvHome.RecordingResume(
                file = movie.filename,
                title = movie.name.ifBlank { movie.filename.substringAfterLast('/') },
                channel = movie.serviceName,
                positionMs = row.positionMs,
                durationMs = row.durationMs.takeIf { it > 0 } ?: (movie.lengthSec * 1000),
                updatedMs = row.updatedMs,
            )
        }
    }

    /** A card in IPTV-favorieten or Verder kijken. */
    private suspend fun libraryProgram(
        channelId: Long,
        card: TvHome.LibraryCard,
        index: Int,
        count: Int,
        receiver: ReceiverConfig?,
        channels: BouquetIndex,
        used: MutableSet<String>,
        prefix: String,
    ): PreviewProgram {
        val uri = TvHome.uri(card.link)
        val poster = card.poster
        var tall = card.tall
        var art: Uri? = null
        if (poster != null) {
            art = TvHome.publicLogo(poster)?.takeIf { it.startsWith("https://", ignoreCase = true) }?.let(Uri::parse)
                ?: TvHomeArt.downloadableLogo(poster)?.let { savePoster(it, used) }
        }
        if (art == null && card.link is TvHome.Link.Recording && receiver != null) {
            art = piconArt(receiver, channels.piconPaths("", card.channel), used)
            tall = false
        }
        if (art == null) tall = false
        val builder = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(
                when {
                    card.link is TvHome.Link.Recording -> TvContractCompat.PreviewPrograms.TYPE_CLIP
                    card.link is TvHome.Link.Resume && card.link.kind == "e" -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
                    card.link is TvHome.Link.Library && card.link.kind == "s" -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
                    else -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
                },
            )
            .setTitle(card.title)
            .setDescription(card.subtitle)
            .setPosterArtUri(art ?: banner())
            .setPosterArtAspectRatio(
                if (tall) TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3 else TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9,
            )
            .setIntentUri(Uri.parse(uri))
            .setInternalProviderId(prefix + hash(uri))
            .setWeight(count - index)
        if (card.durationMs > 0) {
            builder.setDurationMillis(card.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (card.positionMs > 0) builder.setLastPlaybackPositionMillis(card.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        return builder.build()
    }

    private fun liveCards(current: AppSettings): List<TvHome.LiveCard> {
        val receiver = current.activeReceiver()
        val sat = receiver?.let { ChannelMemory.recentFor(current, it.id) }.orEmpty()
        val keys = LinkedHashSet<String>()
        current.lastIptvChannels.keys.filterTo(keys) { it.isNotBlank() }
        playlists.enabledProviders().mapNotNullTo(keys) { it.account()?.key }
        val iptv = keys.flatMap { key -> recentIptv.load(key).map { key to it } }
        return TvHome.liveCards(sat, iptv, iptvFirst = current.lastLiveSource == LastLiveSource.IPTV)
    }

    private suspend fun liveProgram(
        channelId: Long,
        card: TvHome.LiveCard,
        index: Int,
        count: Int,
        receiver: ReceiverConfig?,
        channels: BouquetIndex,
        used: MutableSet<String>,
        stats: LogoStats,
    ): PreviewProgram {
        var title = card.title
        var description = ""
        var art: Uri? = null
        val link = card.link
        if (link is TvHome.Link.Iptv) {
            // 1. The logo the app shows (downloaded by the app and served from our provider: launchers
            //    do not load plain-http logos, and the address may carry the playlist login).
            // 2. The playlist's current logo for this channel, when the remembered one is gone.
            // 3. The receiver's picon of the same channel by name ("NL - SBS 6 4K" → SBS 6).
            // 4. The EnigmaCast banner.
            stats.total++
            art = card.logo.takeIf { it.isNotBlank() }?.let { saveLogo(it, used) }?.also { stats.own++ }
            if (art == null) {
                art = channels.playlistLogo(link)?.takeIf { it != card.logo }?.let { saveLogo(it, used) }?.also { stats.playlist++ }
            }
            if (art == null && receiver != null) {
                art = piconArt(receiver, channels.piconsByName(link.name.ifBlank { card.title }), used)?.also { stats.picon++ }
            }
            if (art == null) stats.banner++
        }
        if (link is TvHome.Link.Sat && receiver != null) {
            val now = System.currentTimeMillis() / 1000
            val event = runCatching { guide.serviceEvents(receiver, link.reference) }.getOrNull()
                ?.firstOrNull { it.beginEpochSec <= now && now < it.beginEpochSec + it.durationSec }
            if (event != null && event.title.isNotBlank()) {
                title = "${card.title} – ${event.title}"
                description = event.shortDescription
            }
            art = piconArt(receiver, channels.piconPaths(link.reference, link.name.ifBlank { card.title }), used)
        }
        return PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
            .setLive(true)
            .setTitle(title)
            .setDescription(description)
            .setPosterArtUri(art ?: banner())
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setIntentUri(Uri.parse(TvHome.uri(link)))
            .setInternalProviderId("live:" + hash(TvHome.uri(link)))
            .setWeight(count - index)
            .build()
    }

    /** A box recording as a [Recording], so [RecordingArtwork] matches it like the ones in My items. */
    private fun recordingOf(receiver: ReceiverConfig, movie: com.enigmacast.data.openwebif.OpenWebifJson.ReceiverMovie): Recording =
        Recording(
            id = "tvhome:" + hash(receiver.id + "|" + movie.filename),
            title = movie.name.ifBlank { movie.filename.substringAfterLast('/').substringBeforeLast('.') },
            channelName = movie.serviceName,
            reference = "",
            eventId = 0,
            beginEpochSec = movie.begin,
            endEpochSec = movie.begin + movie.lengthSec.coerceAtLeast(0),
            place = RecordingPlace.RECEIVER,
            status = RecordingStatus.READY,
            receiverId = receiver.id,
            fileName = movie.filename,
        )

    /**
     * The synced TV bouquets of the receiver, read once per refresh and only when a card needs a
     * picon: the picon paths OpenWebif reported are the files the app's own channel lists show.
     */
    private inner class BouquetIndex(private val receiver: ReceiverConfig?) {
        private var entries: List<BouquetEntry>? = null

        private var names: com.enigmacast.domain.epg.SatEpgNames.Index? = null
        private var logos: Map<String, List<com.enigmacast.domain.xtream.XtreamItem>>? = null

        suspend fun piconPaths(reference: String, name: String): List<String> =
            TvHomeArt.piconCandidates(reference, name, entries())

        /** Picons of the receiver channel an IPTV channel name matches (the satellite-guide matching). */
        suspend fun piconsByName(name: String): List<String> {
            val all = entries()
            if (all.isEmpty()) return emptyList()
            val index = names ?: com.enigmacast.domain.epg.SatEpgNames.index(
                all.map { com.enigmacast.domain.epg.SatEpgNames.Channel(it.name, it.reference, it.reference) },
            ).also { names = it }
            return TvHomeArt.piconsByName(name, all, index)
        }

        /** The logo the playlist has now for this IPTV channel (read once, only when a logo failed). */
        suspend fun playlistLogo(link: TvHome.Link.Iptv): String? {
            val byId = logos ?: runCatching { playlistSync.liveItems() }.getOrDefault(emptyList())
                .filter { it.icon.isNotBlank() }
                .groupBy { it.id }
                .also { logos = it }
            val items = byId[link.id].orEmpty()
            if (items.isEmpty()) return null
            val providers = playlists.providers.value.filter { it.account()?.key == link.source }.map { it.id }.toSet()
            val item = items.firstOrNull { it.providerId in providers } ?: items.first()
            return TvHomeArt.downloadableLogo(item.icon)
        }

        private suspend fun entries(): List<BouquetEntry> {
            entries?.let { return it }
            val out = ArrayList<BouquetEntry>()
            val box = receiver
            if (box != null) {
                val bouquets = runCatching { guide.televisionBouquets(box) }.getOrNull().orEmpty().take(MAX_BOUQUETS)
                for (bouquet in bouquets) {
                    out += runCatching { guide.channelsInBouquet(box, bouquet.reference) }.getOrNull().orEmpty()
                }
            }
            entries = out
            return out
        }
    }

    /** The first of [paths] the receiver has, as a 16:9 card on our own provider. */
    private suspend fun piconArt(receiver: ReceiverConfig?, paths: List<String>, used: MutableSet<String>): Uri? {
        if (receiver == null) return null
        for (path in paths) {
            saveCard(receiver.id + "|" + path, used, fetch = { runCatching { tuner.picon(receiver, path) }.getOrNull() })?.let { return it }
        }
        return null
    }

    /** An IPTV logo, downloaded by the app (never handed to the launcher as an address). */
    private suspend fun saveLogo(url: String, used: MutableSet<String>): Uri? =
        saveCard("logo|$url", used, render = { bytes -> TvHomeCardArt.card(bytes) ?: svgCard(bytes) }) { download(url) }

    /** A film or series poster, downloaded by the app (its address may carry the playlist login). */
    private suspend fun savePoster(url: String, used: MutableSet<String>): Uri? =
        saveCard("poster|$url", used, render = { bytes -> TvHomeCardArt.poster(bytes) }) { download(url) }

    private fun download(url: String): ByteArray? = try {
        logoClient.newCall(Request.Builder().url(url).header("User-Agent", XtreamRepository.USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body.bytes().takeIf { it.size <= TvHomeArt.MAX_LOGO_BYTES }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** An SVG logo (common in IPTV playlists) drawn by Coil, then laid on the card like the others. */
    private suspend fun svgCard(bytes: ByteArray): ByteArray? {
        if (!TvHomeArt.looksLikeSvg(bytes)) return null
        return try {
            val request = coil3.request.ImageRequest.Builder(context)
                .data(bytes)
                .size(TvHomeArt.CARD_WIDTH, TvHomeArt.CARD_HEIGHT)
                .memoryCachePolicy(coil3.request.CachePolicy.DISABLED)
                .diskCachePolicy(coil3.request.CachePolicy.DISABLED)
                .decoderFactory(coil3.svg.SvgDecoder.Factory())
                .build()
            val result = svgLoader.execute(request) as? coil3.request.SuccessResult ?: return null
            val bitmap = result.image.toBitmap()
            TvHomeCardArt.card(bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A card picture on our own provider: fetched once, laid on a 16:9 card, kept for later
     * refreshes. The file name is a hash, so no address or login ends up in the TV provider.
     */
    private suspend fun saveCard(
        key: String,
        used: MutableSet<String>,
        render: suspend (ByteArray) -> ByteArray? = { TvHomeCardArt.card(it) },
        fetch: suspend () -> ByteArray?,
    ): Uri? {
        val name = hash("v${TvHomeArt.VERSION}|$key") + ".png"
        val dir = TvHomeArtProvider.dir(context)
        val file = File(dir, name)
        if (!file.isFile || file.length() == 0L) {
            val bytes = fetch()?.takeIf { it.isNotEmpty() } ?: return null
            val png = try {
                render(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return null
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(png)
            if (!tmp.renameTo(file)) { tmp.delete(); return null }
        }
        used += name
        return TvHomeArtProvider.uri(context, name)
    }

    private fun cleanArt(used: Set<String>, keepAll: Boolean) {
        if (keepAll) return
        TvHomeArtProvider.dir(context).listFiles()?.forEach { file ->
            if (file.name !in used) file.delete()
        }
    }

    private fun banner(): Uri = Uri.parse("android.resource://${context.packageName}/drawable/tv_banner")

    private fun helper() = PreviewChannelHelper(context)

    private fun ensureChannel(key: String, name: String, default: Boolean): Long {
        val helper = helper()
        val existing = helper.allChannels.firstOrNull { it.internalProviderId == key }
        if (existing != null) {
            if (existing.displayName?.toString() != name) {
                helper.updatePreviewChannel(existing.id, PreviewChannel.Builder(existing).setDisplayName(name).build())
            }
            if (!existing.isBrowsable && !prefs.getBoolean("asked:${existing.id}", false)) {
                if (default) requestDefault(existing.id) else queueBrowsable(existing.id)
            }
            return existing.id
        }
        val channel = PreviewChannel.Builder()
            .setDisplayName(name)
            .setAppLinkIntentUri(Uri.parse(TvHome.uri(TvHome.Link.Open)))
            .setInternalProviderId(key)
            .setLogo(logo())
            .build()
        // publishDefaultChannel also makes it browsable (the first request of an app is granted).
        val id = if (default) helper.publishDefaultChannel(channel) else helper.publishChannel(channel)
        if (!default) queueBrowsable(id)
        prefs.edit().remove("rows:$id").apply()
        Log.i(TAG, "home row added: $key")
        return id
    }

    /** The default row needs no dialog: Android grants an app's first request by itself. */
    private fun requestDefault(channelId: Long) {
        runCatching { TvContractCompat.requestChannelBrowsable(context, channelId) }
            .onFailure { Log.i(TAG, "default row not made browsable: ${it.javaClass.simpleName}") }
        prefs.edit().putBoolean("asked:$channelId", true).apply()
    }

    private fun deleteChannel(key: String) {
        val helper = helper()
        helper.allChannels.filter { it.internalProviderId == key }.forEach {
            helper.deletePreviewChannel(it.id)
            prefs.edit().remove("rows:${it.id}").remove("asked:${it.id}").apply()
            _browsablePending.update { list -> list - it.id }
        }
    }

    private fun logo(): Bitmap {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
            ?: ContextCompat.getDrawable(context, R.drawable.ic_launcher)!!
        return drawable.toBitmap(160, 160)
    }

    /** Swaps the row's programs, unless nothing changed (no flicker on the home screen). */
    private fun replace(channelId: Long, programs: List<PreviewProgram>) {
        val fingerprint = hash("v${TvHomeArt.VERSION}\n" + programs.joinToString("\n") { it.toContentValues().toString() })
        val key = "rows:$channelId"
        if (prefs.getString(key, null) == fingerprint) return
        val helper = helper()
        programIds(channelId).forEach { helper.deletePreviewProgram(it) }
        programs.forEach { helper.publishPreviewProgram(it) }
        prefs.edit().putString(key, fingerprint).apply()
    }

    private fun programIds(channelId: Long): List<Long> {
        val ids = ArrayList<Long>()
        context.contentResolver.query(
            TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
            arrayOf(TvContractCompat.BaseTvColumns._ID),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        return ids
    }

    private fun removeChannels() {
        val helper = helper()
        helper.allChannels
            .filter { it.internalProviderId in TvHome.ALL_KEYS }
            .forEach { helper.deletePreviewChannel(it.id) }
        prefs.edit().clear().apply()
        _browsablePending.value = emptyList()
        TvHomeArtProvider.dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Where the IPTV cards in Favorieten got their picture (logged without any address). */
    private class LogoStats {
        var total = 0
        var own = 0
        var playlist = 0
        var picon = 0
        var banner = 0
    }

    private companion object {
        const val MAX_BOUQUETS = 40
        const val TAG = "EcTvHome"
        /** 2: Favorieten, IPTV-favorieten, Verder kijken, Opnames. */
        const val LAYOUT = 2
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
