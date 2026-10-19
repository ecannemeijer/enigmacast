package com.enigmacast.ui.soccer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SoccerSettingsViewModel @Inject constructor(
    private val repository: SoccerRepository,
) : ViewModel() {
    val settings: SoccerSettings get() = repository.settings

    private var keyJob: Job? = null

    fun setKey(key: String) {
        if (key.trim() == settings.values.value.apiKey) return
        settings.setApiKey(key)
        keyJob?.cancel()
        keyJob = viewModelScope.launch {
            delay(1_500)
            runCatching { repository.refresh(SoccerCountries.byId(settings.values.value.region).competitions, force = true) }
        }
    }
}

/** Settings › General › Sport: refresh, live scores and an own API key. The region is chosen on the Sport page itself. */
@Composable
fun SoccerSettingsCard(model: SoccerSettingsViewModel = hiltViewModel()) {
    val values by model.settings.values.collectAsStateWithLifecycle()
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
        // The own API key is for the few who have one: behind Advanced, open when a key is set.
        var advanced by rememberSaveable { mutableStateOf(false) }
        ToggleRow(
            title = stringResource(R.string.soccer_advanced),
            description = stringResource(R.string.soccer_advanced_desc),
            checked = advanced || values.apiKey.isNotBlank(),
            onCheckedChange = { advanced = it },
        )
        if (advanced || values.apiKey.isNotBlank()) {
            TvTextField(
                label = stringResource(R.string.soccer_api_key),
                value = values.apiKey,
                onValueChange = model::setKey,
            )
        }
    }
}
