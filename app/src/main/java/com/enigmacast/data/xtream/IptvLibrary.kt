package com.enigmacast.data.xtream

import android.content.Context
import com.enigmacast.data.db.IptvLibraryDao
import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.data.db.SyncOutboxDao
import com.enigmacast.data.db.SyncOutboxRow
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.domain.profile.ProfileState
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

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
    /** Watch Next: play from the saved position, without the resume question. */
    val resume: Boolean = false,
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
    private val outbox: SyncOutboxDao,
    private val profiles: ProfileStore,
    private val trigger: com.enigmacast.data.enigmasync.SyncTrigger,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val favorites: StateFlow<List<IptvLibraryRow>> = combine(dao.favorites(), profiles.state) { rows, state ->
        rows.filter { it.profileId == state.activeUuid }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val resumes: StateFlow<List<IptvResumeRow>> = combine(dao.resumes(), profiles.state) { rows, state ->
        rows.filter { it.profileId == state.activeUuid }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val resumeMap: StateFlow<Map<String, IptvResumeRow>> = resumes.map { list -> list.associateBy { key(it.source, it.kind, it.id) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val favoriteKeys: StateFlow<Set<String>> = favorites.map { list -> list.map { key(it.source, it.kind, it.id) }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Library → IPTV page. */
    /** Set when the IPTV screen closes after a start from Home/Library: the card to focus again. */
    val returnTo = MutableStateFlow<String?>(null)
    val openRequest = MutableStateFlow<IptvOpenRequest?>(null)

    init { scope.launch { migratePrefs() } }

    fun saveResume(row: IptvResumeRow) {
        val profileId = profiles.state.value.activeUuid
        val stamped = row.copy(profileId = profileId)
        scope.launch {
            dao.putResume(stamped)
            enqueue(profileId, "progress", "upsert", stamped.updatedMs, progressPayload(stamped))
            trigger.nudge()
        }
    }

    suspend fun findResume(source: String, kind: String, id: String): com.enigmacast.data.db.IptvResumeRow? {
        val profileId = profiles.state.value.activeUuid
        return dao.resume(profileId, source, kind, id)
    }

    fun clearResume(source: String, kind: String, id: String) {
        val profileId = profiles.state.value.activeUuid
        val now = System.currentTimeMillis()
        scope.launch {
            dao.deleteResume(profileId, source, kind, id)
            enqueue(profileId, "progress", "delete", now, """{"source":${json(source)},"kind":${json(kind)},"id":${json(id)}}""")
            trigger.nudge()
        }
    }

    fun setFavorite(row: IptvLibraryRow, on: Boolean) {
        val profileId = profiles.state.value.activeUuid
        val stamped = row.copy(profileId = profileId)
        val now = System.currentTimeMillis()
        scope.launch {
            if (on) {
                dao.putFavorite(stamped)
                enqueue(profileId, "favorite", "upsert", now, favoritePayload(stamped))
            } else {
                dao.deleteFavorite(profileId, stamped.source, stamped.kind, stamped.id)
                enqueue(profileId, "favorite", "delete", now, """{"source":${json(stamped.source)},"kind":${json(stamped.kind)},"id":${json(stamped.id)}}""")
            }
            trigger.nudge()
        }
    }

    private suspend fun enqueue(profileId: String, entity: String, op: String, updatedMs: Long, payload: String) {
        outbox.put(SyncOutboxRow(UUID.randomUUID().toString(), profileId, entity, op, payload, updatedMs, ""))
    }

    private suspend fun migratePrefs() {
        val prefs = context.getSharedPreferences("iptv_resume", Context.MODE_PRIVATE)
        val rows = IptvResumeMigration.rows(prefs.all, System.currentTimeMillis())
        if (rows.isEmpty()) return
        val profileId = ProfileState.LEGACY_UUID
        rows.forEach { row ->
            val stamped = row.copy(profileId = profileId)
            if (dao.resume(profileId, stamped.source, stamped.kind, stamped.id) == null) dao.putResume(stamped)
        }
        prefs.edit().clear().apply()
    }

    private fun json(value: String) = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun favoritePayload(row: IptvLibraryRow) =
        """{"source":${json(row.source)},"kind":${json(row.kind)},"id":${json(row.id)},"title":${json(row.title)},"addedMs":${row.addedMs}}"""

    private fun progressPayload(row: IptvResumeRow) =
        """{"source":${json(row.source)},"kind":${json(row.kind)},"id":${json(row.id)},"positionMs":${row.positionMs},"durationMs":${row.durationMs},"updatedMs":${row.updatedMs},"title":${json(row.title)},"poster":${json(row.poster.orEmpty())},"extension":${json(row.extension)},"url":${json(row.url)},"categoryId":${json(row.categoryId)},"seriesId":${json(row.seriesId.orEmpty())},"seriesTitle":${json(row.seriesTitle.orEmpty())},"season":${row.season},"episode":${row.episode}}"""

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
