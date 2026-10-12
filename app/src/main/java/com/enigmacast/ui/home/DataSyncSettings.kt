package com.enigmacast.ui.home

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.enigmacast.R
import com.enigmacast.data.db.SyncStateRow
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.tmdb.SyncSettings
import com.enigmacast.domain.sync.SyncKind
import com.enigmacast.domain.sync.SyncPolicy
import com.enigmacast.live.HomeFilmsRepository
import com.enigmacast.sync.SyncProgress
import com.enigmacast.ui.theme.ValueSetting
import com.enigmacast.ui.theme.InfoRow
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.TvButton
import com.enigmacast.util.asString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DataSyncViewModel @Inject constructor(
    private val repository: HomeFilmsRepository,
    private val settingsRepository: SettingsRepository,
    val syncSettings: SyncSettings,
) : ViewModel() {
    val progress: StateFlow<SyncProgress?> = repository.progress

    val states: StateFlow<List<SyncStateRow>> = settingsRepository.settings
        .map { it.activeReceiver() }
        .flatMapLatest { receiver -> if (receiver == null) flowOf(emptyList()) else repository.syncState(repository.receiverKey(receiver)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun cacheBytes(): Long = withContext(Dispatchers.IO) { repository.cacheBytes() }

    fun refreshNow() = repository.syncNow(force = true)

    fun clearCache(done: () -> Unit) {
        viewModelScope.launch {
            repository.clearCache()
            done()
            repository.syncNow(force = true)
        }
    }
}

/** Settings › General › Data & sync. */
@Composable
fun DataSyncSettingsCard(model: DataSyncViewModel = hiltViewModel()) {
    val values by model.syncSettings.values.collectAsStateWithLifecycle()
    val states by model.states.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var bytes by androidx.compose.runtime.remember { mutableLongStateOf(0L) }
    var refreshKey by androidx.compose.runtime.remember { mutableStateOf(0) }
    LaunchedEffect(refreshKey, states, progress == null) { bytes = model.cacheBytes() }

    val perDay = SyncPolicy.CHOICES
    val perDayLabels = perDay.map { if (it == SyncPolicy.OFF) stringResource(R.string.sync_off) else stringResource(R.string.sync_times_per_day, it) }
    SettingsCard(stringResource(R.string.settings_data_sync)) {
        ValueSetting(
            title = stringResource(R.string.sync_channels),
            description = stringResource(R.string.sync_channels_desc),
            options = perDayLabels,
            selectedIndex = perDay.indexOf(values.channelsPerDay).coerceAtLeast(0),
            onSelect = { model.syncSettings.setChannels(perDay[it]) },
        )
        ValueSetting(
            title = stringResource(R.string.sync_epg),
            description = stringResource(R.string.sync_epg_desc),
            options = perDayLabels,
            selectedIndex = perDay.indexOf(values.epgPerDay).coerceAtLeast(0),
            onSelect = { model.syncSettings.setEpg(perDay[it]) },
        )
        val filmChoices = listOf(SyncPolicy.FOLLOW_EPG) + perDay
        ValueSetting(
            title = stringResource(R.string.sync_films),
            description = stringResource(R.string.sync_films_desc),
            options = listOf(stringResource(R.string.sync_follow_epg)) + perDayLabels,
            selectedIndex = filmChoices.indexOf(values.filmsPerDay).coerceAtLeast(0),
            onSelect = { model.syncSettings.setFilms(filmChoices[it]) },
        )
        ValueSetting(
            title = stringResource(R.string.sync_epg_days),
            options = listOf(stringResource(R.string.sync_days_today), stringResource(R.string.sync_days_two), stringResource(R.string.sync_days_three)),
            selectedIndex = (values.epgDays - 1).coerceIn(0, 2),
            onSelect = { model.syncSettings.setEpgDays(it + 1) },
        )
        fun last(kind: SyncKind): String {
            val row = states.firstOrNull { it.kind == kind.name } ?: return context.getString(R.string.sync_never)
            val whenText = if (row.lastSuccessMs > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(row.lastSuccessMs)) else context.getString(R.string.sync_never)
            return if (row.error.isNotBlank() && row.lastAttemptMs > row.lastSuccessMs) context.getString(R.string.sync_failed, whenText) else whenText
        }
        InfoRow(stringResource(R.string.sync_last_channels), last(SyncKind.CHANNELS))
        InfoRow(stringResource(R.string.sync_last_epg), last(SyncKind.EPG))
        InfoRow(stringResource(R.string.sync_last_films), last(SyncKind.FILMS))
        InfoRow(stringResource(R.string.sync_db_size), Formatter.formatShortFileSize(context, bytes))
        progress?.let { InfoRow(stringResource(R.string.sync_running), stringResource(R.string.sync_preparing, it.percent) + "  " + it.step.asString()) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
            TvButton(stringResource(R.string.sync_refresh_now), { model.refreshNow() })
            TvButton(stringResource(R.string.sync_clear_cache), { model.clearCache { refreshKey++ } })
        }
    }
}
