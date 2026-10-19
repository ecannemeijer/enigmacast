package com.enigmacast.ui.xtream

import com.enigmacast.ui.theme.skinSurface
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
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
import com.enigmacast.data.enigmasync.EnigmaSyncEngine
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.xtream.IptvLoadProgress
import com.enigmacast.data.xtream.IptvLoadResult
import com.enigmacast.data.xtream.IptvPlaylistLogin
import com.enigmacast.data.xtream.IptvPlaylistStore
import com.enigmacast.data.xtream.IptvPlaylistSync
import com.enigmacast.domain.model.IptvPlaylistKind
import com.enigmacast.domain.model.IptvSourceMode
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.domain.subsource.SubSourceLanguages
import com.enigmacast.locale.AppLanguageStore
import com.enigmacast.ui.theme.ChoiceRow
import com.enigmacast.ui.theme.CompactButton
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
    private val xtream: com.enigmacast.data.xtream.XtreamRepository,
    private val hidden: com.enigmacast.data.xtream.IptvHiddenCategories,
    private val syncEngine: EnigmaSyncEngine,
) : ViewModel() {
    /** Settings › IPTV › Categories: the open section's categories and which are hidden. */
    data class CategoryPage(
        val section: com.enigmacast.domain.xtream.XtreamSection,
        val loading: Boolean,
        val categories: List<com.enigmacast.domain.xtream.XtreamCategory> = emptyList(),
        val hidden: Set<String> = emptySet(),
        val failed: Boolean = false,
        val source: String = "",
    )
    private val _categories = MutableStateFlow<CategoryPage?>(null)
    val categoryPage: StateFlow<CategoryPage?> = _categories
    private var categoryJob: Job? = null

    fun openCategories() = showCategories(com.enigmacast.domain.xtream.XtreamSection.LIVE)

    fun showCategories(section: com.enigmacast.domain.xtream.XtreamSection) {
        categoryJob?.cancel()
        _categories.value = CategoryPage(section, loading = true)
        categoryJob = viewModelScope.launch {
            val page = runCatching {
                // Room (playlist) or the receiver's first XStreamity account; parsing runs off the main thread.
                val (mode, kind) = mode.value
                if (mode == IptvSourceMode.PLAYLIST) {
                    val source = com.enigmacast.domain.xtream.IptvCategoryFilter.sourceKey(kind.name, "")
                    CategoryPage(section, false, sync.categories(section), hidden.hidden(source, section), source = source)
                } else {
                    val receiver = settingsRepository.current().activeReceiver() ?: error("no receiver")
                    val account = (xtream.cachedDetection(receiver.id) ?: xtream.detect(receiver)).accounts.firstOrNull() ?: error("no playlist")
                    val source = com.enigmacast.domain.xtream.IptvCategoryFilter.sourceKey(null, account.key)
                    CategoryPage(section, false, xtream.categories(account, section), hidden.hidden(source, section), source = source)
                }
            }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; CategoryPage(section, false, failed = true) }
            _categories.value = page
        }
    }

    private fun saveHidden(ids: Set<String>) {
        val page = _categories.value ?: return
        if (page.source.isBlank()) return
        hidden.set(page.source, page.section, ids)
        _categories.value = page.copy(hidden = ids)
    }

    fun toggleCategory(id: String) = _categories.value?.let { saveHidden(com.enigmacast.domain.xtream.IptvCategoryFilter.toggle(it.hidden, id)) }
    fun showAllCategories() = saveHidden(com.enigmacast.domain.xtream.IptvCategoryFilter.all())
    fun hideAllCategories() = _categories.value?.let { saveHidden(com.enigmacast.domain.xtream.IptvCategoryFilter.none(it.categories)) }
    fun closeCategories() { categoryJob?.cancel(); _categories.value = null }

    /** Saved SubSource slugs. Null until the viewer saves a choice. */
    val subtitleLanguages: StateFlow<List<String>?> = settingsRepository.settings
        .map { it.iptvSubtitleLanguages }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _subtitlesOpen = MutableStateFlow(false)
    val subtitlesOpen: StateFlow<Boolean> = _subtitlesOpen

    fun openSubtitles() { _subtitlesOpen.value = true }
    fun closeSubtitles() { _subtitlesOpen.value = false }

    fun setSubtitleLanguages(slugs: List<String>) = viewModelScope.launch {
        settingsRepository.update { it.copy(iptvSubtitleLanguages = slugs.distinct()) }
    }

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

    /** Saves the fields (encrypted), writes that login to the sync server, and loads the channels. */
    fun saveAndLoad(login: IptvPlaylistLogin) {
        val kind = mode.value.second
        store.save(login)
        viewModelScope.launch { runCatching { syncEngine.publishPlaylist(login, kind) } }
        job?.cancel()
        _load.value = IptvLoadState(busy = true)
        job = viewModelScope.launch {
            val result = sync.load(kind) { p -> _load.value = IptvLoadState(true, p) }
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
        // Which categories the IPTV page shows, and which SubSource languages a film or series may use.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactButton(stringResource(R.string.iptv_categories_manage), model::openCategories)
            CompactButton(stringResource(R.string.iptv_subtitles_manage), model::openSubtitles)
        }
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
    val categoryPage by model.categoryPage.collectAsStateWithLifecycle()
    categoryPage?.let { IptvCategoriesDialog(it, model) }
    val subtitlesOpen by model.subtitlesOpen.collectAsStateWithLifecycle()
    val subtitleLanguages by model.subtitleLanguages.collectAsStateWithLifecycle()
    if (subtitlesOpen) IptvSubtitlesDialog(subtitleLanguages, model)
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
                .skinSurface(EnigmaColors.Selected, skinShape(14.dp))
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

/**
 * Settings › IPTV › Categories, in the style of Settings Import's group picker: Live TV / Films /
 * Series chips, Select all / Select none with "x of y shown", then a compact checkbox list.
 * Up/Down move between the three rows and the list; Back closes.
 */
@Composable
private fun IptvCategoriesDialog(page: IptvSettingsViewModel.CategoryPage, model: IptvSettingsViewModel) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = model::closeCategories,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        com.enigmacast.ui.theme.NavigationSoundsForWindow()
        val sections = com.enigmacast.domain.xtream.XtreamSection.entries
        val chipFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val allFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val noneFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val listFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { chipFocus.requestFocus() } }
        val Cancel = androidx.compose.ui.focus.FocusRequester.Cancel
        androidx.compose.foundation.layout.Column(
            Modifier
                .width(520.dp)
                .heightIn(max = 560.dp)
                .skinSurface(EnigmaColors.Selected, com.enigmacast.ui.theme.skinShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.iptv_categories_title), color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = com.enigmacast.ui.theme.EnigmaType.Strong)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.focusGroup()) {
                sections.forEachIndexed { index, section ->
                    com.enigmacast.ui.theme.ChoiceChip(
                        label = stringResource(
                            when (section) {
                                com.enigmacast.domain.xtream.XtreamSection.LIVE -> R.string.xtream_live
                                com.enigmacast.domain.xtream.XtreamSection.MOVIES -> R.string.xtream_movies
                                com.enigmacast.domain.xtream.XtreamSection.SERIES -> R.string.xtream_series
                            },
                        ),
                        selected = section == page.section,
                        onClick = { model.showCategories(section) },
                        modifier = Modifier
                            .then(if (section == page.section) Modifier.focusRequester(chipFocus) else Modifier)
                            .focusProperties {
                                up = Cancel
                                down = if (page.categories.isNotEmpty()) allFocus else Cancel
                                if (index == 0) left = Cancel
                                if (index == sections.lastIndex) right = Cancel
                            },
                    )
                }
            }
            when {
                page.loading -> com.enigmacast.ui.components.EnigmaLoadingIndicator()
                page.failed -> Text(stringResource(R.string.xtream_load_error), color = EnigmaColors.Danger, fontSize = 13.sp)
                page.categories.isEmpty() -> Text(stringResource(R.string.iptv_categories_none), color = EnigmaColors.Muted, fontSize = 13.sp)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
                        com.enigmacast.ui.theme.CompactButton(
                            stringResource(R.string.import_select_all),
                            model::showAllCategories,
                            modifier = Modifier.focusRequester(allFocus).focusProperties { up = chipFocus; down = listFocus; left = Cancel; right = noneFocus },
                            icon = androidx.compose.material.icons.Icons.Filled.Check,
                        )
                        com.enigmacast.ui.theme.CompactButton(
                            stringResource(R.string.import_select_none),
                            { model.hideAllCategories() },
                            modifier = Modifier.focusRequester(noneFocus).focusProperties { up = chipFocus; down = listFocus; left = allFocus; right = Cancel },
                        )
                        Text(
                            stringResource(R.string.iptv_categories_count, com.enigmacast.domain.xtream.IptvCategoryFilter.shown(page.categories, page.hidden), page.categories.size),
                            color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1,
                        )
                    }
                    val keyed = remember(page.categories) { com.enigmacast.domain.xtream.LazyKeys.keyed(page.categories, "cat") { it.id } }
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        itemsIndexed(keyed, key = { _, k -> k.key }) { index, k ->
                            com.enigmacast.ui.settingsimport.CompactToggle(
                                title = k.value.name,
                                checked = k.value.id !in page.hidden,
                                onToggle = { model.toggleCategory(k.value.id) },
                                modifier = Modifier
                                    .then(if (index == 0) Modifier.focusRequester(listFocus) else Modifier)
                                    .focusProperties {
                                        left = Cancel; right = Cancel
                                        if (index == 0) up = allFocus
                                        if (index == keyed.lastIndex) down = Cancel
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Settings › IPTV › Subtitles: the languages SubSource may offer under the OSD
 * for the film or episode that is playing.
 */
@Composable
private fun IptvSubtitlesDialog(saved: List<String>?, model: IptvSettingsViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appSlug = SubSourceLanguages.forAppTag(AppLanguageStore.current(context).tag)
    val selected = saved ?: listOfNotNull(appSlug)
    val languages = SubSourceLanguages.ordered
    androidx.compose.ui.window.Dialog(
        onDismissRequest = model::closeSubtitles,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NavigationSoundsForWindow()
        val allFocus = remember { FocusRequester() }
        val noneFocus = remember { FocusRequester() }
        val listFocus = remember { FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { allFocus.requestWhenReady() }
        val Cancel = FocusRequester.Cancel
        Column(
            Modifier
                .width(520.dp)
                .heightIn(max = 560.dp)
                .skinSurface(EnigmaColors.Selected, skinShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.iptv_subtitles_title), color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = EnigmaType.Strong)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
                CompactButton(
                    stringResource(R.string.import_select_all),
                    { model.setSubtitleLanguages(languages.map { it.slug }) },
                    modifier = Modifier.focusRequester(allFocus).focusProperties { up = Cancel; down = listFocus; left = Cancel; right = noneFocus },
                    icon = androidx.compose.material.icons.Icons.Filled.Check,
                )
                CompactButton(
                    stringResource(R.string.import_select_none),
                    { model.setSubtitleLanguages(emptyList()) },
                    modifier = Modifier.focusRequester(noneFocus).focusProperties { up = Cancel; down = listFocus; left = allFocus; right = Cancel },
                )
                Text(
                    stringResource(R.string.iptv_subtitles_count, selected.size, languages.size),
                    color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1,
                )
            }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                itemsIndexed(languages, key = { _, language -> language.slug }) { index, language ->
                    com.enigmacast.ui.settingsimport.CompactToggle(
                        title = SubSourceLanguages.display(language),
                        checked = language.slug in selected,
                        onToggle = {
                            val next = if (language.slug in selected) selected - language.slug else selected + language.slug
                            model.setSubtitleLanguages(next)
                        },
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(listFocus) else Modifier)
                            .focusProperties {
                                left = Cancel; right = Cancel
                                if (index == 0) up = allFocus
                                if (index == languages.lastIndex) down = Cancel
                            },
                    )
                }
            }
        }
    }
}
