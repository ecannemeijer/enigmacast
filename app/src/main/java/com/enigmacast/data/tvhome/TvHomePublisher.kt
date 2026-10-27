package com.enigmacast.data.tvhome

import android.content.Context
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
import com.enigmacast.domain.source.TunerStatusSource
import com.enigmacast.domain.sync.GuideCacheReader
import com.enigmacast.domain.tuner.ServiceRef
import com.enigmacast.domain.tvhome.TvHome
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The two Android TV home-screen rows: "EnigmaCast – Favorieten" (recent live channels with the
 * programme on now) and "EnigmaCast – Opnames" (newest receiver recordings). The rows are rebuilt
 * by the background refresh, when the launcher asks, and when the app goes to the background.
 * Off in hotel mode and when the setting is off: then both rows are removed. Watch Next is
 * [com.enigmacast.data.xtream.WatchNextPublisher]'s and is not touched here.
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
) {
    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences("tvhome", Context.MODE_PRIVATE) }
    private val _browsableRequest = MutableStateFlow<Long?>(null)

    /** A row the launcher only shows after the user agrees (the second channel); MainActivity asks once. */
    val browsableRequest: StateFlow<Long?> = _browsableRequest.asStateFlow()

    fun browsableAsked() {
        _browsableRequest.value = null
    }

    suspend fun refresh() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                if (!supported()) return@withContext
                val current = settings.current()
                if (!current.tvHomeChannels || hotel.current.enabled || HotelRuntime.active) {
                    removeChannels()
                } else {
                    publish(current)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EnigmaLog.service("tv home rows not updated: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Hotel mode turned on or the setting off: both rows go, right away. */
    suspend fun removeAll() = mutex.withLock {
        withContext(Dispatchers.IO) { runCatching { if (supported()) removeChannels() } }
    }

    private fun supported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private suspend fun publish(current: AppSettings) {
        val receiver = current.activeReceiver()
        val favorites = ensureChannel(TvHome.FAVORITES_KEY, context.getString(R.string.tvhome_favorites), default = true)
        val recordings = ensureChannel(TvHome.RECORDINGS_KEY, context.getString(R.string.tvhome_recordings), default = false)
        val used = HashSet<String>()
        val live = liveCards(current)
        replace(favorites, live.mapIndexed { index, card -> liveProgram(favorites, card, index, live.size, receiver, used) })
        val movies = receiver?.let { r -> runCatching { api.movies(r) }.getOrNull() }
        // An unreachable receiver keeps the last row instead of emptying it.
        if (movies != null && receiver != null) {
            val newest = movies.filter { it.filename.isNotBlank() }.sortedByDescending { it.begin }.take(TvHome.MAX_RECORDINGS)
            val picons = piconsByName(receiver, newest.map { it.serviceName })
            replace(
                recordings,
                newest.mapIndexed { index, movie ->
                    val picon = picons[movie.serviceName.trim().lowercase()]
                    val art = picon?.let { savePicon(receiver, it, used) }
                    val description = listOfNotNull(
                        movie.serviceName.takeIf { it.isNotBlank() },
                        movie.begin.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it * 1000)) },
                    ).joinToString(" · ")
                    PreviewProgram.Builder()
                        .setChannelId(recordings)
                        .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                        .setTitle(movie.name.ifBlank { movie.filename.substringAfterLast('/') })
                        .setDescription(description)
                        .setPosterArtUri(art ?: banner())
                        .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                        .setDurationMillis((movie.lengthSec * 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                        .setIntentUri(Uri.parse(TvHome.uri(TvHome.Link.Recording(movie.filename))))
                        .setInternalProviderId("rec:" + hash(movie.filename))
                        .setWeight(newest.size - index)
                        .build()
                },
            )
        }
        cleanArt(used, keepAll = movies == null)
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
        used: MutableSet<String>,
    ): PreviewProgram {
        var title = card.title
        var description = ""
        var art: Uri? = card.logo.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val link = card.link
        if (link is TvHome.Link.Sat && receiver != null) {
            val now = System.currentTimeMillis() / 1000
            val event = runCatching { guide.serviceEvents(receiver, link.reference) }.getOrNull()
                ?.firstOrNull { it.beginEpochSec <= now && now < it.beginEpochSec + it.durationSec }
            if (event != null && event.title.isNotBlank()) {
                title = "${card.title} – ${event.title}"
                description = event.shortDescription
            }
            art = ServiceRef.piconPath(link.reference)?.let { savePicon(receiver, it, used) }
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

    /** Channel name → picon path, from the cached bouquets (for recordings, which only name their channel). */
    private suspend fun piconsByName(receiver: ReceiverConfig, names: List<String>): Map<String, String> {
        val wanted = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toMutableSet()
        val out = HashMap<String, String>()
        if (wanted.isEmpty()) return out
        val bouquets = runCatching { guide.televisionBouquets(receiver) }.getOrNull().orEmpty().take(8)
        for (bouquet in bouquets) {
            val channels: List<BouquetEntry> = runCatching { guide.channelsInBouquet(receiver, bouquet.reference) }.getOrNull().orEmpty()
            for (channel in channels) {
                val key = channel.name.trim().lowercase()
                if (key in wanted) {
                    val path = channel.picon.ifBlank { ServiceRef.piconPath(channel.reference).orEmpty() }
                    if (path.isNotBlank()) { out[key] = path; wanted -= key }
                }
            }
            if (wanted.isEmpty()) break
        }
        return out
    }

    /** The picon on our own provider; fetched from the receiver once, kept for later refreshes. */
    private suspend fun savePicon(receiver: ReceiverConfig, path: String, used: MutableSet<String>): Uri? {
        val name = hash(receiver.id + "|" + path) + ".png"
        val dir = TvHomeArtProvider.dir(context)
        val file = File(dir, name)
        if (!file.isFile || file.length() == 0L) {
            val bytes = runCatching { tuner.picon(receiver, path) }.getOrNull() ?: return null
            if (bytes.isEmpty()) return null
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(bytes)
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
            if (!existing.isBrowsable && !default && !prefs.getBoolean("asked:$key", false)) {
                prefs.edit().putBoolean("asked:$key", true).apply()
                _browsableRequest.value = existing.id
            }
            return existing.id
        }
        val channel = PreviewChannel.Builder()
            .setDisplayName(name)
            .setAppLinkIntentUri(Uri.parse(TvHome.uri(TvHome.Link.Open)))
            .setInternalProviderId(key)
            .setLogo(logo())
            .build()
        val id = if (default) helper.publishDefaultChannel(channel) else helper.publishChannel(channel)
        if (!default) {
            prefs.edit().putBoolean("asked:$key", true).apply()
            _browsableRequest.value = id
        }
        prefs.edit().remove("rows:$id").apply()
        return id
    }

    private fun logo(): Bitmap {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
            ?: ContextCompat.getDrawable(context, R.drawable.ic_launcher)!!
        return drawable.toBitmap(160, 160)
    }

    /** Swaps the row's programs, unless nothing changed (no flicker on the home screen). */
    private fun replace(channelId: Long, programs: List<PreviewProgram>) {
        val fingerprint = hash(programs.joinToString("\n") { it.toContentValues().toString() })
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
            .filter { it.internalProviderId == TvHome.FAVORITES_KEY || it.internalProviderId == TvHome.RECORDINGS_KEY }
            .forEach { helper.deletePreviewChannel(it.id) }
        prefs.edit().clear().apply()
        TvHomeArtProvider.dir(context).listFiles()?.forEach { it.delete() }
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
