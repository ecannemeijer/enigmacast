package com.enigmacast.data.tmdb

import com.enigmacast.data.xtream.IptvArtwork
import com.enigmacast.data.xtream.IptvVod
import com.enigmacast.domain.films.CountryResolver
import com.enigmacast.domain.player.VodTitleCard
import com.enigmacast.domain.player.VodTitleInfo
import com.enigmacast.domain.torrent.TorrentTmdb
import com.enigmacast.domain.xtream.XtreamEpisode
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.domain.xtream.XtreamSection
import com.enigmacast.domain.xtream.XtreamVodInfo
import com.enigmacast.live.CountryDetector
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fills the OSD title block for a film or episode: TMDB logo, episode name and plot in the app
 * language (English plot when TMDB has none in that language), else the playlist's own data.
 * Uses the existing TMDB key (settings or BuildConfig); runs on IO and never throws.
 */
@Singleton
class VodTitleSource @Inject constructor(
    private val tmdb: TmdbHttp,
    private val artwork: IptvArtwork,
    private val country: CountryDetector,
) {
    /** An IPTV film ([episode] null) or episode of [item]. [playlist] is get_vod_info / get_series_info. */
    suspend fun iptv(item: XtreamItem?, episode: XtreamEpisode?, playlist: XtreamVodInfo?, fallbackTitle: String): VodTitleInfo =
        withContext(Dispatchers.IO) {
            val name = item?.name?.let { IptvVod.title(it).first }?.ifBlank { null } ?: fallbackTitle
            val base = VodTitleInfo(
                title = name,
                season = episode?.season,
                episode = episode?.episode,
                episodeName = VodTitleCard.episodeName(episode?.title, name, episode?.id.orEmpty()),
                overview = VodTitleCard.overview(if (episode != null) episode.plot else playlist?.plot),
            )
            if (item == null) return@withContext base
            val tv = item.section == XtreamSection.SERIES
            val id = playlist?.tmdbId?.takeIf { it > 0 } ?: try {
                artwork.resolve(listOf(item))
                artwork.movies.value[IptvVod.key(item)]?.id?.let { kotlin.math.abs(it) }?.takeIf { it > 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (id == null) base else tmdb(tv, id, base)
        }

    /** TMDB film [id] or series [id] (season/episode from [base]); [base] fills what TMDB lacks. */
    suspend fun tmdb(tv: Boolean, id: Int, base: VodTitleInfo, logoUrl: String? = null): VodTitleInfo = withContext(Dispatchers.IO) {
        try {
            val cc = runCatching { country.country() }.getOrDefault("NL")
            val language = CountryResolver.tmdbLanguage(country.appLanguage(), cc)
            val needBody = logoUrl == null || ((!tv || !base.isEpisode) && base.overview.isBlank())
            val body = if (needBody) (if (tv) tmdb.tv(id, language) else tmdb.movie(id, language)) else null
            val parsed = body?.let { if (tv) TmdbJson.parseTv(it, cc, language) else TmdbJson.parseMovie(it, cc, language) }
            val logo = logoUrl ?: parsed?.logoPath?.let { TmdbJson.image(it, "w500") }
            val title = parsed?.title?.ifBlank { null } ?: base.title
            if (!tv || !base.isEpisode) {
                return@withContext base.copy(title = title, logoUrl = logo, overview = VodTitleCard.overview(parsed?.overview, base.overview))
            }
            val season = base.season ?: 0
            val number = base.episode ?: 0
            val local = TorrentTmdb.parseEpisodes(tmdb.season(id, season, language)).firstOrNull { it.number == number }
            val english = if (local?.overview.isNullOrBlank() && !language.startsWith("en")) {
                TorrentTmdb.parseEpisodes(tmdb.season(id, season, "en-US")).firstOrNull { it.number == number }
            } else null
            base.copy(
                title = title,
                logoUrl = logo,
                episodeName = VodTitleCard.preferEpisodeName(local?.name, base.episodeName, number),
                overview = VodTitleCard.overview(local?.overview, base.overview, english?.overview),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            base
        }
    }
}
