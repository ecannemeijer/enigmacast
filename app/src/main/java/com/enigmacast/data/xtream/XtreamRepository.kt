package com.enigmacast.data.xtream

import com.enigmacast.data.settingsimport.FtpReceiverClient
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.xtream.XStreamityPlaylists
import com.enigmacast.domain.xtream.XtreamAccount
import com.enigmacast.domain.xtream.XtreamCategory
import com.enigmacast.domain.xtream.XtreamEpg
import com.enigmacast.domain.xtream.XtreamEpisode
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamJson
import com.enigmacast.domain.xtream.XtreamSection
import com.enigmacast.domain.xtream.XtreamUrls
import com.enigmacast.util.EnigmaLog
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the receiver has of XStreamity. */
data class XtreamDetection(val pluginInstalled: Boolean, val accounts: List<XtreamAccount>) {
    val available: Boolean get() = accounts.isNotEmpty()
}

/**
 * XStreamity playlists of a receiver (read over FTP, kept in memory per receiver, never saved)
 * and the Xtream Codes API of those accounts, with an in-memory cache per account and action.
 * Logins are never logged: only the action and the server's host name.
 */
@Singleton
class XtreamRepository @Inject constructor(
    private val webif: com.enigmacast.data.openwebif.OpenWebifApi,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val detections = ConcurrentHashMap<String, Pair<Long, XtreamDetection>>()
    private val cache = ConcurrentHashMap<String, Pair<Long, Any>>()
    private val detectLock = Mutex()

    suspend fun detect(receiver: ReceiverConfig, force: Boolean = false): XtreamDetection = detectLock.withLock {
        val now = System.currentTimeMillis()
        if (!force) detections[receiver.id]?.takeIf { now - it.first < DETECT_TTL_MS }?.let { return it.second }
        val result = viaWebif(receiver) ?: viaFtp(receiver)
        EnigmaLog.network("XStreamity on ${receiver.host}: plugin=${result.pluginInstalled} playlists=${result.accounts.size}")
        detections[receiver.id] = now to result
        result
    }

    /** OpenWebif `/file` first: it needs no FTP login. Null when OpenWebif cannot be read. */
    private suspend fun viaWebif(receiver: ReceiverConfig): XtreamDetection? = runCatching {
        val txt = webif.receiverTextFile(receiver, XStreamityPlaylists.PLAYLISTS_TXT)
        val json = webif.receiverTextFile(receiver, XStreamityPlaylists.PLAYLISTS_JSON)
        val accounts = withContext(Dispatchers.Default) { merge(txt, json) }
        XtreamDetection(txt != null || json != null, accounts)
    }.getOrElse {
        EnigmaLog.network("XStreamity check via OpenWebif on ${receiver.host}: ${it.javaClass.simpleName}")
        null
    }

    private suspend fun viaFtp(receiver: ReceiverConfig): XtreamDetection = withContext(Dispatchers.IO) {
        runCatching {
            FtpReceiverClient.open(receiver).use { ftp ->
                val plugin = runCatching { ftp.list(XStreamityPlaylists.PLUGIN_DIR).isNotEmpty() }.getOrDefault(false)
                val txt = runCatching { ftp.download(XStreamityPlaylists.PLAYLISTS_TXT) }.getOrNull()?.toString(Charsets.UTF_8)
                val json = runCatching { ftp.download(XStreamityPlaylists.PLAYLISTS_JSON) }.getOrNull()?.toString(Charsets.UTF_8)
                XtreamDetection(plugin || txt != null || json != null, merge(txt, json))
            }
        }.getOrElse {
            EnigmaLog.network("XStreamity check via FTP on ${receiver.host}: ${it.javaClass.simpleName}")
            XtreamDetection(false, emptyList())
        }
    }

    private fun merge(txt: String?, json: String?): List<XtreamAccount> =
        (txt?.let(XStreamityPlaylists::parseTxt).orEmpty() + json?.let(XStreamityPlaylists::parseJson).orEmpty())
            .distinctBy { it.server to it.username }

    fun cachedDetection(receiverId: String): XtreamDetection? = detections[receiverId]?.second

    suspend fun categories(account: XtreamAccount, section: XtreamSection, force: Boolean = false): List<XtreamCategory> {
        val action = when (section) {
            XtreamSection.LIVE -> "get_live_categories"
            XtreamSection.MOVIES -> "get_vod_categories"
            XtreamSection.SERIES -> "get_series_categories"
        }
        return cached(account, action, force) { XtreamJson.categories(get(account, action)) }
    }

    suspend fun items(account: XtreamAccount, section: XtreamSection, categoryId: String, force: Boolean = false): List<XtreamItem> {
        val action = when (section) {
            XtreamSection.LIVE -> "get_live_streams"
            XtreamSection.MOVIES -> "get_vod_streams"
            XtreamSection.SERIES -> "get_series"
        }
        return cached(account, "$action/$categoryId", force) {
            XtreamJson.items(get(account, action, "category_id" to categoryId), section)
        }
    }

    suspend fun episodes(account: XtreamAccount, seriesId: String): List<XtreamEpisode> =
        cached(account, "get_series_info/$seriesId", false) { XtreamJson.episodes(get(account, "get_series_info", "series_id" to seriesId)) }

    /** Now and next of a live channel; empty when the server has no guide for it. */
    suspend fun shortEpg(account: XtreamAccount, streamId: String): List<XtreamEpg> = runCatching {
        cached(account, "get_short_epg/$streamId", false, ttl = EPG_TTL_MS) { XtreamJson.shortEpg(get(account, "get_short_epg", "stream_id" to streamId, "limit" to "4")) }
    }.getOrDefault(emptyList())

    /** The playlist's own info for a film or series. */
    suspend fun vodInfo(account: XtreamAccount, item: com.enigmacast.domain.xtream.XtreamItem): com.enigmacast.domain.xtream.XtreamVodInfo? = runCatching {
        if (item.section == XtreamSection.SERIES) {
            cached(account, "series_info_meta/${item.id}", false) { Optional(XtreamJson.vodInfo(get(account, "get_series_info", "series_id" to item.id))) }.value
        } else {
            cached(account, "get_vod_info/${item.id}", false) { Optional(XtreamJson.vodInfo(get(account, "get_vod_info", "vod_id" to item.id))) }.value
        }
    }.getOrNull()

    private data class Optional<T>(val value: T?)

    /** The programme column of one channel: up to 12 events. */
    suspend fun guide(account: XtreamAccount, streamId: String): List<XtreamEpg> = runCatching {
        cached(account, "get_short_epg12/$streamId", false, ttl = EPG_TTL_MS) { XtreamJson.shortEpg(get(account, "get_short_epg", "stream_id" to streamId, "limit" to "12")) }
    }.getOrDefault(emptyList())

    /**
     * Fresh entries come straight from memory; a stale one is still shown at once and refreshed
     * in the background (the next open gets the new list). JSON is parsed off the main thread.
     */
    private suspend fun <T : Any> cached(account: XtreamAccount, key: String, force: Boolean, ttl: Long = TTL_MS, load: suspend () -> T): T {
        val full = "${account.key}/$key"
        val now = System.currentTimeMillis()
        val hit = cache[full]
        @Suppress("UNCHECKED_CAST")
        if (!force && hit != null) {
            if (now - hit.first >= ttl && refreshing.add(full)) {
                background.launch {
                    try { runCatching { withContext(Dispatchers.Default) { load() } }.onSuccess { cache[full] = System.currentTimeMillis() to it } }
                    finally { refreshing.remove(full) }
                }
            }
            return hit.second as T
        }
        val value = withContext(Dispatchers.Default) { load() }
        cache[full] = now to value
        return value
    }

    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val refreshing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private suspend fun get(account: XtreamAccount, action: String, vararg params: Pair<String, String>): String = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val request = Request.Builder().url(XtreamUrls.api(account, action, *params)).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                EnigmaLog.networkError("Xtream $action on ${account.host}: HTTP ${response.code}")
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body.string()
            EnigmaLog.network("Xtream $action on ${account.host}: ${body.length} bytes in ${System.currentTimeMillis() - started} ms")
            body
        }
    }

    companion object {
        private const val TTL_MS = 30 * 60_000L
        private const val EPG_TTL_MS = 5 * 60_000L
        private const val DETECT_TTL_MS = 30 * 60_000L
        const val USER_AGENT = "EnigmaCast"
    }
}
