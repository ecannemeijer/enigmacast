package com.enigmacast.ui.xtream

import com.enigmacast.ui.theme.skinSurface
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
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
import com.enigmacast.data.xtream.IptvAccountStore
import com.enigmacast.domain.xtream.XtreamAccountInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.ui.platform.LocalConfiguration
import com.enigmacast.data.xtream.IptvPlaylistStore
import com.enigmacast.data.xtream.IptvPlaylistSync
import com.enigmacast.data.xtream.IptvProvider
import com.enigmacast.domain.model.IptvPlaylistKind
import com.enigmacast.domain.model.IptvRefreshInterval
import com.enigmacast.domain.model.IptvSourceMode
import com.enigmacast.ui.components.EnigmaLoadingIndicator
import com.enigmacast.domain.subsource.SubSourceLanguages
import com.enigmacast.locale.AppLanguageStore
import com.enigmacast.ui.theme.ChoiceRow
import com.enigmacast.ui.theme.CompactButton
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.TvButton
import com.enigmacast.ui.theme.ValueRow
import com.enigmacast.ui.theme.ToggleRow
import androidx.compose.foundation.focusable
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
    private val accounts: IptvAccountStore,
) : ViewModel() {
    /** Settings › IPTV › Categories: the open section's categories and which are hidden. */
    data class CategoryPage(
        val section: com.enigmacast.domain.xtream.XtreamSection,
        val loading: Boolean,
        val categories: List<com.enigmacast.domain.xtream.XtreamCategory> = emptyList(),
        val hidden: Set<String> = emptySet(),
        val failed: Boolean = false,
        val source: String = "",
        val providerId: String = "",
        val providers: List<com.enigmacast.data.xtream.IptvProvider> = emptyList(),
    )
    private val _categories = MutableStateFlow<CategoryPage?>(null)
    val categoryPage: StateFlow<CategoryPage?> = _categories
    private var categoryJob: Job? = null

    fun openCategories() = showCategories(com.enigmacast.domain.xtream.XtreamSection.LIVE)

    fun showCategories(section: com.enigmacast.domain.xtream.XtreamSection, providerId: String? = null) {
        categoryJob?.cancel()
        val providers = store.enabledProviders()
        if (mode.value.first == IptvSourceMode.PLAYLIST && providerId == null && providers.size > 1) {
            _categories.value = CategoryPage(section, loading = false, providers = providers)
            return
        }
        _categories.value = CategoryPage(section, loading = true, providers = providers, providerId = providerId.orEmpty())
        categoryJob = viewModelScope.launch {
            val page = runCatching {
                val (mode, kind) = mode.value
                if (mode == IptvSourceMode.PLAYLIST) {
                    val chosen = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull()
                    val source = chosen?.let { com.enigmacast.data.xtream.IptvProvider.sourceKey(it.id) }
                        ?: com.enigmacast.domain.xtream.IptvCategoryFilter.sourceKey(kind.name, "")
                    CategoryPage(
                        section,
                        false,
                        if (chosen == null) emptyList() else sync.categories(section, chosen.id),
                        hidden.hidden(source, section),
                        source = source,
                        providerId = chosen?.id.orEmpty(),
                        providers = providers,
                    )
                } else {
                    val receiver = settingsRepository.current().activeReceiver() ?: error("no receiver")
                    val account = (xtream.cachedDetection(receiver.id) ?: xtream.detect(receiver)).accounts.firstOrNull() ?: error("no playlist")
                    val source = com.enigmacast.domain.xtream.IptvCategoryFilter.sourceKey(null, account.key)
                    CategoryPage(section, false, xtream.categories(account, section), hidden.hidden(source, section), source = source)
                }
            }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; CategoryPage(section, false, failed = true, providers = providers) }
            _categories.value = page
        }
    }

    fun selectCategoryProvider(id: String) {
        val page = _categories.value ?: return
        showCategories(page.section, id)
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

    /** Settings › IPTV › channels: merged qualities, hidden channels and own order. */
    data class HiddenChannel(val source: String, val key: String, val name: String)
    private val _channelOptions = MutableStateFlow(Triple(hidden.mergeEnabled(), hidden.qualityOrder(), hidden.hasOrders()))
    val channelOptions: StateFlow<Triple<Boolean, com.enigmacast.domain.xtream.ChannelVariants.QualityOrder, Boolean>> = _channelOptions
    private val _hiddenChannels = MutableStateFlow<List<HiddenChannel>?>(null)
    val hiddenChannelsPage: StateFlow<List<HiddenChannel>?> = _hiddenChannels
    private fun refreshChannels() {
        _channelOptions.value = Triple(hidden.mergeEnabled(), hidden.qualityOrder(), hidden.hasOrders())
        if (_hiddenChannels.value != null) _hiddenChannels.value = hiddenChannelList()
    }
    private fun hiddenChannelList() = hidden.sourcesWithHiddenChannels().flatMap { source ->
        hidden.hiddenChannels(source).map { (key, name) -> HiddenChannel(source, key, name) }
    }.sortedBy { it.name.lowercase() }
    fun hiddenChannelCount(): Int = hiddenChannelList().size
    fun setMergeChannels(on: Boolean) { hidden.setMergeEnabled(on); refreshChannels() }
    fun setQualityOrder(order: com.enigmacast.domain.xtream.ChannelVariants.QualityOrder) { hidden.setQualityOrder(order); refreshChannels() }
    fun resetChannelOrder() { hidden.resetOrders(); refreshChannels() }
    fun openHiddenChannels() { _hiddenChannels.value = hiddenChannelList() }
    fun closeHiddenChannels() { _hiddenChannels.value = null; refreshChannels() }
    fun showChannel(channel: HiddenChannel) { hidden.showChannel(channel.source, channel.key); refreshChannels() }
    fun showAllChannels() { hidden.sourcesWithHiddenChannels().forEach { hidden.showAllChannels(it) }; refreshChannels() }

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
    val providers: StateFlow<List<com.enigmacast.data.xtream.IptvProvider>> = store.providers
    val providerFailures: StateFlow<Map<String, IptvLoadResult.Failure>> = sync.providerFailures
    val lastLoad: StateFlow<Int> = sync.lastLoad

    private val _load = MutableStateFlow<IptvLoadState?>(null)
    val load: StateFlow<IptvLoadState?> = _load
    private var job: Job? = null

    val loadedAt: Long get() = store.loadedAt
    suspend fun count(): Int = sync.count()

    private val _providerCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val providerCounts: StateFlow<Map<String, Int>> = _providerCounts
    fun refreshProviderCounts() = viewModelScope.launch {
        _providerCounts.value = store.providers.value.associate { provider ->
            provider.id to runCatching { sync.countProvider(provider.id) }.getOrDefault(0)
        }
    }

    fun setMode(mode: IptvSourceMode) = viewModelScope.launch { settingsRepository.update { it.copy(iptvSource = mode) } }
    fun setKind(kind: IptvPlaylistKind) = viewModelScope.launch { settingsRepository.update { it.copy(iptvPlaylistKind = kind) } }
    val refreshInterval: StateFlow<IptvRefreshInterval> = settingsRepository.settings
        .map { it.iptvRefreshInterval }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IptvRefreshInterval.EVERY_24H)
    fun setRefreshInterval(interval: IptvRefreshInterval) = viewModelScope.launch {
        settingsRepository.update { it.copy(iptvRefreshInterval = interval) }
    }
    val accountInfo: StateFlow<Map<String, XtreamAccountInfo>> = accounts.info
    val expiryWarning: StateFlow<Boolean> = settingsRepository.settings
        .map { it.iptvExpiryWarning }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun setExpiryWarning(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.update { it.copy(iptvExpiryWarning = enabled) }
    }

    val recordMarginBefore: StateFlow<Int> = settingsRepository.settings
        .map { it.iptvRecordMarginBeforeMin }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)
    val recordMarginAfter: StateFlow<Int> = settingsRepository.settings
        .map { it.iptvRecordMarginAfterMin }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 2)
    val recordVolume: StateFlow<Int> = settingsRepository.settings
        .map { it.iptvRecordVolume }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setRecordMarginBefore(minutes: Int) = viewModelScope.launch {
        settingsRepository.update { it.copy(iptvRecordMarginBeforeMin = minutes) }
    }
    fun setRecordMarginAfter(minutes: Int) = viewModelScope.launch {
        settingsRepository.update { it.copy(iptvRecordMarginAfterMin = minutes) }
    }
    fun setRecordVolume(index: Int) = viewModelScope.launch {
        settingsRepository.update { it.copy(iptvRecordVolume = index) }
    }
    fun refreshAccountInfo(force: Boolean = false) = viewModelScope.launch {
        accounts.refreshAll(force)
    }
    fun refreshAccountInfo(provider: IptvProvider, force: Boolean = false) = viewModelScope.launch {
        accounts.refreshOne(provider, force)
    }

    /** Saves the fields of the selected provider, writes it to the sync server, and reloads all. */
    fun saveProvider(provider: com.enigmacast.data.xtream.IptvProvider): com.enigmacast.data.xtream.IptvProvider? {
        val saved = runCatching { store.saveProvider(provider) }.getOrElse {
            _load.value = IptvLoadState(false, result = IptvLoadResult.Failure.NOT_CONFIGURED)
            return null
        }
        // Keep the server's provider list in step. The old single-playlist endpoint is only for
        // devices that have no provider list at all.
        store.markDirty()
        viewModelScope.launch { runCatching { syncEngine.publishProviders() } }
        viewModelScope.launch { settingsRepository.update { it.copy(iptvSource = IptvSourceMode.PLAYLIST) } }
        reload()
        return saved
    }

    fun deleteProvider(id: String) {
        store.deleteProvider(id)
        store.markDirty()
        viewModelScope.launch { runCatching { syncEngine.publishProviders() } }
        viewModelScope.launch { sync.clearProvider(id) }
        reload()
    }

    /**
     * Active/inactive is this TV's own choice: nothing goes to the sync server, and the lists
     * already loaded are only filtered. A provider that was never loaded on this TV is fetched.
     */
    fun setProviderEnabled(id: String, enabled: Boolean) {
        store.setProviderEnabled(id, enabled)
        if (!enabled) return
        viewModelScope.launch {
            val cached = runCatching { sync.countProvider(id) }.getOrDefault(0)
            if (cached == 0) loadOne(id)
        }
    }

    private fun loadOne(id: String) {
        job?.cancel()
        _load.value = IptvLoadState(busy = true)
        job = viewModelScope.launch {
            val result = sync.loadOne(id) { p -> _load.value = IptvLoadState(true, p) }
            _load.value = IptvLoadState(false, result = result)
            refreshProviderCounts()
        }
    }

    fun moveProvider(id: String, delta: Int) {
        store.moveProvider(id, delta)
        store.markDirty()
        viewModelScope.launch { runCatching { syncEngine.publishProviders() } }
        refreshProviderCounts()
    }

    fun reloadAll() = reload()

    fun providersJson(): String = store.providersJson()

    fun importProviders(raw: String): Int {
        val count = store.importProviders(raw)
        if (count > 0) {
            store.markDirty()
            viewModelScope.launch { runCatching { syncEngine.publishProviders() } }
            reload()
        }
        return count
    }

    private fun reload() {
        job?.cancel()
        _load.value = IptvLoadState(busy = true)
        job = viewModelScope.launch {
            val result = sync.load(mode.value.second) { p -> _load.value = IptvLoadState(true, p) }
            _load.value = IptvLoadState(false, result = result)
            refreshProviderCounts()
        }
    }

    /** Compatibility for the old single-login editor. */
    fun saveAndLoad(login: IptvPlaylistLogin) {
        val kind = mode.value.second
        store.save(login)
        viewModelScope.launch { runCatching { syncEngine.publishPlaylist(login, kind) } }
        reload()
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
    val context = LocalContext.current
    val mode by model.mode.collectAsStateWithLifecycle()
    val providers by model.providers.collectAsStateWithLifecycle()
    val providerCounts by model.providerCounts.collectAsStateWithLifecycle()
    val providerFailures by model.providerFailures.collectAsStateWithLifecycle()
    val loads by model.lastLoad.collectAsStateWithLifecycle()
    val load by model.load.collectAsStateWithLifecycle()
    // The provider whose dialog is open: an id, NEW_PROVIDER for "Add provider", or null.
    var editing by remember { mutableStateOf<String?>(null) }
    // Where focus goes back to when the dialog closes (a provider id or NEW_PROVIDER).
    var returnFocus by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf("") }
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    fun focusFor(key: String) = rowFocus.getOrPut(key) { FocusRequester() }
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(loads) { count = model.count() }
    LaunchedEffect(providers, loads) { model.refreshProviderCounts() }
    val accountInfo by model.accountInfo.collectAsStateWithLifecycle()
    val expiryWarning by model.expiryWarning.collectAsStateWithLifecycle()
    LaunchedEffect(providers, loads) { model.refreshAccountInfo() }

    SettingsCard(stringResource(R.string.iptv_source_card)) {
        val sources = IptvSourceMode.entries
        ChoiceRow(
            title = stringResource(R.string.iptv_source),
            description = stringResource(R.string.iptv_source_desc),
            options = listOf(stringResource(R.string.iptv_source_xstreamity), stringResource(R.string.iptv_source_playlist)),
            selectedIndex = sources.indexOf(mode.first),
            onSelect = { model.setMode(sources[it]) },
        )
        if (mode.first == IptvSourceMode.XSTREAMITY) {
            Text(
                stringResource(R.string.iptv_source_xstreamity_help),
                color = EnigmaColors.Muted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
    if (mode.first == IptvSourceMode.PLAYLIST) {
        SettingsCard(stringResource(R.string.iptv_providers_card)) {
            if (providers.isEmpty()) {
                Text(
                    stringResource(R.string.iptv_provider_empty),
                    color = EnigmaColors.Muted,
                    fontSize = EnigmaType.SettingsDescription,
                )
            }
            if (expiryWarning) {
                val nowSec = System.currentTimeMillis() / 1000
                val warnings = providers.mapNotNull { provider ->
                    val info = accountInfo[provider.id] ?: return@mapNotNull null
                    if (!info.needsWarning(nowSec, IptvAccountStore.DEFAULT_WARN_DAYS)) return@mapNotNull null
                    val days = info.daysLeft(nowSec) ?: return@mapNotNull null
                    if (days < 0L || info.isExpired(nowSec)) {
                        stringResource(R.string.iptv_account_warn_expired, provider.displayName())
                    } else {
                        stringResource(R.string.iptv_account_warn, provider.displayName(), days.toInt())
                    }
                }
                warnings.forEach { line ->
                    Text(line, color = EnigmaColors.Danger, fontSize = EnigmaType.SettingsDescription)
                }
            }
            providers.forEach { provider ->
                CompactListRow(
                    onClick = { editing = provider.id },
                    modifier = Modifier.focusRequester(focusFor(provider.id)),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            provider.displayName(),
                            color = EnigmaColors.Text,
                            fontSize = EnigmaType.SettingsTitle,
                            fontWeight = EnigmaType.Strong,
                            maxLines = 1,
                        )
                        val status = providerCounts[provider.id]?.let {
                            stringResource(R.string.iptv_provider_channels, it)
                        } ?: stringResource(R.string.iptv_provider_not_loaded)
                        val account = accountInfo[provider.id]
                        val accountLine = account?.let { iptvAccountLine(it) }
                        val warn = expiryWarning && account?.needsWarning(System.currentTimeMillis() / 1000, IptvAccountStore.DEFAULT_WARN_DAYS) == true
                        Text(
                            listOf(
                                provider.kind.name,
                                if (provider.enabled) stringResource(R.string.iptv_provider_enabled) else stringResource(R.string.iptv_provider_disabled),
                                status,
                                accountLine,
                                providerFailures[provider.id]?.let { stringResource(R.string.iptv_provider_failed) },
                            ).filterNotNull().joinToString("  ·  "),
                            color = if (providerFailures[provider.id] != null || warn) EnigmaColors.Danger else EnigmaColors.Muted,
                            fontSize = 12.sp,
                        )
                    }
                    Text("›", color = EnigmaColors.Subtle, fontSize = EnigmaType.SettingsTitle)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(
                    stringResource(R.string.iptv_provider_add),
                    { editing = NEW_PROVIDER },
                    modifier = Modifier.focusRequester(focusFor(NEW_PROVIDER)),
                    compact = true,
                )
            }
        }

        SettingsCard(stringResource(R.string.iptv_manage_card)) {
            val refreshOptions = IptvRefreshInterval.entries
            val refresh by model.refreshInterval.collectAsStateWithLifecycle()
            ChoiceRow(
                title = stringResource(R.string.iptv_refresh_interval),
                description = stringResource(R.string.iptv_refresh_interval_desc),
                options = listOf(
                    stringResource(R.string.iptv_refresh_6h),
                    stringResource(R.string.iptv_refresh_12h),
                    stringResource(R.string.iptv_refresh_24h),
                    stringResource(R.string.iptv_refresh_48h),
                    stringResource(R.string.iptv_refresh_manual),
                ),
                selectedIndex = refreshOptions.indexOf(refresh).coerceAtLeast(0),
                onSelect = { model.setRefreshInterval(refreshOptions[it]) },
            )
            ToggleRow(
                title = stringResource(R.string.iptv_expiry_warning),
                description = stringResource(R.string.iptv_expiry_warning_desc),
                checked = expiryWarning,
                onCheckedChange = model::setExpiryWarning,
            )
            val marginChoices = com.enigmacast.domain.recording.IptvRecordTimes.MARGIN_MINUTES
            val marginBefore by model.recordMarginBefore.collectAsStateWithLifecycle()
            val marginAfter by model.recordMarginAfter.collectAsStateWithLifecycle()
            val marginLabels = marginChoices.map { minutes ->
                if (minutes == 0) stringResource(R.string.iptv_record_margin_none)
                else stringResource(R.string.iptv_record_margin_min, minutes)
            }
            ChoiceRow(
                title = stringResource(R.string.iptv_record_margin_before),
                description = stringResource(R.string.iptv_record_margin_before_desc),
                options = marginLabels,
                selectedIndex = marginChoices.indexOf(marginBefore).let { if (it >= 0) it else 1 },
                onSelect = { model.setRecordMarginBefore(marginChoices[it]) },
            )
            ChoiceRow(
                title = stringResource(R.string.iptv_record_margin_after),
                description = stringResource(R.string.iptv_record_margin_after_desc),
                options = marginLabels,
                selectedIndex = marginChoices.indexOf(marginAfter).let { if (it >= 0) it else 2 },
                onSelect = { model.setRecordMarginAfter(marginChoices[it]) },
            )
            val recordContext = LocalContext.current
            val volumes = remember(recordContext) { com.enigmacast.live.IptvRecordStorage.volumes(recordContext) }
            val volumeIndex by model.recordVolume.collectAsStateWithLifecycle()
            if (volumes.isNotEmpty()) {
                val volumeLabels = volumes.map { volume ->
                    val name = if (volume.removable) {
                        stringResource(R.string.iptv_record_storage_external, volume.label)
                    } else {
                        stringResource(R.string.iptv_record_storage_internal, volume.label)
                    }
                    val space = stringResource(
                        R.string.iptv_record_storage_space,
                        android.text.format.Formatter.formatShortFileSize(recordContext, volume.freeBytes),
                        android.text.format.Formatter.formatShortFileSize(recordContext, volume.totalBytes),
                    )
                    "$name  ·  $space"
                }
                ChoiceRow(
                    title = stringResource(R.string.iptv_record_storage),
                    description = stringResource(R.string.iptv_record_storage_desc),
                    options = volumeLabels,
                    selectedIndex = volumeIndex.coerceIn(0, volumes.lastIndex),
                    onSelect = { model.setRecordVolume(volumes[it].index) },
                )
            }

            ValueRow(
                title = stringResource(R.string.iptv_categories_manage),
                value = "›",
                description = stringResource(R.string.iptv_categories_desc),
                onClick = model::openCategories,
            )
            val channelOptions by model.channelOptions.collectAsStateWithLifecycle()
            val hiddenPage by model.hiddenChannelsPage.collectAsStateWithLifecycle()
            ToggleRow(
                title = stringResource(R.string.iptv_merge_channels),
                description = stringResource(R.string.iptv_merge_channels_desc),
                checked = channelOptions.first,
                onCheckedChange = model::setMergeChannels,
            )
            if (channelOptions.first) {
                val orders = com.enigmacast.domain.xtream.ChannelVariants.QualityOrder.entries
                ChoiceRow(
                    title = stringResource(R.string.iptv_quality_order),
                    options = listOf(stringResource(R.string.iptv_quality_highest), stringResource(R.string.iptv_quality_hd), stringResource(R.string.iptv_quality_saver)),
                    selectedIndex = orders.indexOf(channelOptions.second),
                    onSelect = { model.setQualityOrder(orders[it]) },
                )
            }
            val hiddenCount = remember(hiddenPage, channelOptions) { model.hiddenChannelCount() }
            ValueRow(
                title = stringResource(R.string.iptv_hidden_channels),
                value = if (hiddenCount > 0) "$hiddenCount  ›" else "›",
                description = stringResource(R.string.iptv_hidden_channels_desc),
                onClick = model::openHiddenChannels,
            )
            if (channelOptions.third) {
                ValueRow(
                    title = stringResource(R.string.iptv_reset_order),
                    value = "›",
                    onClick = model::resetChannelOrder,
                )
            }
            hiddenPage?.let { IptvHiddenChannelsDialog(it, model) }
            ValueRow(
                title = stringResource(R.string.iptv_subtitles_manage),
                value = "›",
                onClick = model::openSubtitles,
            )
        }

        SettingsCard(stringResource(R.string.iptv_backup_card)) {
            ValueRow(
                title = stringResource(R.string.iptv_provider_export),
                value = "›",
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("EnigmaCast IPTV providers", model.providersJson()))
                    notice = context.getString(R.string.iptv_provider_exported)
                },
            )
            ValueRow(
                title = stringResource(R.string.iptv_provider_import),
                value = "›",
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val raw = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    val imported = model.importProviders(raw)
                    notice = if (imported > 0) context.getString(R.string.iptv_provider_imported, imported) else context.getString(R.string.iptv_provider_import_failed)
                },
            )
            ValueRow(
                title = stringResource(R.string.iptv_provider_reload),
                value = "›",
                onClick = { notice = ""; model.reloadAll() },
            )
            if (notice.isNotBlank()) {
                Text(notice, color = EnigmaColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }

        Text(
            if (model.loadedAt == 0L || count == 0) stringResource(R.string.iptv_status_none)
            else stringResource(R.string.iptv_status, count, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(model.loadedAt))),
            color = EnigmaColors.Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
    editing?.let { key ->
        val provider = providers.firstOrNull { it.id == key }
        if (key != NEW_PROVIDER && provider == null) {
            // Deleted (here or from the sync server): close and land on "Add provider".
            LaunchedEffect(key) { editing = null; returnFocus = NEW_PROVIDER }
        } else {
            IptvProviderDialog(
                provider = provider,
                model = model,
                onClose = { savedId ->
                    returnFocus = savedId ?: key
                    editing = null
                },
            )
        }
    }
    // Back (or Save) closes the dialog: focus returns to the provider row it was opened from.
    LaunchedEffect(returnFocus, providers) {
        val key = returnFocus ?: return@LaunchedEffect
        val target = if (key == NEW_PROVIDER || providers.any { it.id == key }) key else NEW_PROVIDER
        focusFor(target).requestWhenReady()
        returnFocus = null
    }
    load?.let { IptvLoadDialog(it, onCancel = model::cancel, onClose = model::close) }
    val categoryPage by model.categoryPage.collectAsStateWithLifecycle()
    categoryPage?.let { IptvCategoriesDialog(it, model) }
    val subtitlesOpen by model.subtitlesOpen.collectAsStateWithLifecycle()
    val subtitleLanguages by model.subtitleLanguages.collectAsStateWithLifecycle()
    if (subtitlesOpen) IptvSubtitlesDialog(subtitleLanguages, model)
}

private const val NEW_PROVIDER = "\u0000new"

/**
 * One IPTV provider in its own dialog (Settings › IPTV › a provider row, or "Add provider"): name,
 * type, address and login, then for a saved provider on/off, order, reload and delete. Focus starts
 * on the name field and stays inside the dialog; Back closes it ([onClose] gets the saved id, or
 * null when nothing was saved).
 */
@Composable
private fun IptvProviderDialog(
    provider: IptvProvider?,
    model: IptvSettingsViewModel,
    onClose: (savedId: String?) -> Unit,
) {
    var name by remember(provider?.id) { mutableStateOf(provider?.displayName().orEmpty()) }
    var kind by remember(provider?.id) { mutableStateOf(provider?.kind ?: IptvPlaylistKind.M3U) }
    var m3u by remember(provider?.id) { mutableStateOf(provider?.m3uUrl.orEmpty()) }
    var server by remember(provider?.id) { mutableStateOf(provider?.server.orEmpty()) }
    var user by remember(provider?.id) { mutableStateOf(provider?.username.orEmpty()) }
    var pass by remember(provider?.id) { mutableStateOf(provider?.password.orEmpty()) }
    var userAgent by remember(provider?.id) { mutableStateOf(provider?.userAgent.orEmpty()) }
    var httpHeaders by remember(provider?.id) { mutableStateOf(provider?.httpHeaders.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { onClose(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        NavigationSoundsForWindow()
        val first = remember { FocusRequester() }
        LaunchedEffect(provider?.id) { first.requestWhenReady() }
        Column(
            modifier = Modifier
                .width(620.dp)
                .heightIn(max = 600.dp)
                .skinSurface(EnigmaColors.Selected, skinShape(14.dp))
                .padding(16.dp)
                .focusGroup()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                provider?.displayName() ?: stringResource(R.string.iptv_provider_new_title),
                color = EnigmaColors.Text,
                fontSize = EnigmaType.SettingsTitle,
                fontWeight = EnigmaType.Strong,
            )
            if (provider != null) {
                Text(
                    "${provider.kind.name} · ${if (provider.enabled) stringResource(R.string.iptv_provider_enabled) else stringResource(R.string.iptv_provider_disabled)}",
                    color = EnigmaColors.Muted,
                    fontSize = 12.sp,
                )
                val expiryWarning by model.expiryWarning.collectAsStateWithLifecycle()
                val accountInfo by model.accountInfo.collectAsStateWithLifecycle()
                LaunchedEffect(provider.id) { model.refreshAccountInfo(provider) }
                accountInfo[provider.id]?.let { info ->
                    val warn = expiryWarning && info.needsWarning(System.currentTimeMillis() / 1000, IptvAccountStore.DEFAULT_WARN_DAYS)
                    Text(
                        iptvAccountLine(info),
                        color = if (warn) EnigmaColors.Danger else EnigmaColors.Muted,
                        fontSize = 12.sp,
                    )
                }
            }
            TvTextField(stringResource(R.string.iptv_provider_name), name, { name = it }, Modifier.fillMaxWidth(), fieldFocus = first)
            val kinds = IptvPlaylistKind.entries
            ChoiceRow(
                title = stringResource(R.string.iptv_playlist_kind),
                options = listOf(stringResource(R.string.iptv_kind_m3u), stringResource(R.string.iptv_kind_xtream)),
                selectedIndex = kinds.indexOf(kind),
                onSelect = { kind = kinds[it] },
            )
            if (kind == IptvPlaylistKind.M3U) {
                TvTextField(stringResource(R.string.iptv_m3u_url), m3u, { m3u = it }, Modifier.fillMaxWidth(), kind = TvFieldKind.HOST)
            } else {
                TvTextField(stringResource(R.string.iptv_xtream_server), server, { server = it }, Modifier.fillMaxWidth(), kind = TvFieldKind.HOST)
                TvTextField(stringResource(R.string.iptv_xtream_user), user, { user = it }, Modifier.fillMaxWidth())
                TvTextField(stringResource(R.string.iptv_xtream_pass), pass, { pass = it }, Modifier.fillMaxWidth(), kind = TvFieldKind.PASSWORD)
            }
            TvTextField(
                stringResource(R.string.iptv_provider_user_agent),
                userAgent,
                { userAgent = it },
                Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.iptv_provider_user_agent_desc),
                color = EnigmaColors.Muted,
                fontSize = 12.sp,
            )
            TvTextField(
                stringResource(R.string.iptv_provider_headers),
                httpHeaders,
                { httpHeaders = it },
                Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.iptv_provider_headers_desc),
                color = EnigmaColors.Muted,
                fontSize = 12.sp,
            )
            val draft = IptvProvider(
                id = provider?.id.orEmpty(),
                name = name,
                kind = kind,
                m3uUrl = m3u,
                server = server,
                username = user,
                password = pass,
                enabled = provider?.enabled ?: true,
                sortOrder = provider?.sortOrder ?: 0,
                userAgent = userAgent,
                httpHeaders = httpHeaders,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(
                    stringResource(R.string.iptv_provider_save),
                    {
                        val saved = model.saveProvider(draft)
                        if (saved != null) onClose(saved.id)
                    },
                    enabled = name.isNotBlank() && draft.configured(),
                )
                TvButton(stringResource(R.string.cancel), { onClose(null) })
            }
            if (provider != null) {
                Text(
                    stringResource(R.string.iptv_provider_actions),
                    color = EnigmaColors.Text,
                    fontSize = EnigmaType.SettingsTitle,
                    fontWeight = EnigmaType.Strong,
                    modifier = Modifier.padding(top = 6.dp),
                )
                ValueRow(
                    title = stringResource(if (provider.enabled) R.string.iptv_provider_disable_action else R.string.iptv_provider_enable_action),
                    value = if (provider.enabled) stringResource(R.string.iptv_provider_enabled) else stringResource(R.string.iptv_provider_disabled),
                    onClick = { model.setProviderEnabled(provider.id, !provider.enabled) },
                )
                ValueRow(
                    title = stringResource(R.string.iptv_provider_move_up),
                    value = "›",
                    onClick = { model.moveProvider(provider.id, -1) },
                )
                ValueRow(
                    title = stringResource(R.string.iptv_provider_move_down),
                    value = "›",
                    onClick = { model.moveProvider(provider.id, 1) },
                )
                ValueRow(
                    title = stringResource(R.string.iptv_provider_reload),
                    value = "›",
                    onClick = model::reloadAll,
                )
                ValueRow(
                    title = stringResource(R.string.iptv_provider_delete),
                    value = "›",
                    description = stringResource(R.string.iptv_provider_delete_desc),
                    onClick = { confirmDelete = true },
                )
            }
        }
    }
    if (confirmDelete && provider != null) {
        com.enigmacast.ui.theme.TvChoiceDialog(
            title = stringResource(R.string.iptv_provider_delete_confirm),
            message = provider.displayName(),
            choices = listOf(stringResource(R.string.iptv_provider_delete), stringResource(R.string.cancel)),
            onChoose = { choice ->
                confirmDelete = false
                if (choice == 0) {
                    model.deleteProvider(provider.id)
                    onClose(null)
                }
            },
            onDismiss = { confirmDelete = false },
            compact = true,
        )
    }
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
            Text(stringResource(R.string.iptv_load_title), color = EnigmaColors.Text, fontSize = EnigmaType.SettingsTitle, fontWeight = EnigmaType.Strong)
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
                        fontSize = EnigmaType.SettingsDescription,
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
                    fontSize = EnigmaType.SettingsDescription,
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
        val providerFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val sectionFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val allFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val noneFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val listFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val providerRow = rememberLazyListState()
        androidx.compose.runtime.LaunchedEffect(page.providers.size, page.providerId) {
            if (page.providers.size > 1 && page.providerId.isBlank()) {
                runCatching { providerFocus.requestFocus() }
            } else {
                runCatching { sectionFocus.requestFocus() }
            }
        }
        val Cancel = androidx.compose.ui.focus.FocusRequester.Cancel
        androidx.compose.foundation.layout.Column(
            Modifier
                .width(520.dp)
                .heightIn(max = 560.dp)
                .skinSurface(EnigmaColors.Selected, com.enigmacast.ui.theme.skinShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.iptv_categories_title), color = EnigmaColors.Text, fontSize = EnigmaType.SettingsTitle, fontWeight = com.enigmacast.ui.theme.EnigmaType.Strong)
            if (page.providers.size > 1) {
                Text(stringResource(R.string.iptv_provider_choose), color = EnigmaColors.Muted, fontSize = 12.sp)
                val focusProvider = page.providerId.ifBlank { page.providers.firstOrNull()?.id.orEmpty() }
                androidx.compose.foundation.lazy.LazyRow(
                    state = providerRow,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().focusGroup(),
                ) {
                    items(page.providers, key = { it.id }) { provider ->
                        com.enigmacast.ui.theme.ChoiceChip(
                            label = provider.displayName(),
                            selected = provider.id == page.providerId,
                            onClick = { model.selectCategoryProvider(provider.id) },
                            modifier = Modifier
                                .then(if (provider.id == focusProvider) Modifier.focusRequester(providerFocus) else Modifier)
                                .focusProperties {
                                    up = Cancel
                                    down = sectionFocus
                                },
                        )
                    }
                }
            }
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
                        onClick = { model.showCategories(section, page.providerId.ifBlank { null }) },
                        modifier = Modifier
                            .then(if (section == page.section) Modifier.focusRequester(sectionFocus) else Modifier)
                            .focusProperties {
                                up = if (page.providers.size > 1) providerFocus else Cancel
                                down = if (page.categories.isNotEmpty()) allFocus else Cancel
                                if (index == 0) left = Cancel
                                if (index == sections.lastIndex) right = Cancel
                            },
                    )
                }
            }
            when {
                page.loading -> com.enigmacast.ui.components.EnigmaLoadingIndicator()
                page.failed -> Text(stringResource(R.string.xtream_load_error), color = EnigmaColors.Danger, fontSize = EnigmaType.SettingsDescription)
                page.providers.size > 1 && page.providerId.isBlank() -> Text(
                    stringResource(R.string.iptv_provider_choose),
                    color = EnigmaColors.Muted,
                    fontSize = EnigmaType.SettingsDescription,
                )
                page.categories.isEmpty() -> Text(stringResource(R.string.iptv_categories_none), color = EnigmaColors.Muted, fontSize = EnigmaType.SettingsDescription)
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.focusGroup()) {
                        com.enigmacast.ui.theme.CompactButton(
                            stringResource(R.string.import_select_all),
                            model::showAllCategories,
                            modifier = Modifier.focusRequester(allFocus).focusProperties { up = sectionFocus; down = listFocus; left = Cancel; right = noneFocus },
                            icon = androidx.compose.material.icons.Icons.Filled.Check,
                        )
                        com.enigmacast.ui.theme.CompactButton(
                            stringResource(R.string.import_select_none),
                            { model.hideAllCategories() },
                            modifier = Modifier.focusRequester(noneFocus).focusProperties { up = sectionFocus; down = listFocus; left = allFocus; right = Cancel },
                        )
                        Text(
                            stringResource(R.string.iptv_categories_count, com.enigmacast.domain.xtream.IptvCategoryFilter.shown(page.categories, page.hidden), page.categories.size),
                            color = EnigmaColors.Text, fontSize = EnigmaType.SettingsDescription, maxLines = 1,
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
            Text(stringResource(R.string.iptv_subtitles_title), color = EnigmaColors.Text, fontSize = EnigmaType.SettingsTitle, fontWeight = EnigmaType.Strong)
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
                    color = EnigmaColors.Text, fontSize = EnigmaType.SettingsDescription, maxLines = 1,
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


/** Settings › IPTV › Hidden channels: show one again (OK) or all of them. */
@Composable
private fun IptvHiddenChannelsDialog(channels: List<IptvSettingsViewModel.HiddenChannel>, model: IptvSettingsViewModel) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = model::closeHiddenChannels,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        com.enigmacast.ui.theme.NavigationSoundsForWindow()
        val allFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val listFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        val Cancel = androidx.compose.ui.focus.FocusRequester.Cancel
        androidx.compose.runtime.LaunchedEffect(channels.isEmpty()) {
            if (channels.isNotEmpty()) allFocus.requestWhenReady()
        }
        androidx.compose.foundation.layout.Column(
            Modifier
                .width(460.dp)
                .heightIn(max = 520.dp)
                .skinSurface(EnigmaColors.Selected, com.enigmacast.ui.theme.skinShape(14.dp))
                .padding(16.dp)
                .focusable(channels.isEmpty()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.iptv_hidden_channels), color = EnigmaColors.Text, fontSize = EnigmaType.SettingsTitle, fontWeight = com.enigmacast.ui.theme.EnigmaType.Strong)
            if (channels.isEmpty()) {
                Text(stringResource(R.string.iptv_hidden_channels_none), color = EnigmaColors.Muted, fontSize = EnigmaType.SettingsDescription)
            } else {
                com.enigmacast.ui.theme.CompactButton(
                    stringResource(R.string.iptv_show_all_channels),
                    model::showAllChannels,
                    modifier = Modifier.focusRequester(allFocus).focusProperties { up = Cancel; left = Cancel; right = Cancel; down = listFocus },
                    icon = androidx.compose.material.icons.Icons.Filled.Check,
                )
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    itemsIndexed(channels, key = { _, c -> c.source + "\u0000" + c.key }) { index, c ->
                        ValueRow(
                            title = c.name,
                            value = stringResource(R.string.iptv_channel_show),
                            onClick = { model.showChannel(c) },
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(listFocus) else Modifier)
                                .focusProperties {
                                    left = Cancel; right = Cancel
                                    if (index == 0) up = allFocus
                                    if (index == channels.lastIndex) down = Cancel
                                },
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun iptvAccountLine(info: XtreamAccountInfo): String {
    val nowSec = System.currentTimeMillis() / 1000
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    val parts = ArrayList<String>()
    if (info.status.isNotBlank()) parts += info.status
    if (info.isTrial) parts += stringResource(R.string.iptv_account_trial)
    info.expDateEpochSec?.let { exp ->
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(Instant.ofEpochSecond(exp).atZone(ZoneId.systemDefault()))
        val days = info.daysLeft(nowSec)
        val expiry = when {
            info.isExpired(nowSec) -> stringResource(R.string.iptv_account_expired)
            days == 0L -> stringResource(R.string.iptv_account_expires_today, date)
            days == 1L -> stringResource(R.string.iptv_account_one_day, date)
            days != null -> stringResource(R.string.iptv_account_days_left, days.toInt(), date)
            else -> stringResource(R.string.iptv_account_expires, date)
        }
        parts += expiry
    }
    if (info.activeCons != null || info.maxConnections != null) {
        parts += stringResource(
            R.string.iptv_account_connections,
            info.activeCons ?: 0,
            info.maxConnections ?: 0,
        )
    }
    return parts.joinToString("  ·  ")
}
