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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _providers = MutableStateFlow(readProviders())
    val providers: StateFlow<List<IptvProvider>> = _providers
    private val _login = MutableStateFlow(_providers.value.firstOrNull { it.enabled && it.configured() }?.login ?: IptvPlaylistLogin())
    val login: StateFlow<IptvPlaylistLogin> = _login

    init {
        if (_providers.value.isEmpty()) migrateLegacy()
    }

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

    private fun readProviders(): List<IptvProvider> {
        val raw = secrets.read(PROVIDERS).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<IptvProvider>>(raw) }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() }
            .sortedBy { it.sortOrder }
    }

    /** Existing single-playlist users keep their login as provider "default". */
    private fun migrateLegacy() {
        val legacy = read()
        if (!legacy.m3uUrl.startsWith("http", true) && legacy.server.isBlank()) return
        val kind = if (legacy.server.isNotBlank()) IptvPlaylistKind.XTREAM else IptvPlaylistKind.M3U
        val provider = IptvProvider(
            id = "default",
            name = if (kind == IptvPlaylistKind.M3U) "M3U" else "Xtream",
            kind = kind,
            m3uUrl = legacy.m3uUrl,
            server = legacy.server,
            username = legacy.username,
            password = legacy.password,
            enabled = true,
        )
        persistProviders(listOf(provider))
    }

    private fun persistProviders(list: List<IptvProvider>) {
        val ordered = list.sortedBy { it.sortOrder }
        _providers.value = ordered
        secrets.write(PROVIDERS, json.encodeToString(ordered))
        if (ordered.isEmpty()) {
            // Do not let a deleted provider come back through the old single-login fallback.
            secrets.write(M3U, null); secrets.write(SERVER, null); secrets.write(USER, null); secrets.write(PASS, null)
        }
        _login.value = ordered.firstOrNull { it.enabled && it.configured() }?.login ?: IptvPlaylistLogin()
    }

    fun provider(id: String): IptvProvider? = _providers.value.firstOrNull { it.id == id }

    fun enabledProviders(): List<IptvProvider> = _providers.value.filter { it.enabled && it.configured() }

    /** Adds or updates one provider. A blank id creates a new one. */
    fun saveProvider(provider: IptvProvider): IptvProvider {
        if (!provider.configured()) error("provider is not configured")
        val withId = if (provider.id.isBlank()) {
            provider.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = provider.displayName(),
                sortOrder = (_providers.value.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            )
        } else {
            provider.copy(name = provider.displayName())
        }
        val list = _providers.value.toMutableList()
        val index = list.indexOfFirst { it.id == withId.id }
        if (index >= 0) list[index] = withId else list += withId
        persistProviders(list)
        return withId
    }

    fun deleteProvider(id: String) = persistProviders(_providers.value.filterNot { it.id == id })

    fun moveProvider(id: String, delta: Int) {
        val list = _providers.value.sortedBy { it.sortOrder }.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in list.indices) return
        val item = list.removeAt(index)
        list.add(target, item)
        persistProviders(list.mapIndexed { i, provider -> provider.copy(sortOrder = i) })
    }

    fun setProviderEnabled(id: String, enabled: Boolean) {
        persistProviders(_providers.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /** A JSON document for backup/import. Contains credentials, so the UI warns before copying. */
    fun providersJson(): String = json.encodeToString(_providers.value)

    /** Merges providers from [raw] by id; returns how many usable rows were imported. */
    fun importProviders(raw: String): Int {
        val incoming = runCatching { json.decodeFromString<List<IptvProvider>>(raw) }.getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && it.configured() }
        if (incoming.isEmpty()) return 0
        val merged = _providers.value.toMutableList()
        incoming.forEach { provider ->
            val index = merged.indexOfFirst { it.id == provider.id }
            if (index >= 0) merged[index] = provider else merged += provider
        }
        persistProviders(merged)
        return incoming.size
    }

    /** Used by the sync server: replace the whole list without writing legacy single-login fields. */
    fun replaceProviders(list: List<IptvProvider>) {
        persistProviders(list.filter { it.id.isNotBlank() })
    }

    fun save(login: IptvPlaylistLogin) {
        secrets.write(M3U, login.m3uUrl.trim())
        secrets.write(SERVER, login.server.trim())
        secrets.write(USER, login.username.trim())
        secrets.write(PASS, login.password)
        val existing = _providers.value.firstOrNull()
        if (existing == null) {
            val kind = if (login.server.isNotBlank()) IptvPlaylistKind.XTREAM else IptvPlaylistKind.M3U
            saveProvider(
                IptvProvider(
                    id = "default",
                    name = if (kind == IptvPlaylistKind.M3U) "M3U" else "Xtream",
                    kind = kind,
                    m3uUrl = login.m3uUrl,
                    server = login.server,
                    username = login.username,
                    password = login.password,
                    enabled = true,
                ),
            )
        } else {
            saveProvider(existing.copy(m3uUrl = login.m3uUrl, server = login.server, username = login.username, password = login.password))
        }
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
        const val PROVIDERS = "iptv.providers.v1"
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
    private val activity: com.enigmacast.activity.BackgroundActivityTracker,
) {
    private val gate = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    /** A login arrived from the server and the channel list still has to be fetched, after satellite sync. */
    @Volatile var loadAfterCache: Boolean = false

    fun arm() { loadAfterCache = true }

    fun hasLogin(kind: IptvPlaylistKind): Boolean =
        store.providers.value.any { it.enabled && it.configured() && it.kind == kind } || store.login.value.configured(kind)
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
        if (store.enabledProviders().isEmpty() && !store.login.value.configured(kind)) return null
        loadAfterCache = false
        val result = load(kind)
        if (armed && result !is IptvLoadResult.Done) loadAfterCache = true
        return result
    }

    suspend fun load(kind: IptvPlaylistKind, onProgress: (IptvLoadProgress) -> Unit = {}): IptvLoadResult = gate.withLock {
        val configured = if (store.providers.value.isNotEmpty()) {
            store.enabledProviders()
        } else {
            listOfNotNull(legacyProvider(kind))
        }
        if (configured.isEmpty()) return@withLock IptvLoadResult.Failure.NOT_CONFIGURED
        val task = activity.start(com.enigmacast.activity.ActivityKind.IPTV)
        var failed = true
        try {
            var categories = 0
            var items = 0
            var lastFailure: IptvLoadResult.Failure? = null
            for (provider in configured) {
                when (val result = loadProvider(provider) { progress ->
                    activity.progress(task, progress.count, null)
                    onProgress(progress)
                }) {
                    is IptvLoadResult.Done -> {
                        categories += result.categories
                        items += result.items
                    }
                    is IptvLoadResult.Failure -> lastFailure = result
                }
            }
            // Old single-provider rows are no longer used once a provider list exists.
            if (store.providers.value.isNotEmpty()) {
                dao.clearCategories(SOURCE)
                dao.clearItems(SOURCE)
            }
            if (items == 0) {
                failed = lastFailure != null && lastFailure != IptvLoadResult.Failure.NOT_CONFIGURED
                lastFailure ?: IptvLoadResult.Failure.EMPTY
            } else {
                failed = false
                store.markLoaded(System.currentTimeMillis())
                store.markCatchupIndex()
                _lastLoad.value++
                IptvLoadResult.Done(categories, items)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            failed = false
            throw cancel
        } finally {
            activity.finish(task, failed)
        }
    }

    /** A pre-multi-provider login, loaded under the old "playlist" source. */
    private fun legacyProvider(kind: IptvPlaylistKind): IptvProvider? {
        val login = store.login.value
        if (!login.configured(kind)) return null
        return IptvProvider(
            id = "legacy",
            name = "IPTV",
            kind = kind,
            m3uUrl = login.m3uUrl,
            server = login.server,
            username = login.username,
            password = login.password,
            enabled = true,
        )
    }

    private fun sourceOf(provider: IptvProvider): String =
        if (provider.id == "legacy") SOURCE else provider.sourceKey

    private suspend fun loadProvider(provider: IptvProvider, onProgress: (IptvLoadProgress) -> Unit): IptvLoadResult =
        withContext(Dispatchers.IO) {
            if (!provider.configured()) return@withContext IptvLoadResult.Failure.NOT_CONFIGURED
            val source = sourceOf(provider)
            val staging = "$source.new"
            val started = System.currentTimeMillis()
            dao.clearCategories(staging); dao.clearItems(staging)
            try {
                val counts = when (provider.kind) {
                    IptvPlaylistKind.M3U -> loadM3u(provider.m3uUrl, staging, provider, onProgress)
                    IptvPlaylistKind.XTREAM -> loadXtream(
                        provider.login.xtreamAccount()!!, staging, provider, onProgress,
                    )
                }
                if (counts.second == 0) {
                    dao.clearCategories(staging); dao.clearItems(staging)
                    return@withContext IptvLoadResult.Failure.EMPTY
                }
                onProgress(IptvLoadProgress(IptvLoadProgress.Step.SAVE, counts.second))
                db.withTransaction {
                    dao.clearCategories(source); dao.clearItems(source)
                    dao.moveCategories(staging, source); dao.moveItems(staging, source)
                }
                EnigmaLog.network("IPTV provider ${provider.id} (${provider.kind}) loaded: ${counts.first} categories, ${counts.second} entries in ${System.currentTimeMillis() - started} ms")
                IptvLoadResult.Done(counts.first, counts.second)
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) { dao.clearCategories(staging); dao.clearItems(staging) }
                throw e
            } catch (e: HttpStatus) {
                dao.clearCategories(staging); dao.clearItems(staging)
                EnigmaLog.networkError("IPTV provider load: HTTP ${e.code}")
                if (e.code == 401 || e.code == 403) IptvLoadResult.Failure.LOGIN else IptvLoadResult.Failure.HTTP
            } catch (e: IOException) {
                dao.clearCategories(staging); dao.clearItems(staging)
                EnigmaLog.networkError("IPTV provider load: ${e.javaClass.simpleName}")
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

    private suspend fun loadM3u(
        url: String,
        source: String,
        provider: IptvProvider,
        onProgress: (IptvLoadProgress) -> Unit,
    ): Pair<Int, Int> {
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
                    batch += IptvItemRow(
                        source, e.section.name, catId, "m$total", e.name, e.logo,
                        e.url.substringAfterLast('.', "").take(5), e.number, e.url, total,
                        e.archiveDays, e.catchupSource,
                    )
                    if (batch.size >= CHUNK) {
                        outer.ensureActive()
                        flushBlocking(batch)
                        onProgress(IptvLoadProgress(IptvLoadProgress.Step.M3U, total))
                    }
                }
            }
        }
        flush(batch)
        dao.insertCategories(categories.entries.mapIndexed { i, (key, id) -> IptvCategoryRow(source, key.first.name, id, key.second, i) })
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
    private suspend fun loadXtream(
        account: XtreamAccount,
        source: String,
        provider: IptvProvider,
        onProgress: (IptvLoadProgress) -> Unit,
    ): Pair<Int, Int> {
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
            dao.insertCategories(cats.mapIndexed { i, c -> IptvCategoryRow(source, section.name, c.id, c.name, i) })
            categories += cats.size
            val batch = ArrayList<IptvItemRow>(CHUNK)
            stream(XtreamUrls.api(account, itemAction)) { input ->
                for (o in objects(input)) {
                    val item: XtreamItem = XtreamJson.item(o, section) ?: continue
                    total++
                    batch += IptvItemRow(
                        source, section.name, item.categoryId, item.id, item.name, item.icon,
                        item.extension, item.number, "", total, item.archiveDays, item.catchupSource,
                    )
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
        val sources = readProviders().map { it.id to it.displayName() }
        sources.flatMap { (providerId, providerName) ->
            val source = sourceOf(providerId)
            XtreamSection.entries.flatMap { section ->
                dao.search(source, section.name, pattern, limit).map { it.toItem(section, providerId, providerName) }
            }
        }
    }

    suspend fun counts(section: XtreamSection): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        readProviders().forEach { provider ->
            dao.countsByCategory(sourceOf(provider.id), section.name)
                .forEach { out[it.categoryId] = (out[it.categoryId] ?: 0) + it.n }
        }
        return out
    }

    suspend fun categories(section: XtreamSection, providerId: String? = null): List<XtreamCategory> =
        readProviders()
            .filter { providerId == null || it.id == providerId }
            .flatMap { provider ->
                dao.categories(sourceOf(provider.id), section.name).map {
                    XtreamCategory(it.id, it.name, provider.id, provider.displayName())
                }
            }

    /** A whole category, read from Room in pages of [PAGE]. */
    suspend fun items(section: XtreamSection, categoryId: String, providerId: String = ""): List<XtreamItem> = withContext(Dispatchers.IO) {
        val source = sourceOf(providerId)
        val providerName = store.provider(providerId)?.displayName().orEmpty()
        val out = ArrayList<XtreamItem>()
        var offset = 0
        while (true) {
            val page = dao.itemsPage(source, section.name, categoryId, PAGE, offset)
            page.mapTo(out) { it.toItem(section, providerId, providerName) }
            if (page.size < PAGE) break
            offset += PAGE
        }
        out
    }

    /** Every live channel in the loaded playlist, for matching an equivalent channel. */
    suspend fun liveItems(): List<XtreamItem> = withContext(Dispatchers.IO) {
        readProviders().flatMap { provider ->
            dao.allItems(sourceOf(provider.id), XtreamSection.LIVE.name)
                .map { it.toItem(XtreamSection.LIVE, provider.id, provider.displayName()) }
        }
    }

    /** Background refresh (WorkManager): once a day in playlist mode. */
    suspend fun refreshIfDue(kind: IptvPlaylistKind, maxAgeMs: Long = 24 * 60 * 60_000L): IptvLoadResult? {
        if (store.enabledProviders().isEmpty() && !store.login.value.configured(kind)) return null
        if (System.currentTimeMillis() - store.loadedAt < maxAgeMs) return null
        return load(kind)
    }

    suspend fun count(): Int = readProviders().sumOf { dao.itemCount(sourceOf(it.id)) }

    suspend fun countProvider(providerId: String): Int = withContext(Dispatchers.IO) {
        dao.itemCount(sourceOf(providerId))
    }

    suspend fun clearProvider(providerId: String) = withContext(Dispatchers.IO) {
        val source = sourceOf(providerId)
        dao.clearCategories(source)
        dao.clearItems(source)
        _lastLoad.value++
    }

    /** Providers that have loaded rows; the legacy source is shown only when no provider exists. */
    private fun readProviders(): List<IptvProvider> {
        val all = store.providers.value
        if (all.isNotEmpty()) return all.filter { it.enabled && it.configured() }
        return listOfNotNull(legacyProvider(store.login.value.let {
            if (it.server.isNotBlank()) IptvPlaylistKind.XTREAM else IptvPlaylistKind.M3U
        }))
    }

    private fun sourceOf(providerId: String): String =
        if (providerId == "legacy") SOURCE
        else if (providerId.isBlank()) SOURCE
        else IptvProvider.sourceKey(providerId)

    private fun IptvItemRow.toItem(section: XtreamSection, providerId: String, providerName: String) =
        XtreamItem(
            id, name, section, categoryId, icon, extension, number, url, archiveDays, catchupSource,
            providerId = providerId,
            providerName = providerName,
        )

    companion object {
        const val SOURCE = "playlist"
        const val STAGING = "playlist.new"
        const val OTHER = "—"
        private const val CHUNK = 1000
        private const val PAGE = 500
    }
}
