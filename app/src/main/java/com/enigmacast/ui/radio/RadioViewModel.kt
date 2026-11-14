package com.enigmacast.ui.radio

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.enigmacast.data.radio.RadioBrowserRepository
import com.enigmacast.domain.radio.RadioCategory
import com.enigmacast.domain.radio.RadioStation
import com.enigmacast.playback.RadioPlayer

import com.enigmacast.playback.RadioPlayerState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class RadioUiState(
    val query: String = "",
    val selectedCountry: RadioCategory? = null,
    val selectedTag: RadioCategory? = null,
    val countries: List<RadioCategory> = emptyList(),
    val tags: List<RadioCategory> = emptyList(),
    val favorites: List<RadioStation> = emptyList(),
    val recents: List<RadioStation> = emptyList(),
    val popular: List<RadioStation> = emptyList(),
    val topVoted: List<RadioStation> = emptyList(),
    val local: List<RadioStation> = emptyList(),
    val searchResults: List<RadioStation> = emptyList(),
    val isBrowseLoading: Boolean = true,
    val isSearching: Boolean = false,
    val error: String? = null,
    val nowPlaying: RadioStation? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackError: String? = null,
    val streamArtist: String? = null,
    val streamTitle: String? = null,
    val countryPinned: Boolean = false,
    val sleepTimerPresetMinutes: Int = 0,
    val sleepTimerEndsAtMs: Long = 0L
) {
    val isFilterActive: Boolean
        get() = query.isNotBlank() || selectedCountry != null || selectedTag != null

    val nowPlayingTrackLine: String?
        get() = when {
            !streamArtist.isNullOrBlank() && !streamTitle.isNullOrBlank() -> "$streamArtist · $streamTitle"
            !streamTitle.isNullOrBlank() -> streamTitle
            !streamArtist.isNullOrBlank() -> streamArtist
            else -> null
        }
}

