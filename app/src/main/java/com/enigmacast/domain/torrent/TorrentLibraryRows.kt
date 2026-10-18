package com.enigmacast.domain.torrent

import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow

/** What a periodic or final save does with the playing title's resume position. */
enum class ResumeSave { NONE, KEEP, CLEAR }

/** What Library or Home asks the Torrent page to open: the info page, at a remembered episode. */
data class TorrentOpenRequest(
    val title: TmdbTitle,
    val season: Int? = null,
    val episode: Int? = null,
    /** "home/<card key>" or "<row>/<card key>": Back from the info page returns there. */
    val origin: String? = null,
    val token: Long = System.nanoTime(),
)

/**
 * Torrent favorites and Continue watching share the IPTV library tables (and their sync):
 * source "torrent", kind m (film) / s (series favorite) / e (episode), id = TMDB id
 * (episodes "<tv id>:<season>:<episode>"). Older apps keep such rows but never show them,
 * because "torrent" is not one of their playlist accounts.
 */
object TorrentLibraryRows {
    const val SOURCE = "torrent"

    fun kindOf(kind: TorrentKind) = if (kind == TorrentKind.TV) "s" else "m"

    fun favoriteRow(title: TmdbTitle, nowMs: Long) = IptvLibraryRow(
        source = SOURCE, kind = kindOf(title.kind), id = title.id.toString(),
        title = title.title, poster = title.poster, backdrop = title.backdrop, year = title.year,
        categoryId = "", extension = "", url = "", addedMs = nowMs,
    )

    fun titleOf(row: IptvLibraryRow): TmdbTitle? {
        val id = row.id.toIntOrNull() ?: return null
        val kind = if (row.kind == "s") TorrentKind.TV else TorrentKind.MOVIE
        return TmdbTitle(id, kind, row.title, row.year, row.poster, row.backdrop, "")
    }

    /** Resume key of a film ("m", TMDB id) or an episode ("e", "<tv id>:<season>:<episode>"). */
    fun resumeKind(season: Int?) = if (season != null) "e" else "m"
    fun resumeId(tmdbId: Int, season: Int?, episode: Int?) = if (season != null) "$tmdbId:$season:${episode ?: 0}" else tmdbId.toString()

    fun resumeRow(
        title: TmdbTitle,
        season: Int?,
        episode: Int?,
        episodeName: String?,
        positionMs: Long,
        durationMs: Long,
        nowMs: Long,
    ) = IptvResumeRow(
        source = SOURCE, kind = resumeKind(season), id = resumeId(title.id, season, episode),
        positionMs = positionMs, durationMs = durationMs, updatedMs = nowMs,
        title = if (season != null) episodeName?.takeIf { it.isNotBlank() } ?: "S${season}E${episode ?: 0}" else title.title,
        poster = title.poster, extension = "", url = "", categoryId = "",
        seriesId = if (season != null) title.id.toString() else null,
        seriesTitle = if (season != null) title.title else null,
        season = season ?: 0, episode = episode ?: 0,
    )

    /** The info page to open for a Continue watching row; series rows carry their episode. */
    fun requestOf(row: IptvResumeRow, year: Int? = null): TorrentOpenRequest? = when (row.kind) {
        "e" -> row.seriesId?.toIntOrNull()?.let { id ->
            TorrentOpenRequest(
                TmdbTitle(id, TorrentKind.TV, row.seriesTitle ?: row.title, year, row.poster, null, ""),
                row.season.takeIf { it > 0 }, row.episode.takeIf { it > 0 },
            )
        }
        "m" -> row.id.toIntOrNull()?.let { TorrentOpenRequest(TmdbTitle(it, TorrentKind.MOVIE, row.title, year, row.poster, null, "")) }
        else -> null
    }

    /**
     * Nothing is written before the saved place was offered (the new stream is still buffering at
     * 0:00 and would wipe it) or while the question is open. Then a place past the first minute
     * is kept; one at the end (watched) is cleared; an early stop leaves the saved place alone.
     */
    fun saveAction(offered: Boolean, asking: Boolean, positionMs: Long, durationMs: Long): ResumeSave = when {
        !offered || asking -> ResumeSave.NONE
        durationMs > 0 && com.enigmacast.domain.resume.ResumePolicy.watched(positionMs, durationMs) -> ResumeSave.CLEAR
        positionMs >= com.enigmacast.domain.resume.ResumePolicy.MIN_RESUME_MS -> ResumeSave.KEEP
        else -> ResumeSave.NONE
    }

    /** The latest saved episode of a series (Play on its info page starts there). */
    fun latestEpisode(rows: Collection<IptvResumeRow>, tvId: Int): IptvResumeRow? = rows
        .filter { it.source == SOURCE && it.kind == "e" && it.seriesId == tvId.toString() }
        .maxByOrNull { it.updatedMs }
}
