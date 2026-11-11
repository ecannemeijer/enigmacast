package com.enigmacast.data.xtream

import android.content.Context
import com.enigmacast.domain.model.IptvPlaylistKind
import com.enigmacast.domain.xtream.XtreamAccountInfo
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Cached Xtream user_info per provider, so Settings can show expiry without a fetch every time. */
@Singleton
class IptvAccountStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val xtream: XtreamRepository,
    private val providers: IptvPlaylistStore,
) {
    private val gate = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val _info = MutableStateFlow<Map<String, XtreamAccountInfo>>(emptyMap())
    val info: StateFlow<Map<String, XtreamAccountInfo>> = _info.asStateFlow()

    init {
        val loaded = runCatching { readFile() }.getOrDefault(emptyMap())
        if (loaded.isNotEmpty()) _info.value = loaded
    }

    suspend fun refreshAll(force: Boolean = false) {
        val list = providers.providers.value.filter {
            it.enabled && it.kind == IptvPlaylistKind.XTREAM && it.configured()
        }
        list.forEach { refreshOne(it, force) }
        val keep = providers.providers.value.map { it.id }.toSet()
        if (_info.value.keys.any { it !in keep }) {
            gate.withLock {
                _info.value = _info.value.filterKeys { it in keep }
                writeFile(_info.value)
            }
        }
    }

    suspend fun refreshOne(provider: IptvProvider, force: Boolean = false): XtreamAccountInfo? {
        if (provider.kind != IptvPlaylistKind.XTREAM) return null
        val account = provider.account() ?: return null
        val cached = _info.value[provider.id]
        val now = System.currentTimeMillis()
        if (!force && cached != null && now - cached.fetchedAtMs < TTL_MS) return cached
        val fresh = runCatching { xtream.userInfo(account) }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            EnigmaLog.networkError("Xtream user_info " + provider.id + ": " + error.javaClass.simpleName)
            null
        } ?: return cached
        gate.withLock {
            _info.value = _info.value + (provider.id to fresh)
            writeFile(_info.value)
        }
        return fresh
    }

    private fun file(): File = File(context.cacheDir, "iptv-account-info.json")

    private fun readFile(): Map<String, XtreamAccountInfo> {
        val f = file()
        if (!f.isFile) return emptyMap()
        return json.decodeFromString<Map<String, XtreamAccountInfo>>(f.readText())
    }

    private fun writeFile(map: Map<String, XtreamAccountInfo>) {
        runCatching { file().writeText(json.encodeToString(map)) }
    }

    companion object {
        const val TTL_MS = 15 * 60_000L
        const val DEFAULT_WARN_DAYS = 7
    }
}
