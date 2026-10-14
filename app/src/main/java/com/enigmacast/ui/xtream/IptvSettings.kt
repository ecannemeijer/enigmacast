package com.enigmacast.ui.xtream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.xtream.IptvLoadProgress
import com.enigmacast.data.xtream.IptvLoadResult
import com.enigmacast.data.xtream.IptvPlaylistLogin
import com.enigmacast.data.xtream.IptvPlaylistStore
import com.enigmacast.data.xtream.IptvPlaylistSync
import com.enigmacast.domain.model.IptvPlaylistKind
import com.enigmacast.domain.model.IptvSourceMode
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.ui.theme.ChoiceRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.TvButton
import com.enigmacast.ui.theme.TvFieldKind
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.skinShape
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The load dialog: progress while [busy], then the result or the error. */
data class IptvLoadState(val busy: Boolean, val progress: IptvLoadProgress? = null, val result: IptvLoadResult? = null)

@HiltViewModel
class IptvSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val store: IptvPlaylistStore,
    private val sync: IptvPlaylistSync,
) : ViewModel() {
    val mode: StateFlow<Pair<IptvSourceMode, IptvPlaylistKind>> = settingsRepository.settings
        .map { it.iptvSource to it.iptvPlaylistKind }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IptvSourceMode.XSTREAMITY to IptvPlaylistKind.M3U)
    val login: StateFlow<IptvPlaylistLogin> = store.login
    val lastLoad: StateFlow<Int> = sync.lastLoad

    private val _load = MutableStateFlow<IptvLoadState?>(null)
    val load: StateFlow<IptvLoadState?> = _load
    private var job: Job? = null

    val loadedAt: Long get() = store.loadedAt
    suspend fun count(): Int = sync.count()

    fun setMode(mode: IptvSourceMode) = viewModelScope.launch { settingsRepository.update { it.copy(iptvSource = mode) } }
    fun setKind(kind: IptvPlaylistKind) = viewModelScope.launch { settingsRepository.update { it.copy(iptvPlaylistKind = kind) } }

    /** Saves the fields (encrypted) and loads the playlist into the app. */
    fun saveAndLoad(login: IptvPlaylistLogin) {
        store.save(login)
        job?.cancel()
        _load.value = IptvLoadState(busy = true)
        job = viewModelScope.launch {
            val result = sync.load(mode.value.second) { p -> _load.value = IptvLoadState(true, p) }
            _load.value = IptvLoadState(false, result = result)
        }
    }

    fun cancel() {
        job?.cancel()
        _load.value = null
    }

    fun close() { _load.value = null }
}

/** Settings › Guide & Home › IPTV: XStreamity on the receiver, or an M3U / Xtream playlist. */
@Composable
fun IptvSettingsCard(model: IptvSettingsViewModel = hiltViewModel()) {
    val mode by model.mode.collectAsStateWithLifecycle()
    val saved by model.login.collectAsStateWithLifecycle()
    val loads by model.lastLoad.collectAsStateWithLifecycle()
    val load by model.load.collectAsStateWithLifecycle()
    var m3u by remember(saved) { mutableStateOf(saved.m3uUrl) }
    var server by remember(saved) { mutableStateOf(saved.server) }
    var user by remember(saved) { mutableStateOf(saved.username) }
    var pass by remember(saved) { mutableStateOf(saved.password) }
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(loads) { count = model.count() }

    SettingsCard(stringResource(R.string.iptv_settings_card)) {
        val sources = IptvSourceMode.entries
        ChoiceRow(
            title = stringResource(R.string.iptv_source),
            description = stringResource(R.string.iptv_source_desc),
            options = listOf(stringResource(R.string.iptv_source_xstreamity), stringResource(R.string.iptv_source_playlist)),
            selectedIndex = sources.indexOf(mode.first),
            onSelect = { model.setMode(sources[it]) },
        )
        if (mode.first == IptvSourceMode.PLAYLIST) {
            val kinds = IptvPlaylistKind.entries
            ChoiceRow(
                title = stringResource(R.string.iptv_playlist_kind),
                options = listOf(stringResource(R.string.iptv_kind_m3u), stringResource(R.string.iptv_kind_xtream)),
                selectedIndex = kinds.indexOf(mode.second),
                onSelect = { model.setKind(kinds[it]) },
            )
            if (mode.second == IptvPlaylistKind.M3U) {
                TvTextField(stringResource(R.string.iptv_m3u_url), m3u, { m3u = it }, Modifier.fillMaxWidth(), kind = TvFieldKind.HOST)
            } else {
                TvTextField(stringResource(R.string.iptv_xtream_server), server, { server = it }, Modifier.fillMaxWidth(), kind = TvFieldKind.HOST)
                TvTextField(stringResource(R.string.iptv_xtream_user), user, { user = it }, Modifier.fillMaxWidth())
                TvTextField(stringResource(R.string.iptv_xtream_pass), pass, { pass = it }, Modifier.fillMaxWidth(), kind = TvFieldKind.PASSWORD)
            }
            val current = IptvPlaylistLogin(m3u, server, user, pass)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(
                    stringResource(R.string.iptv_save_load),
                    { model.saveAndLoad(current) },
                    enabled = current.configured(mode.second),
                )
            }
            val at = model.loadedAt
            Text(
                if (at == 0L || count == 0) stringResource(R.string.iptv_status_none)
                else stringResource(R.string.iptv_status, count, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))),
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
            )
        }
    }
    load?.let { IptvLoadDialog(it, onCancel = model::cancel, onClose = model::close) }
}

