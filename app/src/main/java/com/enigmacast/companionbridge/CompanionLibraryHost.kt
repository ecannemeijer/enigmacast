package com.enigmacast.companionbridge

import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.data.xtream.IptvLibrary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Deelbare IPTV-bibliotheek van het actieve profiel: favorieten en kijkposities.
 * De telefoon leest dit als `state.library` en schrijft wijzigingen terug met
 * `cmd.library`; de TV gebruikt dezelfde [IptvLibrary] als zijn eigen Library.
 */
@Singleton
class CompanionLibraryHost @Inject constructor(
    private val library: IptvLibrary,
) {
    data class Entry(
        val title: String,
        val url: String,
        val source: String,
        val kind: String,
        val id: String,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val favorite: Boolean = false,
        val updatedMs: Long = 0,
    )

    data class State(
        val favorites: List<IptvLibraryRow> = emptyList(),
        val resumes: List<IptvResumeRow> = emptyList(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state: StateFlow<State> = combine(library.favorites, library.resumes) { favorites, resumes ->
        State(favorites, resumes)
    }.stateIn(scope, SharingStarted.Eagerly, State())

    /** Returns false for an unknown action or an incomplete entry. */
    fun apply(action: String, entry: Entry): Boolean {
        if (entry.source.isBlank() || entry.kind.isBlank() || entry.id.isBlank()) return false
        return when (action) {
            "FAVORITE" -> {
                library.setFavorite(
                    IptvLibraryRow(
                        source = entry.source,
                        kind = entry.kind,
                        id = entry.id,
                        title = entry.title,
                        poster = null,
                        backdrop = null,
                        year = null,
                        categoryId = "",
                        extension = "",
                        url = entry.url,
                        addedMs = entry.updatedMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    ),
                    entry.favorite,
                )
                true
            }
            "PROGRESS" -> {
                library.saveResume(
                    IptvResumeRow(
                        source = entry.source,
                        kind = entry.kind,
                        id = entry.id,
                        positionMs = entry.positionMs.coerceAtLeast(0),
                        durationMs = entry.durationMs.coerceAtLeast(0),
                        updatedMs = entry.updatedMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        title = entry.title,
                        poster = null,
                        extension = "",
                        url = entry.url,
                        categoryId = "",
                        seriesId = null,
                        seriesTitle = null,
                        season = 0,
                        episode = 0,
                    ),
                )
                true
            }
            else -> false
        }
    }
}
