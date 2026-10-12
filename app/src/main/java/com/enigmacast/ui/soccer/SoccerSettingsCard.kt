package com.enigmacast.ui.soccer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.enigmacast.R
import com.enigmacast.data.soccer.SoccerRepository
import com.enigmacast.data.soccer.SoccerSettings
import com.enigmacast.domain.soccer.SoccerCountries
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.ToggleRow
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.ValueSetting
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SoccerSettingsViewModel @Inject constructor(
    private val repository: SoccerRepository,
) : ViewModel() {
    val settings: SoccerSettings get() = repository.settings
    val available: StateFlow<Set<String>?> = repository.available().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { runCatching { repository.refreshCompetitions() } }
    }

    fun setCountry(id: String) {
        settings.setCountry(id)
        viewModelScope.launch { runCatching { repository.refresh(SoccerCountries.byId(id).competitions, force = false) } }
    }

    private var keyJob: Job? = null

    fun setKey(key: String) {
        if (key.trim() == settings.values.value.apiKey) return
        settings.setApiKey(key)
        keyJob?.cancel()
        keyJob = viewModelScope.launch {
            delay(1_500)
            runCatching { repository.refresh(SoccerCountries.byId(settings.values.value.country).competitions, force = true) }
        }
    }
}

/** Settings › General › Soccer: country to follow (exactly one), refresh, live scores and an own API key. */
@Composable
fun SoccerSettingsCard(model: SoccerSettingsViewModel = hiltViewModel()) {
    val values by model.settings.values.collectAsStateWithLifecycle()
    val available by model.available.collectAsStateWithLifecycle()
    val countries = SoccerCountries.offered(available)
    val labels = countries.map { c -> countryName(c.id) + "  (" + c.competitions.joinToString(", ") + ")" }
    val refresh = SoccerSettings.REFRESH_CHOICES
    val refreshLabels = refresh.map {
        when {
            it == 0 -> stringResource(R.string.soccer_refresh_manual)
            it < 60 -> stringResource(R.string.soccer_refresh_minutes, it)
            else -> stringResource(R.string.soccer_refresh_hours, it / 60)
        }
    }
    SettingsCard(stringResource(R.string.settings_soccer)) {
        ValueSetting(
            title = stringResource(R.string.soccer_country),
            description = stringResource(R.string.soccer_country_desc),
            options = labels,
            selectedIndex = countries.indexOfFirst { it.id == values.country }.coerceAtLeast(0),
            onSelect = { model.setCountry(countries[it].id) },
        )
        ValueSetting(
            title = stringResource(R.string.soccer_refresh),
            description = stringResource(R.string.soccer_refresh_desc),
            options = refreshLabels,
            selectedIndex = refresh.indexOf(values.refreshMinutes).coerceAtLeast(0),
            onSelect = { model.settings.setRefreshMinutes(refresh[it]) },
        )
        ToggleRow(
            title = stringResource(R.string.soccer_live_updates),
            description = stringResource(R.string.soccer_live_updates_desc),
            checked = values.liveUpdates,
            onCheckedChange = { model.settings.setLiveUpdates(it) },
        )
        TvTextField(
            label = stringResource(R.string.soccer_api_key),
            value = values.apiKey,
            onValueChange = model::setKey,
        )
    }
}