@HiltViewModel
@OptIn(UnstableApi::class)
class RadioViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RadioBrowserRepository,
    private val playerManager: RadioPlayer
) : ViewModel() {

    private val prefs by lazy { context.getSharedPreferences("enigmacast_radio_prefs", Context.MODE_PRIVATE) }

    private val _uiState = MutableStateFlow(RadioUiState())
    val uiState: StateFlow<RadioUiState> = _uiState.asStateFlow()

    private val queryInput = MutableStateFlow("")
    private val countryInput = MutableStateFlow<RadioCategory?>(null)
    private val tagInput = MutableStateFlow<RadioCategory?>(null)
    private var favoriteIds: Set<String> = emptySet()
    private var countryPinned: Boolean = false
    private var sleepTimerJob: Job? = null

    init {
        restorePinnedCountry()
        observeLocalCollections()
        observePlayer()
        loadBrowse()
        observeSearch()
    }

    fun onQueryChange(value: String) {
        queryInput.value = value
        _uiState.update { it.copy(query = value) }
    }

    fun onCountrySelected(country: RadioCategory?) {
        countryInput.value = country
        _uiState.update { it.copy(selectedCountry = country) }
        if (countryPinned) {
            if (country == null) {
                setCountryPinned(false)
            } else {
                persistPinnedCountry(country)
            }
        }
    }

    fun toggleCountryPinned() {
        val country = countryInput.value ?: return
        setCountryPinned(!countryPinned, country)
    }

    fun onTagSelected(tag: RadioCategory?) {
        tagInput.value = tag
        _uiState.update { it.copy(selectedTag = tag) }
    }

    fun clearFilters() {
        queryInput.value = ""
        tagInput.value = null
        if (!countryPinned) {
            countryInput.value = null
        }
        _uiState.update {
            it.copy(
                query = "",
                selectedCountry = if (countryPinned) it.selectedCountry else null,
                selectedTag = null,
                searchResults = emptyList(),
                isSearching = false
            )
        }
    }

    fun playStation(station: RadioStation) {
        val current = _uiState.value
        if (current.nowPlaying?.stationUuid == station.stationUuid &&
            (playerManager.isCurrentlyPlaying() || current.isBuffering)
        ) {
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    nowPlaying = station.withFavoriteFlag(),
                    isBuffering = true,
                    playbackError = null,
                    streamArtist = null,
                    streamTitle = null
                )
            }
            val result = repository.resolvePlayUrl(station)
            val resolved = result.getOrNull()
            val url = resolved?.url
            if (url.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        isBuffering = false,
                        playbackError = result.exceptionOrNull()?.message
                    )
                }
                return@launch
            }
            repository.markRecent(station.copy(streamUrl = url))
            Log.d(
                RADIO_META_TAG,
                "playStation name=${station.name} hls=${station.hls} codec=${station.codec} bitrate=${station.bitrate} mime=${resolved.mimeType}"
            )
            playerManager.playStream(
                url = url,
                mimeType = resolved.mimeType
            )
        }
    }

    fun togglePlayPause() {
        val nowPlaying = _uiState.value.nowPlaying ?: return
        if (playerManager.isCurrentlyPlaying() || _uiState.value.isBuffering) {
            playerManager.togglePlayPause()
        } else {
            playStation(nowPlaying)
        }
    }

    fun toggleFavorite(station: RadioStation) {
        viewModelScope.launch {
            repository.toggleFavorite(station)
        }
    }

    fun playAdjacent(delta: Int) {
        if (delta == 0) return
        val state = _uiState.value
        val current = state.nowPlaying ?: return
        val queue = playbackQueue(state, current)
        if (queue.size < 2) return
        val index = queue.indexOfFirst { it.stationUuid == current.stationUuid }
        if (index < 0) return
        val next = queue[(index + delta).mod(queue.size)]
        playStation(next)
    }

    fun cycleSleepTimer() {
        val presets = RADIO_SLEEP_TIMER_PRESETS
        val currentIndex = presets.indexOf(_uiState.value.sleepTimerPresetMinutes)
            .takeIf { it >= 0 }
            ?: presets.lastIndex
        setSleepTimer(presets[(currentIndex + 1) % presets.size])
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val preset = minutes.coerceAtLeast(0)
        if (preset == 0) {
            _uiState.update { it.copy(sleepTimerPresetMinutes = 0, sleepTimerEndsAtMs = 0L) }
            return
        }
        val endsAt = System.currentTimeMillis() + (preset * 60_000L)
        _uiState.update {
            it.copy(sleepTimerPresetMinutes = preset, sleepTimerEndsAtMs = endsAt)
        }
        sleepTimerJob = viewModelScope.launch {
            val remaining = endsAt - System.currentTimeMillis()
            if (remaining > 0L) delay(remaining)
            if (_uiState.value.sleepTimerEndsAtMs != endsAt) return@launch
            _uiState.update { it.copy(sleepTimerPresetMinutes = 0, sleepTimerEndsAtMs = 0L) }
            playerManager.pausePlayback()
        }
    }

    fun retryBrowse() {
        loadBrowse()
    }

    private fun observeLocalCollections() {
        viewModelScope.launch {
            repository.observeFavoriteIds().collect { ids ->
                favoriteIds = ids
                _uiState.update { state ->
                    state.copy(
                        popular = state.popular.withFavoriteFlags(),
                        topVoted = state.topVoted.withFavoriteFlags(),
                        local = state.local.withFavoriteFlags(),
                        searchResults = state.searchResults.withFavoriteFlags(),
                        nowPlaying = state.nowPlaying?.withFavoriteFlag()
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeFavorites().collect { favorites ->
                _uiState.update { it.copy(favorites = favorites) }
            }
        }
        viewModelScope.launch {
            repository.observeRecent().collect { recents ->
                _uiState.update { it.copy(recents = recents) }
            }
        }
    }

    private fun observePlayer() {
        viewModelScope.launch {
            playerManager.isPlaying.collect { playing ->
                _uiState.update { it.copy(isPlaying = playing) }
            }
        }
        viewModelScope.launch {
            playerManager.streamNowPlaying.collect { info ->
                Log.d(
                    RADIO_META_TAG,
                    "uiCollect artist=${info.artist} title=${info.title} raw=${info.raw}"
                )
                _uiState.update {
                    it.copy(
                        streamArtist = info.artist,
                        streamTitle = info.title
                    )
                }
            }
        }
        viewModelScope.launch {
            playerManager.playerState.collect { state ->
                _uiState.update {
                    it.copy(
                        isBuffering = it.nowPlaying != null &&
                            (state is RadioPlayerState.Buffering || state is RadioPlayerState.Buffering),
                        playbackError = if (it.nowPlaying != null) {
                            (state as? RadioPlayerState.Error)?.message
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    private fun loadBrowse() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBrowseLoading = true, error = null) }
            val countriesResult = repository.getCountries()
            val tagsResult = repository.getTags()
            val popularResult = repository.getPopular()
            val topVotedResult = repository.getTopVoted()
            val countryCode = Locale.getDefault().country.takeIf { it.isNotBlank() }
            val localResult = if (countryCode != null) {
                repository.getLocalStations(countryCode)
            } else {
                Result.success(emptyList())
            }
            val browseFailed = popularResult.isFailure && topVotedResult.isFailure
            val countries = countriesResult.getOrDefault(emptyList())
            val restoredCountry = matchPinnedCountry(countries) ?: countryInput.value
            if (restoredCountry != countryInput.value) {
                countryInput.value = restoredCountry
            }
            _uiState.update {
                it.copy(
                    countries = countries,
                    tags = tagsResult.getOrDefault(emptyList()),
                    popular = popularResult.getOrDefault(emptyList()).withFavoriteFlags(),
                    topVoted = topVotedResult.getOrDefault(emptyList()).withFavoriteFlags(),
                    local = localResult.getOrDefault(emptyList()).withFavoriteFlags(),
                    selectedCountry = restoredCountry,
                    countryPinned = countryPinned,
                    isBrowseLoading = false,
                    error = if (browseFailed) "browse_failed" else null
                )
            }
        }
    }

    @kotlin.OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            combine(queryInput, countryInput, tagInput) { query, country, tag ->
                Triple(query.trim(), country, tag)
            }
                .debounce(400)
                .distinctUntilChanged()
                .collectLatest { (query, country, tag) ->
                    if (query.isBlank() && country == null && tag == null) {
                        _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
                        return@collectLatest
                    }
                    _uiState.update { it.copy(isSearching = true, error = null) }
                    val result = repository.searchStations(
                        name = query.takeIf { it.isNotBlank() },
                        country = country?.name,
                        countryCode = country?.code,
                        tag = tag?.name
                    )
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchResults = result.getOrDefault(emptyList()).withFavoriteFlags(),
                            error = result.exceptionOrNull()?.let { "search_failed" }
                        )
                    }
                }
        }
    }

    private fun restorePinnedCountry() {
        val code = prefs.getString(PREF_RADIO_PINNED_COUNTRY_CODE, null)?.takeIf { it.isNotBlank() }
        val name = prefs.getString(PREF_RADIO_PINNED_COUNTRY_NAME, null)?.takeIf { it.isNotBlank() }
        if (code == null && name == null) return
        val country = RadioCategory(name = name ?: code.orEmpty(), code = code)
        countryPinned = true
        countryInput.value = country
        _uiState.update { it.copy(selectedCountry = country, countryPinned = true) }
    }

    private fun matchPinnedCountry(countries: List<RadioCategory>): RadioCategory? {
        if (!countryPinned) return countryInput.value
        val current = countryInput.value ?: return null
        return countries.firstOrNull { option ->
            (!current.code.isNullOrBlank() && option.code.equals(current.code, ignoreCase = true)) ||
                option.name.equals(current.name, ignoreCase = true)
        } ?: current
    }

    private fun setCountryPinned(pinned: Boolean, country: RadioCategory? = countryInput.value) {
        countryPinned = pinned
        if (pinned && country != null) {
            persistPinnedCountry(country)
        } else {
            prefs.edit()
                .remove(PREF_RADIO_PINNED_COUNTRY_CODE)
                .remove(PREF_RADIO_PINNED_COUNTRY_NAME)
                .apply()
        }
        _uiState.update { it.copy(countryPinned = pinned) }
    }

    private fun persistPinnedCountry(country: RadioCategory) {
        prefs.edit()
            .putString(PREF_RADIO_PINNED_COUNTRY_CODE, country.code.orEmpty())
            .putString(PREF_RADIO_PINNED_COUNTRY_NAME, country.name)
            .apply()
    }

    private fun playbackQueue(state: RadioUiState, current: RadioStation): List<RadioStation> {
        val currentId = current.stationUuid
        fun usable(stations: List<RadioStation>): List<RadioStation>? =
            stations.takeIf { list ->
                list.size >= 2 && list.any { it.stationUuid == currentId }
            }
        return usable(state.favorites)
            ?: usable(state.recents)
            ?: usable(state.popular)
            ?: usable(state.topVoted)
            ?: usable(state.local)
            ?: emptyList()
    }

    private fun List<RadioStation>.withFavoriteFlags(): List<RadioStation> =
        map { it.withFavoriteFlag() }

    private fun RadioStation.withFavoriteFlag(): RadioStation =
        copy(isFavorite = stationUuid in favoriteIds)
}

private const val PREF_RADIO_PINNED_COUNTRY_CODE = "radio_pinned_country_code"
private const val PREF_RADIO_PINNED_COUNTRY_NAME = "radio_pinned_country_name"
private const val RADIO_META_TAG = "RadioMeta"
private val RADIO_SLEEP_TIMER_PRESETS = listOf(15, 30, 45, 60, 0)
