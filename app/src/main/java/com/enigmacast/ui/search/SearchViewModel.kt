package com.enigmacast.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.search.SearchHit
import com.enigmacast.domain.search.SearchKind
import com.enigmacast.domain.search.SearchQuery
import com.enigmacast.domain.search.SearchResults
import com.enigmacast.live.ProgrammeSearch
import com.enigmacast.live.RecordingBook
import com.enigmacast.live.SearchOutcome
import com.enigmacast.live.SearchPlayer
import com.enigmacast.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val programmeSearch: ProgrammeSearch,
    private val player: SearchPlayer,
    private val recordings: RecordingBook,
) : ViewModel() {
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf(SearchResults())
        private set
    var searching by mutableStateOf(false)
        private set
    var message by mutableStateOf(UiText.Empty)
        private set
    private var searchJob: Job? = null

    fun onQuery(value: String, immediate: Boolean = false) {
        query = value
        message = UiText.Empty
        searchJob?.cancel()
        val nowSec = System.currentTimeMillis() / 1000
        if (!SearchQuery.parse(value, nowSec).isSearchable) {
            results = SearchResults()
            searching = false
            return
        }
        searching = true
        searchJob = viewModelScope.launch {
            if (!immediate) delay(DEBOUNCE_MS)
            val found = programmeSearch.search(value, recordings.items.value, System.currentTimeMillis() / 1000)
            results = found
            searching = false
        }
    }

    fun applyExternalQuery(
        value: String,
        playFirst: Boolean,
        onPlayed: () -> Unit,
        onBouquet: (BouquetEntry) -> Unit,
        prepare: () -> Unit = {},
    ) {
        onQuery(value, immediate = true)
        if (!playFirst) return
        viewModelScope.launch {
            searchJob?.join()
            val hit = results.playHit(SearchQuery.parse(value, System.currentTimeMillis() / 1000).text.ifBlank { value })
                ?: return@launch
            choose(hit, onPlayed, onBouquet, prepare)
        }
    }

    fun choose(
        hit: SearchHit,
        onPlayed: () -> Unit,
        onBouquet: (BouquetEntry) -> Unit,
        prepare: () -> Unit = {},
    ) {
        message = UiText.Empty
        viewModelScope.launch {
            prepare()
            when (val outcome = player.open(hit)) {
                SearchOutcome.Played -> onPlayed()
                is SearchOutcome.Bouquet -> onBouquet(outcome.bouquet)
                is SearchOutcome.Failed -> message = outcome.text
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}

/** Section title for a non-empty group, in the order the list shows them. */
fun SearchResults.sections(): List<Pair<SearchKind, List<SearchHit>>> = listOf(
    SearchKind.NOW to now,
    SearchKind.LATER to later,
    SearchKind.RECORDING to recordings,
    SearchKind.CHANNEL to stations,
).filter { it.second.isNotEmpty() }
