package com.enigmacast.domain.resume

import com.enigmacast.domain.torrent.TmdbSeason
import com.enigmacast.domain.xtream.NextEpisode
import com.enigmacast.domain.xtream.XtreamEpisode

/** What finishing an episode does to the series' Continue watching card. */
sealed interface EpisodeDone {
    /** The card moves to this episode ("next" state, or its own progress when it has some). */
    data class Next(val season: Int, val episode: Int, val id: String? = null) : EpisodeDone
    /** The last episode of the last known season: the card goes. */
    data object SeriesDone : EpisodeDone
    /** The episode list is not known: keep the card where it is rather than lose the series. */
    data object Unknown : EpisodeDone
}

/**
 * A series stays in Continue watching until every episode of every season has been watched.
 * Before this, finishing any episode cleared its row, and the series left Continue watching.
 */
object SeriesContinue {
    fun afterIptv(current: XtreamEpisode, episodes: List<XtreamEpisode>): EpisodeDone {
        if (episodes.none { it.id == current.id }) return EpisodeDone.Unknown
        val next = NextEpisode.after(current, episodes) ?: return EpisodeDone.SeriesDone
        return EpisodeDone.Next(next.season, next.episode, next.id)
    }

    /** TMDB seasons (specials excluded): the next episode in the season, else the next season's first. */
    fun afterTorrent(seasons: List<TmdbSeason>, season: Int, episode: Int): EpisodeDone {
        val known = seasons.filter { it.number > 0 && it.episodes > 0 }
        val current = known.firstOrNull { it.number == season } ?: return EpisodeDone.Unknown
        if (episode < current.episodes) return EpisodeDone.Next(season, episode + 1)
        val following = known.filter { it.number > season }.minByOrNull { it.number } ?: return EpisodeDone.SeriesDone
        return EpisodeDone.Next(following.number, 1)
    }

    /** A saved row of the next episode is left as it is (its own progress, or already "next"). */
    fun keepNext(existing: Any?): Boolean = existing != null

    /** A Continue watching row that stands for an episode not started yet. */
    fun isUpNext(kind: String, positionMs: Long): Boolean = kind == "e" && positionMs < ResumePolicy.MIN_RESUME_MS
}