@Composable
private fun IptvLoadDialog(state: IptvLoadState, onCancel: () -> Unit, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = { if (state.busy) onCancel() else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        NavigationSoundsForWindow()
        val button = remember { FocusRequester() }
        LaunchedEffect(state.busy) { button.requestWhenReady() }
        Column(
            modifier = Modifier
                .widthIn(min = 420.dp, max = 560.dp)
                .background(EnigmaColors.Selected, skinShape(14.dp))
                .border(1.dp, EnigmaColors.CardBorder, skinShape(14.dp))
                .padding(16.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.iptv_load_title), color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = EnigmaType.Strong)
            if (state.busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EnigmaLoadingIndicator()
                    val p = state.progress
                    Text(
                        when (p?.step) {
                            null, IptvLoadProgress.Step.CONNECT -> stringResource(R.string.iptv_load_connect)
                            IptvLoadProgress.Step.LIVE -> stringResource(R.string.iptv_load_live, p.count)
                            IptvLoadProgress.Step.MOVIES -> stringResource(R.string.iptv_load_movies, p.count)
                            IptvLoadProgress.Step.SERIES -> stringResource(R.string.iptv_load_series, p.count)
                            IptvLoadProgress.Step.M3U -> stringResource(R.string.iptv_load_entries, p.count)
                            IptvLoadProgress.Step.SAVE -> stringResource(R.string.iptv_load_save, p.count)
                        },
                        color = EnigmaColors.Text,
                        fontSize = 14.sp,
                    )
                }
                TvButton(stringResource(R.string.cancel), onCancel, modifier = Modifier.focusRequester(button))
            } else {
                val r = state.result
                Text(
                    when (r) {
                        is IptvLoadResult.Done -> stringResource(R.string.iptv_load_done, r.items, r.categories)
                        IptvLoadResult.Failure.NOT_CONFIGURED -> stringResource(R.string.iptv_err_config)
                        IptvLoadResult.Failure.LOGIN -> stringResource(R.string.iptv_err_login)
                        IptvLoadResult.Failure.HTTP -> stringResource(R.string.iptv_err_http)
                        IptvLoadResult.Failure.UNREACHABLE -> stringResource(R.string.iptv_err_unreachable)
                        IptvLoadResult.Failure.EMPTY, null -> stringResource(R.string.iptv_err_empty)
                    },
                    color = if (r is IptvLoadResult.Done) EnigmaColors.Text else EnigmaColors.Muted,
                    fontSize = 14.sp,
                )
                TvButton(stringResource(R.string.close), onClose, modifier = Modifier.focusRequester(button))
            }
        }
    }
}
