package com.enigmacast.data.xtream

import androidx.room.withTransaction
import com.enigmacast.data.db.EnigmaDatabase
import com.enigmacast.data.db.IptvCategoryRow
import com.enigmacast.data.db.IptvDao
import com.enigmacast.data.db.IptvItemRow
import com.enigmacast.data.security.SecretStore
import com.enigmacast.domain.model.IptvPlaylistKind
import com.enigmacast.domain.xtream.M3uPlaylist
import com.enigmacast.domain.xtream.XStreamityPlaylists
import com.enigmacast.domain.xtream.XtreamAccount
import com.enigmacast.domain.xtream.XtreamCategory
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamJson
import com.enigmacast.domain.xtream.XtreamSection
import com.enigmacast.domain.xtream.XtreamUrls
import com.enigmacast.util.EnigmaLog
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeToSequence
import okhttp3.OkHttpClient
import okhttp3.Request

/** The playlist entered in Settings › IPTV. Kept only in the encrypted [SecretStore]. */
data class IptvPlaylistLogin(val m3uUrl: String = "", val server: String = "", val username: String = "", val password: String = "") {
    fun configured(kind: IptvPlaylistKind): Boolean = when (kind) {
        IptvPlaylistKind.M3U -> m3uUrl.startsWith("http://", true) || m3uUrl.startsWith("https://", true)
        IptvPlaylistKind.XTREAM -> xtreamAccount() != null
    }

    /** The Xtream account; a bare host gets http://. */
    fun xtreamAccount(): XtreamAccount? {
        if (server.isBlank() || username.isBlank()) return null
        val base = server.trim().let { if (it.contains("://")) it else "http://$it" }.trimEnd('/').substringBefore("/player_api.php").substringBefore("/get.php")
        return XStreamityPlaylists.fromUrl("$base/get.php?username=${enc(username)}&password=${enc(password)}&output=ts", "Playlist")
    }

