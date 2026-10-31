package com.enigmacast.ui.person

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.tmdb.TmdbHttp
import com.enigmacast.data.tmdb.TmdbJson
import com.enigmacast.domain.account.PremiumFeature
import com.enigmacast.domain.films.CountryResolver
import com.enigmacast.domain.films.TmdbCastMember
import com.enigmacast.domain.films.TmdbCredit
import com.enigmacast.domain.films.TmdbPerson
import com.enigmacast.domain.torrent.TmdbTitle
import com.enigmacast.domain.torrent.TorrentKind
import com.enigmacast.domain.torrent.TorrentOpenRequest
import com.enigmacast.live.CountryDetector
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ActorState {
    data object Loading : ActorState
    data object Failed : ActorState
    data class Ready(val person: TmdbPerson) : ActorState
}

/** The actor page of a cast member: TMDB person (app language, English biography fallback) and filmography. */
@HiltViewModel
class ActorViewModel @Inject constructor(
    private val tmdb: TmdbHttp,
    private val country: CountryDetector,
    private val torrents: com.enigmacast.data.torrent.TorrentLibrary,
    private val scraper: com.enigmacast.data.torrent.ScraperRepository,
    private val gate: com.enigmacast.live.PremiumGate,
) : ViewModel() {
    var state by mutableStateOf<ActorState>(ActorState.Loading)
        private set
    private var job: Job? = null
    private var loadedFor: String? = null

    fun load(member: TmdbCastMember) {
        val key = "${member.id}:${member.name}"
        if (key == loadedFor && state !is ActorState.Failed) return
        loadedFor = key
        state = ActorState.Loading
        job?.cancel()
        job = viewModelScope.launch {
            state = runCatching { fetch(member) }.getOrNull()?.let { ActorState.Ready(it) } ?: ActorState.Failed
        }
    }

    private suspend fun fetch(member: TmdbCastMember): TmdbPerson? {
        val cc = runCatching { country.country() }.getOrDefault("NL")
        val language = CountryResolver.tmdbLanguage(country.appLanguage(), cc)
        val id = member.id.takeIf { it > 0 }
            ?: tmdb.searchPerson(member.name, language)?.let { TmdbJson.parsePersonSearch(it, member.name) }
            ?: return null
        val person = tmdb.person(id, language)?.let(TmdbJson::parsePerson) ?: return null
        EnigmaLog.network("actor page person=$id credits=${person.credits.size}")
        if (person.biography.isNotBlank() || language.startsWith("en")) return person
        // No translation of the biography: TMDB's English one (the rest stays in the app language).
        val english = tmdb.person(id, "en-US")?.let(TmdbJson::parsePerson)?.biography.orEmpty()
        return person.copy(biography = english)
    }

    /** Torrent page available (valid scraper and Premium): the title can open there. */
    fun torrentsAllowed(): Boolean = scraper.status.value is com.enigmacast.data.torrent.ScraperStatus.Valid &&
        gate.allows(PremiumFeature.TORRENT)

    /** Opens the title's Torrent info page (its sources); Back there returns to the Torrent page. */
    fun openTorrent(credit: TmdbCredit, beforeOpen: (TmdbTitle) -> Unit = {}, onTorrent: () -> Unit) {
        if (!gate.require(PremiumFeature.TORRENT)) return
        val title = TmdbTitle(
            id = credit.id,
            kind = if (credit.isSeries) TorrentKind.TV else TorrentKind.MOVIE,
            title = credit.title,
            year = credit.year,
            poster = TmdbJson.image(credit.posterPath, "w342"),
            backdrop = TmdbJson.image(credit.backdropPath, "w1280"),
            overview = credit.overview,
            rating = credit.rating,
        )
        beforeOpen(title)
        torrents.openRequest.value = TorrentOpenRequest(title)
        onTorrent()
    }
}
