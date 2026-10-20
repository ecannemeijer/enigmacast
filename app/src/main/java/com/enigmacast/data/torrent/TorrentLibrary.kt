package com.enigmacast.data.torrent

import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.data.xtream.IptvLibrary
import com.enigmacast.domain.torrent.TmdbTitle
import com.enigmacast.domain.torrent.TorrentLibraryRows
import com.enigmacast.domain.torrent.TorrentOpenRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Torrent favorites and resume positions: rows of the user database's IPTV library with
 * source "torrent", so they are per profile and synced like IPTV films.
 */
@Singleton
class TorrentLibrary @Inject constructor(private val library: IptvLibrary) {
    val favorites: StateFlow<List<IptvLibraryRow>> get() = library.favorites
    val resumes: StateFlow<List<IptvResumeRow>> get() = library.resumes

    /** Library / Home → Torrent page. */
    val openRequest = MutableStateFlow<TorrentOpenRequest?>(null)

    fun isFavorite(title: TmdbTitle): Boolean =
        IptvLibrary.key(TorrentLibraryRows.SOURCE, TorrentLibraryRows.kindOf(title.kind), title.id.toString()) in library.favoriteKeys.value

    fun setFavorite(title: TmdbTitle, on: Boolean) =
        library.setFavorite(TorrentLibraryRows.favoriteRow(title, System.currentTimeMillis()), on)

    fun favorite(kind: String, id: String): IptvLibraryRow? =
        library.favorites.value.firstOrNull { it.source == TorrentLibraryRows.SOURCE && it.kind == kind && it.id == id }

    fun resume(kind: String, id: String): IptvResumeRow? =
        library.resumeMap.value[IptvLibrary.key(TorrentLibraryRows.SOURCE, kind, id)]

    fun latestEpisode(tvId: Int): IptvResumeRow? = TorrentLibraryRows.latestEpisode(library.resumes.value, tvId)

    fun saveResume(row: IptvResumeRow) = library.saveResume(row)

    fun clearResume(kind: String, id: String) = library.clearResume(TorrentLibraryRows.SOURCE, kind, id)

    /** Every saved episode of the series of this row (the series left Continue watching). */
    fun removeSeries(kind: String, id: String) = library.removeContinue(TorrentLibraryRows.SOURCE, kind, id)
}