    override fun toString(): String = "IptvPlaylistLogin(***)"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

/** What a load did, or why it failed. */
sealed interface IptvLoadResult {
    data class Done(val categories: Int, val items: Int) : IptvLoadResult
    enum class Failure : IptvLoadResult { NOT_CONFIGURED, HTTP, UNREACHABLE, EMPTY, LOGIN }
}

/** Progress of a load: the step and how many entries are read so far. */
data class IptvLoadProgress(val step: Step, val count: Int) {
    enum class Step { CONNECT, LIVE, MOVIES, SERIES, M3U, SAVE }
}

/** When this device last agreed an IPTV login with the sync server. */
data class PlaylistRevision(val updatedMs: Long, val changeId: String, val dirty: Boolean)

@Singleton
class IptvPlaylistStore @Inject constructor(
    @ApplicationContext context: Context,
    private val secrets: SecretStore,
) {
    private val revisionPrefs = context.getSharedPreferences(REVISION_PREFS, Context.MODE_PRIVATE)
    private val _login = MutableStateFlow(read())
    val login: StateFlow<IptvPlaylistLogin> = _login

    /** Set while a server login is written locally, so that write is not uploaded again. */
    @Volatile var applyingRemote: Boolean = false

    fun revision(): PlaylistRevision? {
        val changeId = revisionPrefs.getString(REV_CHANGE, null) ?: return null
        return PlaylistRevision(revisionPrefs.getLong(REV_MS, 0L), changeId, revisionPrefs.getBoolean(REV_DIRTY, false))
    }

    fun markDirty(now: Long = System.currentTimeMillis()): PlaylistRevision {
        val changeId = "$now-${java.util.UUID.randomUUID()}"
        revisionPrefs.edit().putLong(REV_MS, now).putString(REV_CHANGE, changeId).putBoolean(REV_DIRTY, true).apply()
        return PlaylistRevision(now, changeId, true)
    }

    fun acceptRevision(updatedMs: Long, changeId: String) {
        revisionPrefs.edit().putLong(REV_MS, updatedMs).putString(REV_CHANGE, changeId).putBoolean(REV_DIRTY, false).apply()
    }

    fun clearDirty() {
        revisionPrefs.edit().putBoolean(REV_DIRTY, false).apply()
    }

    /** The wizard said no to this server revision, so a later sync does not take that same login. */
    fun isDeclined(changeId: String): Boolean =
        changeId.isNotBlank() && revisionPrefs.getString(DECLINED, null) == changeId

    fun decline(changeId: String) {
        if (changeId.isBlank()) return
        revisionPrefs.edit().putString(DECLINED, changeId).apply()
    }

    fun clearDecline() {
        revisionPrefs.edit().remove(DECLINED).apply()
    }

    private fun read() = IptvPlaylistLogin(
        secrets.read(M3U).orEmpty(), secrets.read(SERVER).orEmpty(), secrets.read(USER).orEmpty(), secrets.read(PASS).orEmpty(),
    )

    fun save(login: IptvPlaylistLogin) {
        secrets.write(M3U, login.m3uUrl.trim())
        secrets.write(SERVER, login.server.trim())
        secrets.write(USER, login.username.trim())
        secrets.write(PASS, login.password)
        _login.value = read()
    }

    /** When the playlist was last loaded into Room (ms), 0 if never. Not a secret, kept alongside. */
    val loadedAt: Long get() = secrets.read(LOADED).orEmpty().toLongOrNull() ?: 0L

    fun markLoaded(at: Long) = secrets.write(LOADED, at.toString())

    /** Playlists saved before catch-up was stored still have every channel at 0 days. */
    fun needsCatchupIndex(): Boolean = !revisionPrefs.getBoolean(CATCHUP_INDEX, false)

    fun markCatchupIndex() {
        revisionPrefs.edit().putBoolean(CATCHUP_INDEX, true).apply()
    }

    private companion object {
        const val REVISION_PREFS = "iptv_playlist_revision"
        const val REV_MS = "updated_ms"
        const val REV_CHANGE = "change_id"
        const val REV_DIRTY = "dirty"
        const val DECLINED = "declined_change_id"
        const val CATCHUP_INDEX = "catchup_v1"
        const val LOADED = "iptv.playlist.loaded_at"
        const val M3U = "iptv.playlist.m3u"
        const val SERVER = "iptv.playlist.server"
        const val USER = "iptv.playlist.user"
        const val PASS = "iptv.playlist.pass"
    }
}

/**
 * Loads the playlist into Room: everything is written under a staging source and swapped in
 * one short transaction at the end, so the IPTV page keeps the old list during a load and a
 * cancelled or failed load leaves it untouched. Responses are read as streams, inserts go in
 * chunks, and nothing of this runs on the main thread.
 */
@Singleton
class IptvPlaylistSync @Inject constructor(
    private val db: EnigmaDatabase,
    private val dao: IptvDao,
    private val store: IptvPlaylistStore,
) {
    private val gate = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    /** A login arrived from the server and the channel list still has to be fetched, after satellite sync. */
    @Volatile var loadAfterCache: Boolean = false

    fun arm() { loadAfterCache = true }

    fun hasLogin(kind: IptvPlaylistKind): Boolean = store.login.value.configured(kind)
    private val _lastLoad = MutableStateFlow(0)
    /** Goes up after every finished load, so pages reading Room reload. */
    val lastLoad: StateFlow<Int> = _lastLoad

    /**
     * Fetches the playlist when a server login is waiting, or when the daily refresh is due.
     * [allow] is false until setup has finished, so this does not run beside the wizard's bouquet and guide download.
     */
    suspend fun loadTail(kind: IptvPlaylistKind, allow: Boolean): IptvLoadResult? {
        if (!allow) return null
        val armed = loadAfterCache
        val indexCatchup = store.needsCatchupIndex()
        if (!armed && !indexCatchup && System.currentTimeMillis() - store.loadedAt < 24 * 60 * 60_000L) return null
        if (!store.login.value.configured(kind)) return null
        loadAfterCache = false
        val result = load(kind)
        if (armed && result !is IptvLoadResult.Done) loadAfterCache = true
        return result
    }

    suspend fun load(kind: IptvPlaylistKind, onProgress: (IptvLoadProgress) -> Unit = {}): IptvLoadResult = gate.withLock { loadBody(kind, onProgress) }

    private suspend fun loadBody(kind: IptvPlaylistKind, onProgress: (IptvLoadProgress) -> Unit): IptvLoadResult = withContext(Dispatchers.IO) {
        val login = store.login.value
        if (!login.configured(kind)) return@withContext IptvLoadResult.Failure.NOT_CONFIGURED
        val started = System.currentTimeMillis()
        dao.clearCategories(STAGING); dao.clearItems(STAGING)
        try {
            val counts = when (kind) {
                IptvPlaylistKind.M3U -> loadM3u(login.m3uUrl, onProgress)
                IptvPlaylistKind.XTREAM -> loadXtream(login.xtreamAccount()!!, onProgress)
            }
            if (counts.second == 0) {
                dao.clearCategories(STAGING); dao.clearItems(STAGING)
                return@withContext IptvLoadResult.Failure.EMPTY
            }
            onProgress(IptvLoadProgress(IptvLoadProgress.Step.SAVE, counts.second))
            db.withTransaction {
                dao.clearCategories(SOURCE); dao.clearItems(SOURCE)
                dao.moveCategories(STAGING, SOURCE); dao.moveItems(STAGING, SOURCE)
            }
            EnigmaLog.network("IPTV playlist ($kind) loaded: ${counts.first} categories, ${counts.second} entries in ${System.currentTimeMillis() - started} ms")
            store.markLoaded(System.currentTimeMillis())
            store.markCatchupIndex()
            _lastLoad.value++
            IptvLoadResult.Done(counts.first, counts.second)
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) { dao.clearCategories(STAGING); dao.clearItems(STAGING) }
            throw e
        } catch (e: HttpStatus) {
            dao.clearCategories(STAGING); dao.clearItems(STAGING)
            EnigmaLog.networkError("IPTV playlist load: HTTP ${e.code}")
            if (e.code == 401 || e.code == 403) IptvLoadResult.Failure.LOGIN else IptvLoadResult.Failure.HTTP
        } catch (e: IOException) {
            dao.clearCategories(STAGING); dao.clearItems(STAGING)
            EnigmaLog.networkError("IPTV playlist load: ${e.javaClass.simpleName}")
            IptvLoadResult.Failure.UNREACHABLE
        }
    }

