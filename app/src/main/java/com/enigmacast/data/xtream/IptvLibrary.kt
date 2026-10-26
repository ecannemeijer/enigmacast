package com.enigmacast.data.xtream

import android.content.Context
import com.enigmacast.data.db.IptvLibraryDao
import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.data.db.SyncOutboxDao
import com.enigmacast.data.db.SyncOutboxRow
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.domain.profile.ProfileState
import com.enigmacast.domain.sync.LibrarySync
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
import kotlinx.coroutines.flow.first
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
    private val tombstones: LibraryTombstones,
    private val watchNext: WatchNextPublisher,
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
    /** Watch history of the active viewer by "<source>/<kind>/<id>" (episode bars, watched marks). */
    val history: StateFlow<Map<String, com.enigmacast.data.db.EpisodeHistoryRow>> = combine(dao.histories(), profiles.state) { rows, state ->
        rows.filter { it.profileId == state.activeUuid }.associateBy { key(it.source, it.kind, it.id) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val favoriteKeys: StateFlow<Set<String>> = favorites.map { list -> list.map { key(it.source, it.kind, it.id) }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Library → IPTV page. */
    /** Set when the IPTV screen closes after a start from Home/Library: the card to focus again. */
    val returnTo = MutableStateFlow<String?>(null)
    val openRequest = MutableStateFlow<IptvOpenRequest?>(null)

    init {
        scope.launch {
            migratePrefs()
            // Ook zonder geopende Companion-brug opruimen, zodat Verder kijken nooit
            // meerdere afleveringen van dezelfde serie houdt.
            keepLatestEpisodePerSeries()
        }
    }

    /** Verwijdert oude demo-/loopback-items uit de IPTV-bibliotheek (en uit de sync). */
    fun removeDemoEntries() {
        val profileId = profiles.state.value.activeUuid
        scope.launch {
            dao.favorites().first()
                .filter { it.profileId == profileId && isDemoUrl(it.url) }
                .forEach { setFavorite(it, false) }
            dao.resumes().first()
                .filter { it.profileId == profileId && isDemoUrl(it.url) }
                .forEach { clearResume(it.source, it.kind, it.id) }
        }
    }

    /** Houdt per serie alleen de nieuwste aflevering in Verder kijken; de rest gaat ook uit de sync. */
    fun keepLatestEpisodePerSeries() {
        scope.launch {
            val rows = dao.resumes().first()
                .filter { it.kind == "e" && seriesKeyOf(it) != null }
            val now = System.currentTimeMillis()
            rows.groupBy { Triple(it.profileId, it.source, seriesKeyOf(it)) }.values.forEach { group ->
                group.sortedByDescending { it.updatedMs }.drop(1).forEach { row ->
                    deleteResume(row.profileId, row.source, row.kind, row.id, now)
                }
            }
            trigger.nudge()
        }
    }

    /**
     * Phone progress carries no seriesId, but its title does contain the series name.
     * Fall back to the title prefix so those rows are cleaned up too.
     */
    private fun seriesKeyOf(row: IptvResumeRow): String? {
        row.seriesId?.takeIf { it.isNotBlank() }?.let { return it }
        row.seriesTitle?.takeIf { it.isNotBlank() }?.let { return it }
        val marker = SERIES_MARKER.find(row.title) ?: return null
        return row.title.substring(0, marker.range.first).takeIf { it.isNotBlank() }
    }

    private suspend fun trimSeries(profileId: String, source: String, seriesKey: String) {
        dao.resumes().first()
            .filter {
                it.profileId == profileId && it.kind == "e" &&
                    it.source == source && seriesKeyOf(it) == seriesKey
            }
            .sortedByDescending { it.updatedMs }
            .drop(1)
            .forEach { clearResume(it.source, it.kind, it.id) }
    }

    private fun isDemoUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase().orEmpty()
        return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "0.0.0.0"
    }

    fun saveResume(row: IptvResumeRow) {
        val profileId = profiles.state.value.activeUuid
        val stamped = row.copy(profileId = profileId)
        scope.launch {
            dao.putResume(stamped)
            tombstones.clear(profileId, LibrarySync.PROGRESS, key(stamped.source, stamped.kind, stamped.id))
            enqueue(profileId, LibrarySync.PROGRESS, "upsert", stamped.updatedMs, LibrarySync.progressPayload(stamped))
            trigger.nudge()
            if (stamped.kind == "e") {
                seriesKeyOf(stamped)?.let { trimSeries(profileId, stamped.source, it) }
            }
        }
    }

    suspend fun findResume(source: String, kind: String, id: String): com.enigmacast.data.db.IptvResumeRow? {
        val profileId = profiles.state.value.activeUuid
        return dao.resume(profileId, source, kind, id)
    }

    /**
     * Adds a play to the watch history (any position from the first second, or watched). History
     * is separate from Continue watching: removing a card or its tombstones never touches it.
     */
    fun recordHistory(source: String, kind: String, id: String, seriesId: String?, positionMs: Long, durationMs: Long) {
        if (!com.enigmacast.domain.resume.EpisodeHistory.worthKeeping(positionMs, durationMs)) return
        val profileId = profiles.state.value.activeUuid
        val entry = com.enigmacast.domain.resume.EpisodeHistory.entry(source, kind, id, seriesId, positionMs, durationMs, System.currentTimeMillis(), profileId)
        scope.launch {
            val current = dao.history(profileId, source, kind, id)
            val merged = com.enigmacast.domain.resume.EpisodeHistory.merge(current, entry)
            if (merged == current) return@launch
            dao.putHistory(merged)
            enqueue(profileId, LibrarySync.HISTORY, "upsert", merged.updatedMs, LibrarySync.historyPayload(merged))
            trigger.nudge()
        }
    }

    fun clearResume(source: String, kind: String, id: String) {
        val profileId = profiles.state.value.activeUuid
        val now = System.currentTimeMillis()
        scope.launch {
            deleteResume(profileId, source, kind, id, now)
            trigger.nudge()
        }
    }

    /**
     * Removes a Continue watching card. A series card stands for every saved episode of that
     * series (the newest one is shown), so all of them go: otherwise an older episode, often one
     * watched on another device, took the card's place and the series seemed to come back.
     */
    fun removeContinue(source: String, kind: String, id: String) {
        val profileId = profiles.state.value.activeUuid
        val now = System.currentTimeMillis()
        scope.launch {
            val rows = dao.resumes().first().filter { it.profileId == profileId }
            val targets = com.enigmacast.domain.xtream.IptvLibraryRows.continueGroup(rows, source, kind, id)
            targets.forEach { (k, i) -> deleteResume(profileId, source, k, i, now) }
            trigger.nudge()
        }
    }

    /**
     * Hotel cleanup: Continue watching of [profileUuid] goes (also on the sync server, through
     * tombstones, so it does not come back), and its watch history is removed on this TV.
     * Rows of any other profile are never touched.
     */
    suspend fun clearProfileHistory(profileUuid: String): Int {
        val now = System.currentTimeMillis()
        val rows = com.enigmacast.domain.hotel.HotelCleanupPlan.rowsOfHotel(dao.resumes().first(), profileUuid) { it.profileId }
        rows.forEach { deleteResume(profileUuid, it.source, it.kind, it.id, now) }
        if (profileUuid.isNotBlank()) dao.deleteHistoryFor(profileUuid)
        if (rows.isNotEmpty()) trigger.nudge()
        return rows.size
    }

    private suspend fun deleteResume(profileId: String, source: String, kind: String, id: String, now: Long) {
        dao.deleteResume(profileId, source, kind, id)
        tombstones.put(profileId, LibrarySync.PROGRESS, key(source, kind, id), now)
        enqueue(profileId, LibrarySync.PROGRESS, "delete", now, LibrarySync.deletePayload(source, kind, id))
        watchNext.remove(source, kind, id)
    }

    fun setFavorite(row: IptvLibraryRow, on: Boolean) {
        val profileId = profiles.state.value.activeUuid
        val stamped = row.copy(profileId = profileId)
        val now = System.currentTimeMillis()
        scope.launch {
            if (on) {
                dao.putFavorite(stamped)
                tombstones.clear(profileId, LibrarySync.FAVORITE, key(stamped.source, stamped.kind, stamped.id))
                enqueue(profileId, LibrarySync.FAVORITE, "upsert", now, LibrarySync.favoritePayload(stamped))
            } else {
                dao.deleteFavorite(profileId, stamped.source, stamped.kind, stamped.id)
                tombstones.put(profileId, LibrarySync.FAVORITE, key(stamped.source, stamped.kind, stamped.id), now)
                enqueue(profileId, LibrarySync.FAVORITE, "delete", now, LibrarySync.deletePayload(stamped.source, stamped.kind, stamped.id))
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

    companion object {
        private val SERIES_MARKER = Regex(" - S\\d+E\\d+")
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
