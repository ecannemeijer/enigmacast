package com.enigmacast.data.xtream

import android.content.Context
import com.enigmacast.data.db.IptvLibraryDao
import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamSection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What Library or Search asks the IPTV page to open. */
/** [origin] = where it was started ("home/<card key>" or "<row>/<card key>" in Library), to return there afterwards. */
/** [detail]: a film or series opens its info page instead of playing (Search, Library info). Live items always play. */
/** [episode]: a series from Continue watching plays this episode. */
data class IptvOpenRequest(
    val source: String,
    val item: XtreamItem,
    val origin: String? = null,
    val detail: Boolean = false,
    val episode: com.enigmacast.domain.xtream.XtreamEpisode? = null,
    val token: Long = System.nanoTime(),
)

/**
 * IPTV favorites and resume positions in the user database (Library rows work offline).
 * Keys are "<source>/<kind>/<id>" with source = the playlist account key, kind m (film),
 * s (series, favorites) or e (episode, resume). Positions saved by older versions in
 * preferences are copied in once.
 */
@Singleton
class IptvLibrary @Inject constructor(
    private val dao: IptvLibraryDao,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val favorites: StateFlow<List<IptvLibraryRow>> = dao.favorites().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val resumes: StateFlow<List<IptvResumeRow>> = dao.resumes().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val resumeMap: StateFlow<Map<String, IptvResumeRow>> = resumes.map { list -> list.associateBy { key(it.source, it.kind, it.id) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val favoriteKeys: StateFlow<Set<String>> = favorites.map { list -> list.map { key(it.source, it.kind, it.id) }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Library → IPTV page. */
    /** Set when the IPTV screen closes after a start from Home/Library: the card to focus again. */
    val returnTo = MutableStateFlow<String?>(null)
    val openRequest = MutableStateFlow<IptvOpenRequest?>(null)

    init { scope.launch { migratePrefs() } }

    fun saveResume(row: IptvResumeRow) { scope.launch { dao.putResume(row) } }
    fun clearResume(source: String, kind: String, id: String) { scope.launch { dao.deleteResume(source, kind, id) } }

    fun setFavorite(row: IptvLibraryRow, on: Boolean) {
        scope.launch { if (on) dao.putFavorite(row) else dao.deleteFavorite(row.source, row.kind, row.id) }
    }

    private suspend fun migratePrefs() {
        val prefs = context.getSharedPreferences("iptv_resume", Context.MODE_PRIVATE)
        val rows = IptvResumeMigration.rows(prefs.all, System.currentTimeMillis())
        if (rows.isEmpty()) return
        rows.forEach { if (dao.resume(it.source, it.kind, it.id) == null) dao.putResume(it) }
        prefs.edit().clear().apply()
    }

    companion object {
        fun key(source: String, kind: String, id: String) = "$source/$kind/$id"
        fun kindOf(section: XtreamSection) = if (section == XtreamSection.SERIES) "s" else "m"
    }
}

/** Old preference entries "<source>/<m|e>/<id>" = position and "…/d" = length, as rows. */
object IptvResumeMigration {
    fun rows(all: Map<String, *>, nowMs: Long): List<IptvResumeRow> = all.entries.mapNotNull { (key, value) ->
        if (key.endsWith("/d")) return@mapNotNull null
        val position = (value as? Long) ?: return@mapNotNull null
        val parts = key.split('/')
        if (parts.size != 3 || parts[1] !in setOf("m", "e")) return@mapNotNull null
        val duration = (all["$key/d"] as? Long) ?: 0L
        IptvResumeRow(parts[0], parts[1], parts[2], position, duration, nowMs, "", null, "", "", "", null, null, 0, 0)
    }
}