    private class HttpStatus(val code: Int) : IOException("HTTP $code")

    private suspend fun <T> stream(url: String, read: suspend (InputStream) -> T): T {
        val request = Request.Builder().url(url).header("User-Agent", XtreamRepository.USER_AGENT).build()
        val call = client.newCall(request)
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw HttpStatus(response.code)
                return read(response.body.byteStream())
            }
        } finally {
            handle?.dispose()
        }
    }

    private suspend fun loadM3u(url: String, onProgress: (IptvLoadProgress) -> Unit): Pair<Int, Int> {
        onProgress(IptvLoadProgress(IptvLoadProgress.Step.CONNECT, 0))
        val categories = LinkedHashMap<Pair<XtreamSection, String>, String>()
        val batch = ArrayList<IptvItemRow>(CHUNK)
        var total = 0
        val outer = currentCoroutineContext()
        stream(url) { input ->
            input.bufferedReader().useLines { lines ->
                M3uPlaylist.parse(lines) { e ->
                    val group = e.group.ifBlank { OTHER }
                    val key = e.section to group
                    val catId = categories.getOrPut(key) { "g${categories.size}" }
                    total++
                    batch += IptvItemRow(STAGING, e.section.name, catId, "m$total", e.name, e.logo, e.url.substringAfterLast('.', "").take(5), e.number, e.url, total, e.archiveDays, e.catchupSource)
                    if (batch.size >= CHUNK) {
                        outer.ensureActive()
                        flushBlocking(batch)
                        onProgress(IptvLoadProgress(IptvLoadProgress.Step.M3U, total))
                    }
                }
            }
        }
        flush(batch)
        dao.insertCategories(categories.entries.mapIndexed { i, (key, id) -> IptvCategoryRow(STAGING, key.first.name, id, key.second, i) })
        return categories.size to total
    }

    /** M3U lines come from a plain callback; inserts stay on this IO thread. */
    private fun flushBlocking(batch: ArrayList<IptvItemRow>) = runBlocking { flush(batch) }

    private suspend fun flush(batch: ArrayList<IptvItemRow>) {
        currentCoroutineContext().ensureActive()
        if (batch.isEmpty()) return
        dao.insertItems(ArrayList(batch))
        batch.clear()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun loadXtream(account: XtreamAccount, onProgress: (IptvLoadProgress) -> Unit): Pair<Int, Int> {
        onProgress(IptvLoadProgress(IptvLoadProgress.Step.CONNECT, 0))
        var categories = 0
        var total = 0
        val sections = listOf(
            Triple(XtreamSection.LIVE, "get_live_categories", "get_live_streams") to IptvLoadProgress.Step.LIVE,
            Triple(XtreamSection.MOVIES, "get_vod_categories", "get_vod_streams") to IptvLoadProgress.Step.MOVIES,
            Triple(XtreamSection.SERIES, "get_series_categories", "get_series") to IptvLoadProgress.Step.SERIES,
        )
        for ((names, step) in sections) {
            val (section, catAction, itemAction) = names
            onProgress(IptvLoadProgress(step, total))
            val cats: List<XtreamCategory> = stream(XtreamUrls.api(account, catAction)) { input -> objects(input).mapNotNull(XtreamJson::category).toList() }
            dao.insertCategories(cats.mapIndexed { i, c -> IptvCategoryRow(STAGING, section.name, c.id, c.name, i) })
            categories += cats.size
            val batch = ArrayList<IptvItemRow>(CHUNK)
            stream(XtreamUrls.api(account, itemAction)) { input ->
                for (o in objects(input)) {
                    val item: XtreamItem = XtreamJson.item(o, section) ?: continue
                    total++
                    batch += IptvItemRow(STAGING, section.name, item.categoryId, item.id, item.name, item.icon, item.extension, item.number, "", total, item.archiveDays, item.catchupSource)
                    if (batch.size >= CHUNK) {
                        flush(batch)
                        onProgress(IptvLoadProgress(step, total))
                    }
                }
            }
            flush(batch)
        }
        return categories to total
    }

    /** A JSON array read element by element from the stream. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun objects(input: InputStream): Sequence<JsonObject> =
        Json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).mapNotNull { it as? JsonObject }

    /** Titles containing [query], at most [limit] per section; IO thread. */
    suspend fun search(query: String, limit: Int = com.enigmacast.domain.xtream.IptvSearch.LIMIT): List<XtreamItem> = withContext(Dispatchers.IO) {
        val pattern = com.enigmacast.domain.xtream.IptvSearch.likePattern(query)
        XtreamSection.entries.flatMap { section ->
            dao.search(SOURCE, section.name, pattern, limit).map { it.toItem(section) }
        }
    }

    suspend fun counts(section: XtreamSection): Map<String, Int> =
        dao.countsByCategory(SOURCE, section.name).associate { it.categoryId to it.n }

    suspend fun categories(section: XtreamSection): List<XtreamCategory> =
        dao.categories(SOURCE, section.name).map { XtreamCategory(it.id, it.name) }

    /** A whole category, read from Room in pages of [PAGE]. */
    suspend fun items(section: XtreamSection, categoryId: String): List<XtreamItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<XtreamItem>()
        var offset = 0
        while (true) {
            val page = dao.itemsPage(SOURCE, section.name, categoryId, PAGE, offset)
            page.mapTo(out) { it.toItem(section) }
            if (page.size < PAGE) break
            offset += PAGE
        }
        out
    }

    /** Background refresh (WorkManager): once a day in playlist mode. */
    suspend fun refreshIfDue(kind: IptvPlaylistKind, maxAgeMs: Long = 24 * 60 * 60_000L): IptvLoadResult? {
        if (!store.login.value.configured(kind)) return null
        if (System.currentTimeMillis() - store.loadedAt < maxAgeMs) return null
        return load(kind)
    }

    suspend fun count(): Int = dao.itemCount(SOURCE)

    private fun IptvItemRow.toItem(section: XtreamSection) =
        XtreamItem(id, name, section, categoryId, icon, extension, number, url, archiveDays, catchupSource)

    companion object {
        const val SOURCE = "playlist"
        const val STAGING = "playlist.new"
        const val OTHER = "—"
        private const val CHUNK = 1000
        private const val PAGE = 500
    }
}
