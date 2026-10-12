package com.enigmacast.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.enigmacast.BuildConfig
import com.enigmacast.R
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.model.OsdInfoFields
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.RecordingTarget
import com.enigmacast.domain.network.TranscodePreset
import com.enigmacast.domain.model.StreamMode
import com.enigmacast.domain.power.PowerEvent
import com.enigmacast.locale.AppLanguage
import com.enigmacast.locale.AppLanguageStore
import com.enigmacast.playback.AudioSync
import com.enigmacast.ui.components.EnigmaCastLogo
import com.enigmacast.ui.components.EnigmaNavigationDrawer
import com.enigmacast.ui.components.EnigmaScreenTransition
import com.enigmacast.ui.connection.AutodetectState
import com.enigmacast.ui.connection.ConnectionViewModel
import com.enigmacast.ui.osd.OsdInfoSettings
import com.enigmacast.ui.power.ReceiverPowerPanel
import com.enigmacast.ui.power.ReceiverPowerViewModel
import com.enigmacast.ui.power.powerStateColor
import com.enigmacast.ui.remote.EnigmaVideoTexture
import com.enigmacast.ui.remote.GuideActions
import com.enigmacast.ui.remote.GuideDetails
import com.enigmacast.ui.remote.GuideTimelineGrid
import com.enigmacast.ui.remote.ProgrammeDialog
import com.enigmacast.ui.remote.RemoteDisplayViewModel
import com.enigmacast.ui.remote.rememberGuideClock
import com.enigmacast.ui.settings.SettingsViewModel
import com.enigmacast.ui.theme.ChannelsLoadingDialog
import com.enigmacast.ui.theme.ChoiceRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.edgeLeft
import com.enigmacast.ui.theme.focusListRow
import com.enigmacast.ui.theme.wrapListEnds
import com.enigmacast.ui.theme.EnigmaIcons
import com.enigmacast.ui.theme.FeedbackSnackbar
import com.enigmacast.ui.theme.InfoRow
import com.enigmacast.ui.theme.LocalEdgeLeft
import com.enigmacast.ui.theme.NavItem
import com.enigmacast.ui.theme.PanelHeader
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.recording.RecordConfirmDialog
import com.enigmacast.ui.remote.RadioNowPlaying
import com.enigmacast.ui.search.SearchPanel
import com.enigmacast.ui.search.SearchViewModel
import com.enigmacast.ui.theme.StatusPill
import com.enigmacast.ui.theme.StepperRow
import com.enigmacast.ui.theme.ToggleRow
import com.enigmacast.ui.theme.TvConfirmDialog
import com.enigmacast.ui.theme.TvFieldKind
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.util.UiText
import com.enigmacast.util.asString
import com.enigmacast.util.isNotEmpty
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DashSection {
    Home,
    Stream,
    Guide,
    Radio,
    Search,
    MyItems,
    Receiver,
    Settings,
}

/** Where Back goes while a column to the right of the main menu has focus. */
private class DashBack {
    var target: FocusRequester? = null
}

private enum class SettingPage {
    Receivers,
    Remote,
    Playback,
    Osd,
    RcInfo,
    General,
    Info,
    Language,
}

@Composable
fun DashboardScreen(
    showMiniPlayer: Boolean,
    onStream: () -> Unit,
    onMiniPlayer: () -> Unit,
    onExit: () -> Unit,
    onBindBack: ((() -> Boolean)?) -> Unit,
    focusPlayingChannel: Boolean = false,
    onPlayingChannelFocused: () -> Unit = {},
    returnToMyItemsId: String? = null,
    onMyItemsFocused: () -> Unit = {},
    updatePromptShown: Boolean = false,
    focusLanguageSetting: Boolean = false,
    onLanguageSettingFocused: () -> Unit = {},
    returnToSettingsHub: Boolean = false,
    onSettingsHubShown: () -> Unit = {},
    onSignalMeter: () -> Unit = {},
    onOscam: () -> Unit = {},
    onChooseLanguage: (AppLanguage) -> Unit = {},
    pendingSearchQuery: String? = null,
    pendingSearchPlay: Boolean = false,
    pendingSearchToken: Int = 0,
    onAssistantSearchConsumed: () -> Unit = {},
    guideModel: StreamGuideViewModel = hiltViewModel(),
    powerModel: ReceiverPowerViewModel = hiltViewModel(),
    searchModel: SearchViewModel = hiltViewModel(),
) {
    // After a language change the activity is recreated; reopen Settings › Language then.
    // Back from a recording opens My Items on that row.
    var sectionEnter by remember { mutableIntStateOf(0) }
    var section by remember {
        mutableStateOf(
            when {
                focusLanguageSetting || returnToSettingsHub -> DashSection.Settings
                returnToMyItemsId != null -> DashSection.MyItems
                focusPlayingChannel -> DashSection.Stream
                else -> DashSection.Home
            },
        )
    }
    var navFocused by remember { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    val homeFocus = remember { FocusRequester() }
    val homeContentFocus = remember { FocusRequester() }
    val streamFocus = remember { FocusRequester() }
    val guideFocus = remember { FocusRequester() }
    val guideGridFocus = remember { FocusRequester() }
    val radioFocus = remember { FocusRequester() }
    val radioListFocus = remember { FocusRequester() }
    var streamItemFocused by remember { mutableStateOf(false) }
    var guideItemFocused by remember { mutableStateOf(false) }
    var radioItemFocused by remember { mutableStateOf(false) }
    val channelFocus = remember { FocusRequester() }
    val searchMenuFocus = remember { FocusRequester() }
    var searchActive by remember { mutableStateOf(false) }
    var searchEnterToken by remember { mutableIntStateOf(0) }
    val myItemsMenuFocus = remember { FocusRequester() }
    val myItemsListFocus = remember { FocusRequester() }
    // Recordings stay unfocusable until Right (or a return from playback/dialog) enters the list,
    // so opening Mijn Items does not pull the highlight onto the first row.
    var myItemsListActive by remember { mutableStateOf(returnToMyItemsId != null) }
    var myItemsEnterToken by remember { mutableIntStateOf(0) }
    val receiverMenuFocus = remember { FocusRequester() }
    val receiverPageFocus = remember { FocusRequester() }
    val settingsMenuFocus = remember { FocusRequester() }
    val settingsPageFocus = remember { FocusRequester() }
    /** Address form is open; focus landing on the main menu must not leave Settings. */
    var settingsFormOpen by remember { mutableStateOf(false) }
    val bouquetFocus = remember { FocusRequester() }
    val dashBack = remember { DashBack() }
    val retreat: (FocusRequester) -> Unit = { dashBack.target = it }
    val myItemsModel: MyItemsViewModel = hiltViewModel()
    fun sectionMenu(): FocusRequester = when (section) {
        DashSection.Home -> homeFocus
        DashSection.Stream -> streamFocus
        DashSection.Guide -> guideFocus
        DashSection.Radio -> radioFocus
        DashSection.Search -> searchMenuFocus
        DashSection.MyItems -> myItemsMenuFocus
        DashSection.Receiver -> receiverMenuFocus
        DashSection.Settings -> settingsMenuFocus
    }
    DisposableEffect(contentFocused, section, guideModel.askWake) {
        onBindBack {
            if (guideModel.askWake) {
                guideModel.declineWake()
                true
            } else if (contentFocused) {
                // One column back: page to its submenu item, submenu (or a page without one) to the main item.
                runCatching { (dashBack.target ?: sectionMenu()).requestFocus() }
                true
            } else {
                false
            }
        }
        onDispose { onBindBack(null) }
    }
    LaunchedEffect(Unit) {
        when {
            returnToSettingsHub -> {
                settingsMenuFocus.requestWhenReady()
                onSettingsHubShown()
            }
            focusLanguageSetting || returnToMyItemsId != null -> Unit
            focusPlayingChannel -> streamFocus.requestFocus()
            else -> homeFocus.requestFocus()
        }
    }
    LaunchedEffect(returnToMyItemsId) {
        val id = returnToMyItemsId ?: return@LaunchedEffect
        myItemsListActive = true
        myItemsModel.focusRow(id)
        myItemsListFocus.requestWhenReady()
        onMyItemsFocused()
    }
    LaunchedEffect(searchEnterToken) {
        if (searchEnterToken == 0) return@LaunchedEffect
        searchActive = true
    }
    LaunchedEffect(pendingSearchToken) {
        val q = pendingSearchQuery ?: return@LaunchedEffect
        if (pendingSearchToken == 0) return@LaunchedEffect
        section = DashSection.Search
        searchActive = true
        searchEnterToken += 1
        searchModel.applyExternalQuery(
            value = q,
            playFirst = pendingSearchPlay,
            onPlayed = onStream,
            onBouquet = { bouquet ->
                guideModel.openBouquet(bouquet)
                section = if (BouquetChannels.isRadioBouquet(bouquet.reference)) DashSection.Radio else DashSection.Stream
            },
            prepare = guideModel::releaseBouquetPin,
        )
        onAssistantSearchConsumed()
    }
    LaunchedEffect(myItemsEnterToken) {
        if (myItemsEnterToken == 0) return@LaunchedEffect
        myItemsListActive = true
        myItemsListFocus.requestWhenReady()
    }
    LaunchedEffect(myItemsModel.focusToken, myItemsModel.pendingDelete, myItemsModel.selected) {
        if (!myItemsModel.holdList) return@LaunchedEffect
        if (myItemsModel.selected != null || myItemsModel.pendingDelete != null) return@LaunchedEffect
        myItemsListActive = true
        section = DashSection.MyItems
        myItemsListFocus.requestWhenReady()
    }
    fun openSection(target: DashSection) {
        val keepList = myItemsModel.holdList && section == DashSection.MyItems && target != DashSection.MyItems
        val keepSettings = settingsFormOpen && target != DashSection.Settings
        if (!keepList && !keepSettings) section = target
    }
    /** OK on a side-menu item opens that section and moves focus to its first entry. */
    fun enterSection(target: DashSection) {
        val keepList = myItemsModel.holdList && section == DashSection.MyItems && target != DashSection.MyItems
        if (keepList || settingsFormOpen) return
        if (target == DashSection.Guide) guideModel.focusFirstInGrid()
        openSection(target)
        sectionEnter += 1
    }
    LaunchedEffect(Unit) {
        while (true) {
            powerModel.poll()
            delay(powerModel.pollIntervalMs)
        }
    }
    LaunchedEffect(Unit) {
        powerModel.events.collect { event ->
            when (event) {
                PowerEvent.WentToStandby -> guideModel.onReceiverOff(UiText.res(R.string.power_receiver_off_now))
                is PowerEvent.GoingOffline -> guideModel.onReceiverGoingOffline(
                    UiText.res(if (event.action == PowerAction.DEEP_STANDBY) R.string.power_receiver_switched_off else R.string.power_receiver_restarting),
                )
                PowerEvent.WokeUp -> guideModel.onReceiverOn()
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(EnigmaColors.Canvas)) {
        val drawerExpanded = navFocused || !contentFocused
        Row(modifier = Modifier.fillMaxSize()) {
            EnigmaNavigationDrawer(
                expanded = drawerExpanded,
                modifier = Modifier.onFocusChanged { navFocused = it.hasFocus },
            ) {
                EnigmaCastLogo(expanded = drawerExpanded)
                if (drawerExpanded) {
                    StatusPill(
                        text = powerModel.stateLabel.asString(),
                        color = powerStateColor(powerModel.powerState, powerModel.restarting),
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                    )
                }
                NavItem(
                    icon = Icons.Filled.Home,
                    label = stringResource(R.string.nav_home),
                    selected = section == DashSection.Home,
                    showLabel = drawerExpanded,
                    staggerIndex = 0,
                    modifier = Modifier
                        .focusRequester(homeFocus)
                        .focusProperties { right = if (section == DashSection.Home) homeContentFocus else FocusRequester.Cancel },
                    onClick = { enterSection(DashSection.Home) },
                )
                NavItem(
                    icon = EnigmaIcons.Tv,
                    label = stringResource(R.string.nav_live),
                    selected = section == DashSection.Stream,
                    showLabel = drawerExpanded,
                    staggerIndex = 1,
                    modifier = Modifier
                        .focusRequester(streamFocus)
                        .onFocusChanged { streamItemFocused = it.isFocused }
                        .focusProperties { right = if (section == DashSection.Stream) bouquetFocus else FocusRequester.Cancel },
                    onClick = { enterSection(DashSection.Stream) },
                )
                NavItem(
                    icon = EnigmaIcons.Guide,
                    label = stringResource(R.string.nav_guide),
                    selected = section == DashSection.Guide,
                    showLabel = drawerExpanded,
                    staggerIndex = 2,
                    modifier = Modifier
                        .focusRequester(guideFocus)
                        .onFocusChanged { guideItemFocused = it.isFocused }
                        .focusProperties { right = if (section == DashSection.Guide) guideGridFocus else FocusRequester.Cancel },
                    onClick = { enterSection(DashSection.Guide) },
                )
                NavItem(
                    icon = EnigmaIcons.Radio,
                    label = stringResource(R.string.nav_radio),
                    selected = section == DashSection.Radio,
                    showLabel = drawerExpanded,
                    staggerIndex = 3,
                    modifier = Modifier
                        .focusRequester(radioFocus)
                        .onFocusChanged { radioItemFocused = it.isFocused }
                        .focusProperties { right = if (section == DashSection.Radio) bouquetFocus else FocusRequester.Cancel },
                    onClick = { enterSection(DashSection.Radio) },
                )
                NavItem(
                    icon = Icons.Filled.Search,
                    label = stringResource(R.string.nav_search),
                    selected = section == DashSection.Search,
                    showLabel = drawerExpanded,
                    staggerIndex = 4,
                    modifier = Modifier
                        .focusRequester(searchMenuFocus)
                        .onFocusChanged { if (it.isFocused) searchActive = false }
                        .focusProperties { right = FocusRequester.Cancel }
                        .onPreviewKeyEvent { event ->
                            if (section != DashSection.Search) return@onPreviewKeyEvent false
                            if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionRight) return@onPreviewKeyEvent false
                            searchActive = true
                            searchEnterToken += 1
                            true
                        },
                    onClick = { enterSection(DashSection.Search) },
                )
                NavItem(
                    icon = EnigmaIcons.Record,
                    label = stringResource(R.string.nav_my_items),
                    selected = section == DashSection.MyItems,
                    showLabel = drawerExpanded,
                    staggerIndex = 5,
                    modifier = Modifier
                        .focusRequester(myItemsMenuFocus)
                        .onFocusChanged {
                            if (it.isFocused && !myItemsModel.holdList && returnToMyItemsId == null) {
                                myItemsListActive = false
                            }
                        }
                        .focusProperties { right = FocusRequester.Cancel }
                        .onPreviewKeyEvent { event ->
                            if (section != DashSection.MyItems) return@onPreviewKeyEvent false
                            if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionRight) return@onPreviewKeyEvent false
                            myItemsListActive = true
                            myItemsEnterToken += 1
                            true
                        },
                    onClick = { enterSection(DashSection.MyItems) },
                )
                NavItem(
                    icon = EnigmaIcons.Power,
                    label = stringResource(R.string.nav_receiver),
                    selected = section == DashSection.Receiver,
                    showLabel = drawerExpanded,
                    staggerIndex = 6,
                    modifier = Modifier
                        .focusRequester(receiverMenuFocus)
                        .focusProperties { right = if (section == DashSection.Receiver) receiverPageFocus else FocusRequester.Cancel },
                    onClick = { enterSection(DashSection.Receiver) },
                )
                NavItem(
                    icon = Icons.Filled.Settings,
                    label = stringResource(R.string.nav_settings),
                    selected = section == DashSection.Settings,
                    showLabel = drawerExpanded,
                    staggerIndex = 7,
                    modifier = Modifier
                        .focusRequester(settingsMenuFocus)
                        .focusProperties { right = if (section == DashSection.Settings) settingsPageFocus else FocusRequester.Cancel },
                    onClick = { enterSection(DashSection.Settings) },
                )
                Spacer(Modifier.height(8.dp))
                NavItem(
                    icon = Icons.Filled.Close,
                    label = stringResource(R.string.nav_exit),
                    selected = false,
                    showLabel = drawerExpanded,
                    staggerIndex = 8,
                    onClick = onExit,
                )
                Spacer(Modifier.weight(1f))
                if (drawerExpanded) {
                    Text(
                        "${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}",
                        color = EnigmaColors.Subtle,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(EnigmaColors.Canvas)
                    .padding(start = 12.dp, top = 28.dp, end = 28.dp, bottom = 24.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onFocusChanged { contentFocused = it.hasFocus },
                ) {
                    EnigmaScreenTransition(targetState = section, modifier = Modifier.fillMaxSize()) { shown ->
                    when (shown) {
                        DashSection.Home -> {
                        LaunchedEffect(sectionEnter) {
                            if (sectionEnter == 0) return@LaunchedEffect
                            homeContentFocus.requestWhenReady()
                        }
                        com.enigmacast.ui.home.FilmHomePanel(
                            contentFocus = homeContentFocus,
                            menuFocus = homeFocus,
                            onRetreatTo = retreat,
                            onWatch = { entry ->
                                val known = guideModel.channels.firstOrNull {
                                    BouquetChannels.sameService(it.reference, entry.reference)
                                } ?: entry
                                guideModel.tune(known, onStream, onStream)
                            },
                            onContinue = { last ->
                                val entry = guideModel.channels.firstOrNull {
                                    BouquetChannels.sameService(it.reference, last.reference)
                                } ?: BouquetEntry(name = last.name, reference = last.reference)
                                guideModel.tune(entry, onStream, onStream)
                            },
                        )
                        }
                        DashSection.Stream -> LiveBrowser(
                            showMiniPlayer = showMiniPlayer,
                            listFocus = channelFocus,
                            bouquetFocus = bouquetFocus,
                            menuFocus = streamFocus,
                            menuFocused = { streamItemFocused },
                            focusPlayingChannel = focusPlayingChannel,
                            onPlayingChannelFocused = onPlayingChannelFocused,
                            onMiniPlayer = onMiniPlayer,
                            onFullscreen = onStream,
                            medium = GuideMedium.Television,
                            guideModel = guideModel,
                            enterToken = sectionEnter,
                            onRetreatTo = retreat,
                        )
                        DashSection.Guide -> {
                        LaunchedEffect(sectionEnter) {
                            if (sectionEnter == 0) return@LaunchedEffect
                            guideModel.focusFirstInGrid()
                            guideGridFocus.requestWhenReady()
                        }
                        GuidePanel(
                            showMiniPlayer,
                            guideGridFocus,
                            bouquetFocus,
                            { guideItemFocused },
                            focusPlayingChannel,
                            onPlayingChannelFocused,
                            guideFocus,
                            onMiniPlayer,
                            onStream,
                            guideModel,
                            onRetreatTo = retreat,
                        )
                        }
                        DashSection.Radio -> LiveBrowser(
                            showMiniPlayer = showMiniPlayer,
                            listFocus = radioListFocus,
                            bouquetFocus = bouquetFocus,
                            menuFocus = radioFocus,
                            menuFocused = { radioItemFocused },
                            focusPlayingChannel = false,
                            onPlayingChannelFocused = {},
                            onMiniPlayer = onMiniPlayer,
                            onFullscreen = onStream,
                            medium = GuideMedium.Radio,
                            guideModel = guideModel,
                            enterToken = sectionEnter,
                            onRetreatTo = retreat,
                        )
                        DashSection.Search -> {
                        LaunchedEffect(sectionEnter) {
                            if (sectionEnter == 0) return@LaunchedEffect
                            searchActive = true
                            searchEnterToken += 1
                        }
                        SearchPanel(
                            rowsFocusable = searchActive,
                            focusToken = searchEnterToken,
                            menuFocus = searchMenuFocus,
                            onPrepare = guideModel::releaseBouquetPin,
                            onPlayed = onStream,
                            onBouquet = { bouquet ->
                                guideModel.openBouquet(bouquet)
                                section = if (BouquetChannels.isRadioBouquet(bouquet.reference)) DashSection.Radio else DashSection.Stream
                            },
                            viewModel = searchModel,
                            onRetreatTo = retreat,
                            modifier = Modifier.fillMaxSize(),
                        )
                        }
                        DashSection.MyItems -> {
                        LaunchedEffect(sectionEnter) {
                            if (sectionEnter == 0) return@LaunchedEffect
                            myItemsListActive = true
                            myItemsEnterToken += 1
                        }
                        MyItemsPanel(
                            listFocus = myItemsListFocus,
                            menuFocus = myItemsMenuFocus,
                            rowsFocusable = myItemsListActive,
                            onPlay = onStream,
                            onRetreatTo = retreat,
                            modifier = Modifier.fillMaxSize(),
                        )
                        }
                        DashSection.Receiver -> {
                        LaunchedEffect(sectionEnter) {
                            if (sectionEnter == 0) return@LaunchedEffect
                            receiverPageFocus.requestWhenReady()
                        }
                        ReceiverPowerPanel(
                            pageFocus = receiverPageFocus,
                            menuFocus = receiverMenuFocus,
                            onRetreatTo = retreat,
                            viewModel = powerModel,
                            modifier = Modifier.fillMaxSize(),
                        )
                        }
                        DashSection.Settings -> {
                        LaunchedEffect(sectionEnter) {
                            if (sectionEnter == 0) return@LaunchedEffect
                            settingsPageFocus.requestWhenReady()
                        }
                        SettingsHub(
                            settingsPageFocus,
                            settingsMenuFocus,
                            focusLanguageSetting,
                            onLanguageSettingFocused,
                            onChooseLanguage,
                            onSignalMeter = onSignalMeter,
                            onOscam = onOscam,
                            onFormActive = { settingsFormOpen = it },
                            onRetreatTo = retreat,
                            modifier = Modifier.fillMaxSize(),
                        )
                        }
                    }
                    }
                }
            }
        }
        if (guideModel.loadingChannels) {
            ChannelsLoadingDialog(
                guideModel.loadingLabel.asString().ifBlank { stringResource(R.string.load_channels) },
                guideModel.loadingProgress,
            )
        }
        guideModel.recordAsk?.let { ask ->
            RecordConfirmDialog(ask, onConfirm = guideModel::confirmRecord, onDismiss = guideModel::dismissRecord)
        }
        // Wait until the update prompt is closed: a second dialog window would take its focus.
        if (guideModel.askWake && !updatePromptShown) {
            TvConfirmDialog(
                title = stringResource(R.string.wake_title),
                message = if (guideModel.waking) stringResource(R.string.wake_busy) else stringResource(R.string.wake_question),
                confirmLabel = stringResource(R.string.wake_yes),
                dismissLabel = stringResource(R.string.no),
                onConfirm = guideModel::confirmWake,
                onDismiss = guideModel::declineWake,
                busy = guideModel.waking,
            )
        }
        powerModel.feedback?.let { feedback ->
            LaunchedEffect(feedback.id) {
                delay(4_000)
                powerModel.dismissFeedback(feedback.id)
            }
            FeedbackSnackbar(
                message = feedback.message.asString(),
                isError = feedback.isError,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
            )
        }
    }
}

/**
 * TV Guide: the mini player with the highlighted programme next to it, and the programme
 * grid below. The view model moves the highlight. OK on air starts playback; a later
 * programme asks to record. Left on the programme on air opens the bouquets.
 */
@Composable
private fun GuidePanel(
    showMiniPlayer: Boolean,
    channelFocus: FocusRequester,
    bouquetFocus: FocusRequester,
    menuFocused: () -> Boolean,
    focusPlayingChannel: Boolean,
    onPlayingChannelFocused: () -> Unit,
    settingsFocus: FocusRequester,
    onMiniPlayer: () -> Unit,
    onFullscreen: () -> Unit,
    guideModel: StreamGuideViewModel,
    onRetreatTo: (FocusRequester) -> Unit,
) {
    BindGuide(GuideMedium.Television, guideModel)
    val grid by guideModel.grid.state.collectAsState()
    val channels = guideModel.channels
    val bouquets = guideModel.bouquets
    val tuned = guideModel.tunedReference
    val nowSec = rememberGuideClock()
    var bouquetsOpen by remember { mutableStateOf(false) }
    var bouquetOpenToken by remember { mutableIntStateOf(0) }
    var gridFocusToken by remember { mutableIntStateOf(0) }
    var gridFocused by remember { mutableStateOf(false) }
    LaunchedEffect(bouquetsOpen, gridFocused) {
        if (gridFocused) onRetreatTo(if (bouquetsOpen) bouquetFocus else settingsFocus)
    }
    LaunchedEffect(gridFocusToken) {
        if (gridFocusToken == 0) return@LaunchedEffect
        channelFocus.requestWhenReady()
    }
    // The dashboard always starts with focus on the "Stream" menu item. After Back from the
    // full-screen player, put the highlight on the channel that was playing and the focus in
    // the grid as soon as the channels are there, unless the user already left "Stream".
    LaunchedEffect(focusPlayingChannel, channels.isNotEmpty()) {
        if (!focusPlayingChannel || channels.isEmpty()) return@LaunchedEffect
        withFrameNanos { } // let the initial "Stream" focus request land first
        guideModel.focusTunedInGrid()
        if (menuFocused()) gridFocusToken += 1
        onPlayingChannelFocused()
    }
    // A closed programme dialog gives the focus back to the grid.
    val prompt = guideModel.programmePrompt
    val autoSwitchKeys by guideModel.autoSwitchKeys.collectAsState()
    var hadPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(prompt == null) {
        if (prompt != null) {
            hadPrompt = true
        } else if (hadPrompt) {
            hadPrompt = false
            gridFocusToken += 1
        }
    }
    val focused = grid.focusedChannel
    val focusedCell = remember(grid, nowSec) { guideModel.grid.focused()?.second }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MiniPlayerHeight),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                if (showMiniPlayer && !guideModel.loadingChannels) MiniStreamPlayer(Modifier.fillMaxSize())
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                GuideDetails(
                    title = guideModel.bouquetName.ifBlank { stringResource(R.string.channels) },
                    channel = focused,
                    number = focused?.let(guideModel::numberOf) ?: 0,
                    cell = focusedCell,
                    nowSec = nowSec,
                    autoSwitch = focusedCell?.event?.let { it.programmeKey in autoSwitchKeys } == true,
                    loading = grid.loading,
                    failed = grid.failed,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                val message = guideModel.notice.takeIf { it.isNotEmpty() } ?: guideModel.status
                if (message.isNotEmpty()) {
                    Text(
                        message.asString(),
                        color = EnigmaColors.Accent,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (bouquetsOpen) {
                BouquetColumn(
                    bouquets = bouquets,
                    selectedReference = guideModel.selectedBouquetReference,
                    bouquetFocus = bouquetFocus,
                    settingsFocus = settingsFocus,
                    openToken = bouquetOpenToken,
                    onPreview = guideModel::previewBouquet,
                    onClose = {
                        bouquetsOpen = false
                        gridFocusToken += 1
                    },
                    onColumnFocus = { onRetreatTo(settingsFocus) },
                    modifier = Modifier.width(220.dp).fillMaxHeight(),
                )
            }
            GuideTimelineGrid(
                state = grid,
                playingReference = tuned,
                active = gridFocused,
                nowSec = nowSec,
                numberOf = guideModel::numberOf,
                picon = guideModel::picon,
                cachedPicon = guideModel::cachedPicon,
                autoSwitchKeys = autoSwitchKeys,
                recordingReferences = guideModel.recordingReferences,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(channelFocus)
                    .onFocusChanged {
                        gridFocused = it.isFocused
                        if (it.isFocused) onRetreatTo(if (bouquetsOpen) bouquetFocus else settingsFocus)
                    }
                    .onPreviewKeyEvent { event ->
                        gridKey(
                            event = event,
                            model = guideModel,
                            bouquetsOpen = bouquetsOpen,
                            onOpenBouquets = {
                                bouquetsOpen = true
                                bouquetOpenToken += 1
                            },
                            onFocusBouquets = { runCatching { bouquetFocus.requestFocus() } },
                            onMiniPlayer = onMiniPlayer,
                            onFullscreen = onFullscreen,
                        )
                    }
                    .focusable(),
            )
        }
    }
    val programmeActions = remember(guideModel) {
        GuideActions(
            onOpenChannelEpg = {},
            onChooseProgramme = { _, _ -> },
            onAutoSwitch = guideModel::toggleAutoSwitch,
            onRecord = guideModel::chooseRecord,
            onDismissProgramme = guideModel::dismissProgramme,
        )
    }
    prompt?.let { ProgrammeDialog(it, programmeActions) }
}

private val MiniPlayerHeight = 158.dp

/** Keys on the programme grid; Back is left to the dashboard (to the menu). */
private fun gridKey(
    event: KeyEvent,
    model: StreamGuideViewModel,
    bouquetsOpen: Boolean,
    onOpenBouquets: () -> Unit,
    onFocusBouquets: () -> Unit,
    onMiniPlayer: () -> Unit,
    onFullscreen: () -> Unit,
): Boolean {
    val key = event.key
    val confirm = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
    if (confirm) {
        // Act on release, like the buttons: a held OK must not tune and then go full screen.
        if (event.type == KeyEventType.KeyUp) model.chooseInGrid(onMiniPlayer, onFullscreen)
        return true
    }
    if (event.type != KeyEventType.KeyDown) return false
    when (key) {
        Key.DirectionUp -> model.moveGridRows(-1)
        Key.DirectionDown -> model.moveGridRows(1)
        Key.ChannelUp, Key.PageUp -> model.moveGridRows(-GRID_PAGE_ROWS)
        Key.ChannelDown, Key.PageDown -> model.moveGridRows(GRID_PAGE_ROWS)
        Key.DirectionRight -> model.moveGridTime(1)
        Key.DirectionLeft -> {
            if (model.moveGridTime(-1)) return true
            // Already on the programme on air: the bouquets (a held key does not open them).
            if (event.nativeKeyEvent.repeatCount > 0) return true
            if (bouquetsOpen) onFocusBouquets() else onOpenBouquets()
        }
        else -> return false
    }
    return true
}

private const val GRID_PAGE_ROWS = 6

@Composable
internal fun BouquetColumn(
    bouquets: List<BouquetEntry>,
    selectedReference: String,
    bouquetFocus: FocusRequester,
    settingsFocus: FocusRequester,
    openToken: Int,
    onPreview: (BouquetEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    requestFocus: Boolean = true,
    onColumnFocus: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    val lastFocus = remember { FocusRequester() }
    val lastIndex = bouquets.lastIndex
    val focusIndex = bouquets.indexOfFirst { it.reference == selectedReference }.let { if (it >= 0) it else 0 }
    LaunchedEffect(openToken, bouquets.size) {
        if (openToken == 0 || bouquets.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(focusIndex)
        if (requestFocus) bouquetFocus.requestWhenReady()
    }
    Column(modifier.onFocusChanged { if (it.hasFocus) onColumnFocus() }) {
        Text(
            stringResource(R.string.bouquets),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp, start = 8.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (bouquets.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(bouquetFocus)
                            .focusProperties { left = settingsFocus }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionRight) return@onPreviewKeyEvent false
                                onClose()
                                true
                            }
                            .focusable(),
                    )
                }
            } else {
                itemsIndexed(bouquets) { index, bouquet ->
                    BouquetRow(
                        name = bouquet.name,
                        selected = bouquet.reference == selectedReference,
                        onClick = onClose,
                        onFocus = { onPreview(bouquet) },
                        modifier = Modifier
                            .then(if (index == focusIndex) Modifier.focusRequester(bouquetFocus) else Modifier)
                            .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .then(if (index == lastIndex && lastIndex > 0) Modifier.focusRequester(lastFocus) else Modifier)
                            .focusProperties { left = settingsFocus }
                            .wrapListEnds(
                                index = index,
                                lastIndex = lastIndex,
                                onWrapToFirst = { scope.launch { firstFocus.focusListRow(listState, 0) } },
                                onWrapToLast = { scope.launch { lastFocus.focusListRow(listState, lastIndex) } },
                            )
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionRight) return@onPreviewKeyEvent false
                                onClose()
                                true
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun BouquetRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) EnigmaColors.Selected else EnigmaColors.Canvas,
            contentColor = if (selected) EnigmaColors.Cyan else Color.White,
            focusedContainerColor = EnigmaColors.Focus,
            focusedContentColor = EnigmaColors.OnFocus,
        ),
    ) {
        Text(
            name,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RcInfoPanel(contentFocus: FocusRequester, modifier: Modifier = Modifier) {
    Column(modifier.focusRequester(contentFocus).edgeLeft().focusable()) {
        PanelHeader(stringResource(R.string.rc_title), stringResource(R.string.rc_subtitle))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            SettingsCard(stringResource(R.string.rc_navigation), Modifier.weight(1f)) {
                RcRow(stringResource(R.string.rc_arrows), stringResource(R.string.rc_arrows_desc))
                RcRow(stringResource(R.string.rc_ok_short), stringResource(R.string.rc_ok_short_desc))
                RcRow(stringResource(R.string.rc_ok_long), stringResource(R.string.rc_ok_long_desc))
                RcRow(stringResource(R.string.rc_back_short), stringResource(R.string.rc_back_short_desc))
                RcRow(stringResource(R.string.rc_back_long), stringResource(R.string.rc_back_long_desc))
                RcRow(stringResource(R.string.rc_back_menu), stringResource(R.string.rc_back_menu_desc))
            }
            SettingsCard(stringResource(R.string.rc_tv_buttons), Modifier.weight(1f)) {
                RcRow(stringResource(R.string.rc_info), stringResource(R.string.rc_info_desc))
                RcRow(stringResource(R.string.rc_guide), stringResource(R.string.rc_guide_desc))
                RcRow("P+ / P-", stringResource(R.string.rc_channel_desc))
                RcRow(stringResource(R.string.rc_colours), stringResource(R.string.rc_colours_desc))
                RcRow(stringResource(R.string.rc_blue_long), stringResource(R.string.rc_blue_long_desc))
                RcRow("0–9", stringResource(R.string.rc_digits_desc))
                RcRow("Play / Pause / Stop", stringResource(R.string.rc_play_desc))
            }
        }
    }
}

@Composable
private fun RcRow(button: String, action: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(button, color = EnigmaColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(140.dp))
        Text(action, color = EnigmaColors.Muted, fontSize = 14.sp)
    }
}

@Composable
private fun SettingsHub(
    pageFocus: FocusRequester,
    menuFocus: FocusRequester,
    focusLanguage: Boolean,
    onLanguageFocused: () -> Unit,
    onChooseLanguage: (AppLanguage) -> Unit,
    onSignalMeter: () -> Unit,
    onOscam: () -> Unit,
    onFormActive: (Boolean) -> Unit,
    onRetreatTo: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    connection: ConnectionViewModel = hiltViewModel(),
) {
    var page by remember { mutableStateOf(if (focusLanguage) SettingPage.Language else SettingPage.Receivers) }
    var pageEnter by remember { mutableIntStateOf(0) }
    val pageContentFocus = remember { FocusRequester() }
    val navFocus = remember { SettingPage.entries.associateWith { FocusRequester() } }
    var formActive by remember { mutableStateOf(false) }
    /** Shows the page and keeps focus on the submenu row. */
    fun showPage(target: SettingPage) {
        page = target
    }
    /** OK or Right on a submenu row moves into that page's first option. */
    fun enterPage(target: SettingPage) {
        page = target
        pageEnter += 1
    }
    val formFocus = remember { FocusRequester() }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Column(
        modifier.onFocusChanged { state ->
            val dialogOpen = connection.autodetectState !is AutodetectState.Hidden
            if (formActive && !state.hasFocus && !dialogOpen) {
                runCatching { formFocus.requestFocus() }
            }
        },
    ) {
        PanelHeader(stringResource(R.string.settings_title), settings.activeReceiver()?.let { "${it.name}  ·  ${it.host}" }.orEmpty())
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            CompositionLocalProvider(LocalEdgeLeft provides menuFocus) {
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .onFocusChanged { if (it.hasFocus) onRetreatTo(menuFocus) },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SettingNav(EnigmaIcons.Tv, stringResource(R.string.settings_receivers), page == SettingPage.Receivers, navFocus.getValue(SettingPage.Receivers), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Receivers) }) { enterPage(SettingPage.Receivers) }
                SettingNav(EnigmaIcons.Lock, stringResource(R.string.settings_remote), page == SettingPage.Remote, navFocus.getValue(SettingPage.Remote), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Remote) }) { enterPage(SettingPage.Remote) }
                SettingNav(Icons.Filled.PlayArrow, stringResource(R.string.settings_playback), page == SettingPage.Playback, navFocus.getValue(SettingPage.Playback), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Playback) }) { enterPage(SettingPage.Playback) }
                SettingNav(EnigmaIcons.Layers, stringResource(R.string.settings_osd), page == SettingPage.Osd, navFocus.getValue(SettingPage.Osd), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Osd) }) { enterPage(SettingPage.Osd) }
                SettingNav(EnigmaIcons.Speed, stringResource(R.string.signal_meter), false, null, null, menuFocus, pageContentFocus, opensPage = false, onSelect = onSignalMeter)
                SettingNav(EnigmaIcons.Sync, stringResource(R.string.oscam), false, null, null, menuFocus, pageContentFocus, opensPage = false, onSelect = onOscam)
                SettingNav(Icons.Filled.Info, stringResource(R.string.nav_rc_info), page == SettingPage.RcInfo, navFocus.getValue(SettingPage.RcInfo), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.RcInfo) }) { enterPage(SettingPage.RcInfo) }
                SettingNav(Icons.Filled.Settings, stringResource(R.string.settings_general), page == SettingPage.General, navFocus.getValue(SettingPage.General), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.General) }) { enterPage(SettingPage.General) }
                SettingNav(Icons.Filled.Info, stringResource(R.string.settings_info), page == SettingPage.Info, navFocus.getValue(SettingPage.Info), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Info) }) { enterPage(SettingPage.Info) }
                SettingNav(EnigmaIcons.Language, stringResource(R.string.settings_language), page == SettingPage.Language, navFocus.getValue(SettingPage.Language), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Language) }) { enterPage(SettingPage.Language) }
            }
            }
            CompositionLocalProvider(LocalEdgeLeft provides pageFocus) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .onFocusChanged { if (it.hasFocus) onRetreatTo(pageFocus) },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LaunchedEffect(pageEnter) {
                    if (pageEnter == 0) return@LaunchedEffect
                    pageContentFocus.requestWhenReady()
                }
                when (page) {
                    SettingPage.Receivers -> ReceiversSettingsPanel(
                        receivers = settings.receivers,
                        activeId = settings.activeReceiver()?.id,
                        onSelect = viewModel::selectReceiver,
                        onDelete = viewModel::removeReceiver,
                        menuAnchor = pageFocus,
                        contentFocus = pageContentFocus,
                        onFormActive = { active ->
                            formActive = active
                            onFormActive(active)
                        },
                        formFocus = formFocus,
                    )
                    SettingPage.Remote -> RemoteAccessSettingsPanel(contentFocus = pageContentFocus)
                    SettingPage.Playback -> PlaybackSettingsPanel(
                        settings.streamMode,
                        settings.playback.lowLatency,
                        settings.playback.autoReconnect,
                        settings.playback.hdmiPassthrough,
                        settings.playback.subtitlesEnabled,
                        settings.playback.audioDelayPcmMs,
                        settings.playback.audioDelayPassthroughMs,
                        settings.playback.recordingTarget,
                        settings.playback.recordingPreset,
                        viewModel,
                        contentFocus = pageContentFocus,
                    )
                    SettingPage.Osd -> OsdSettingsPanel(
                        settings.osd.format,
                        settings.osd.fastPolling,
                        settings.osd.transparencyPercent,
                        settings.osdInfo,
                        viewModel,
                        contentFocus = pageContentFocus,
                    )
                    SettingPage.RcInfo -> RcInfoPanel(pageContentFocus, Modifier.fillMaxWidth())
                    SettingPage.General -> GeneralSettingsPanel(settings.navigationSounds, settings.motionQuality, viewModel, pageContentFocus)
                    SettingPage.Info -> InfoSettingsPanel(settings, viewModel, pageContentFocus)
                    SettingPage.Language -> LanguageSettingsPanel(focusLanguage, onLanguageFocused, onChooseLanguage, pageContentFocus)
                }
            }
            }
        }
    }
}

@Composable
private fun SettingNav(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    itemFocus: FocusRequester?,
    entryFocus: FocusRequester?,
    menuFocus: FocusRequester,
    pageContentFocus: FocusRequester,
    opensPage: Boolean,
    onFocus: () -> Unit = {},
    onSelect: () -> Unit,
) {
    NavItem(
        icon = icon,
        label = label,
        selected = selected,
        onClick = onSelect,
        onFocus = onFocus,
        modifier = Modifier
            .then(if (itemFocus != null) Modifier.focusRequester(itemFocus) else Modifier)
            .then(if (selected && entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier)
            .focusProperties {
                left = menuFocus
                right = if (opensPage) pageContentFocus else FocusRequester.Cancel
            },
    )
}

@Composable
private fun PlaybackSettingsPanel(
    streamMode: StreamMode,
    fast: Boolean,
    autoReconnect: Boolean,
    hdmiPassthrough: Boolean,
    subtitlesEnabled: Boolean,
    audioDelayPcmMs: Int,
    audioDelayPassthroughMs: Int,
    recordingTarget: RecordingTarget,
    recordingPreset: TranscodePreset?,
    viewModel: SettingsViewModel,
    contentFocus: FocusRequester,
    remote: RemoteDisplayViewModel = hiltViewModel(),
) {
    val playback by remote.uiState.collectAsStateWithLifecycle()
    val passthroughActive by remote.audioPassthroughActive.collectAsStateWithLifecycle()
    SettingsCard(stringResource(R.string.record_target)) {
        ChoiceRow(
            title = stringResource(R.string.record_target),
            description = stringResource(R.string.record_target_desc),
            options = listOf(stringResource(R.string.record_target_tv), stringResource(R.string.record_target_receiver)),
            selectedIndex = if (recordingTarget == RecordingTarget.RECEIVER) 1 else 0,
            onSelect = { viewModel.setRecordingTarget(if (it == 1) RecordingTarget.RECEIVER else RecordingTarget.TV) },
            firstOptionModifier = Modifier.focusRequester(contentFocus),
        )
        val presets = TranscodePreset.entries
        ChoiceRow(
            title = stringResource(R.string.record_size),
            description = stringResource(R.string.record_size_desc),
            options = listOf(stringResource(R.string.record_size_original)) +
                presets.map { stringResource(R.string.quality_transcoded, it.height, it.bitrateKbps / 1000f) },
            selectedIndex = presets.indexOf(recordingPreset).let { if (it < 0) 0 else it + 1 },
            onSelect = { viewModel.setRecordingPreset(presets.getOrNull(it - 1)) },
        )
    }
    SettingsCard(stringResource(R.string.viewing_mode)) {
        ChoiceRow(
            title = stringResource(R.string.viewing_mode_title),
            description = if (streamMode == StreamMode.OWN_TUNER) {
                stringResource(R.string.viewing_mode_own_desc)
            } else {
                stringResource(R.string.viewing_mode_follow_desc)
            },
            options = listOf(stringResource(R.string.viewing_mode_own), stringResource(R.string.viewing_mode_follow)),
            selectedIndex = if (streamMode == StreamMode.OWN_TUNER) 0 else 1,
            onSelect = { viewModel.setStreamMode(if (it == 0) StreamMode.OWN_TUNER else StreamMode.FOLLOW_RECEIVER) },
        )
    }
    StreamTypeCard()
    SettingsCard(stringResource(R.string.picture)) {
        ChoiceRow(
            title = stringResource(R.string.buffer),
            description = if (fast) stringResource(R.string.buffer_fast_desc) else stringResource(R.string.buffer_stable_desc),
            options = listOf(stringResource(R.string.buffer_stable), stringResource(R.string.buffer_fast)),
            selectedIndex = if (fast) 1 else 0,
            onSelect = { viewModel.setLowLatency(it == 1) },
        )
    }
    SettingsCard(stringResource(R.string.sound)) {
        ChoiceRow(
            title = stringResource(R.string.audio_output),
            description = if (hdmiPassthrough) {
                stringResource(R.string.audio_output_hdmi_desc)
            } else {
                stringResource(R.string.audio_output_stereo_desc)
            },
            options = listOf(stringResource(R.string.stereo), stringResource(R.string.hdmi)),
            selectedIndex = if (hdmiPassthrough) 1 else 0,
            onSelect = { viewModel.setHdmiPassthrough(it == 1) },
        )
    }
    AudioSyncCard(
        pcmMs = audioDelayPcmMs,
        passthroughMs = audioDelayPassthroughMs,
        passthroughActive = passthroughActive,
        streamVisible = playback.playerState.playbackState == "READY" && playback.playerState.videoWidth > 0,
        player = { remote.player() },
        viewModel = viewModel,
    )
    SettingsCard(stringResource(R.string.subtitles)) {
        ChoiceRow(
            title = stringResource(R.string.dvb_subtitles),
            description = if (playback.subtitlesAvailable) {
                stringResource(R.string.subtitles_available_desc)
            } else {
                stringResource(R.string.subtitles_none_desc)
            },
            options = listOf(stringResource(R.string.off), stringResource(R.string.on)),
            selectedIndex = if (subtitlesEnabled) 1 else 0,
            onSelect = { viewModel.setSubtitlesEnabled(it == 1) },
        )
    }
    SettingsCard(stringResource(R.string.connection)) {
        ToggleRow(
            title = stringResource(R.string.auto_resume),
            description = stringResource(R.string.auto_resume_desc),
            checked = autoReconnect,
            onCheckedChange = viewModel::setAutoReconnect,
        )
    }
}

/**
 * Audio delay per output. While a stream plays, a small preview sits next to the
 * controls so the user can watch lips while stepping; each step applies at once.
 */
@Composable
private fun AudioSyncCard(
    pcmMs: Int,
    passthroughMs: Int,
    passthroughActive: Boolean?,
    streamVisible: Boolean,
    player: () -> androidx.media3.common.Player,
    viewModel: SettingsViewModel,
) {
    SettingsCard(stringResource(R.string.audio_sync)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.audio_sync_desc),
                    color = EnigmaColors.Muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                AudioDelayRow(stringResource(R.string.stereo), pcmMs, passthroughActive == false, passthrough = false, viewModel)
                AudioDelayRow(stringResource(R.string.hdmi_passthrough), passthroughMs, passthroughActive == true, passthrough = true, viewModel)
            }
            if (streamVisible) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp, end = 6.dp)
                        .width(240.dp)
                        .aspectRatio(16f / 9f)
                        .background(Color.Black, RoundedCornerShape(8.dp)),
                ) {
                    EnigmaVideoTexture(player(), emptyList(), Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun AudioDelayRow(
    title: String,
    valueMs: Int,
    active: Boolean,
    passthrough: Boolean,
    viewModel: SettingsViewModel,
) {
    StepperRow(
        title = title,
        value = AudioSync.label(valueMs),
        description = AudioSync.describe(valueMs).asString(),
        decreaseLabel = "−${AudioSync.STEP_MS}",
        increaseLabel = "+${AudioSync.STEP_MS}",
        onDecrease = { viewModel.stepAudioDelay(passthrough, -1) },
        onIncrease = { viewModel.stepAudioDelay(passthrough, 1) },
        onReset = { viewModel.resetAudioDelay(passthrough) },
        badge = if (active) stringResource(R.string.active_now) else null,
    )
}

/**
 * App language. Choosing one recreates the screen in that language; focus then comes
 * back here, on the chosen language.
 */
@Composable
private fun LanguageSettingsPanel(
    restoreFocus: Boolean,
    onFocusRestored: () -> Unit,
    onChoose: (AppLanguage) -> Unit,
    contentFocus: FocusRequester,
) {
    val context = LocalContext.current
    val current = remember { AppLanguageStore.current(context) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(restoreFocus) {
        if (!restoreFocus) return@LaunchedEffect
        focus.requestWhenReady()
        onFocusRestored()
    }
    SettingsCard(stringResource(R.string.language_card)) {
        ChoiceRow(
            title = stringResource(R.string.language_choice),
            description = stringResource(R.string.language_desc),
            options = AppLanguage.entries.map { it.nativeName },
            selectedIndex = AppLanguage.entries.indexOf(current),
            onSelect = { onChoose(AppLanguage.entries[it]) },
            firstOptionModifier = Modifier.focusRequester(contentFocus),
            selectedOptionModifier = Modifier.focusRequester(focus),
        )
    }
}

@Composable
private fun OsdSettingsPanel(
    format: String,
    fastPolling: Boolean,
    transparencyPercent: Int,
    info: OsdInfoFields,
    viewModel: SettingsViewModel,
    contentFocus: FocusRequester,
) {
    SettingsCard(stringResource(R.string.osd_transparency)) {
        StepperRow(
            title = stringResource(R.string.osd_transparency),
            description = stringResource(R.string.osd_transparency_desc),
            value = "$transparencyPercent%",
            decreaseLabel = "−10",
            increaseLabel = "+10",
            decreaseModifier = Modifier.focusRequester(contentFocus),
            onDecrease = { viewModel.setOsdTransparency(transparencyPercent - 10) },
            onIncrease = { viewModel.setOsdTransparency(transparencyPercent + 10) },
        )
    }
    OsdInfoSettings(info, viewModel::setOsdInfoFields)
    SettingsCard(stringResource(R.string.nav_osd_skins)) {
        Text(
            stringResource(R.string.coming_soon),
            color = EnigmaColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Text(
            stringResource(R.string.skins_detail),
            color = EnigmaColors.Muted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
    SettingsCard(stringResource(R.string.receiver_osd)) {
        ChoiceRow(
            title = stringResource(R.string.osd_format),
            description = stringResource(R.string.osd_format_desc),
            options = listOf("PNG", "JPG"),
            selectedIndex = if (format == "jpg") 1 else 0,
            onSelect = { viewModel.setOsdFormat(if (it == 1) "jpg" else "png") },
        )
        ToggleRow(
            title = stringResource(R.string.fast_refresh),
            description = stringResource(R.string.fast_refresh_desc),
            checked = fastPolling,
            onCheckedChange = viewModel::setFastPolling,
        )
    }
}

@Composable
private fun GeneralSettingsPanel(
    navigationSounds: Boolean,
    motionQuality: com.enigmacast.domain.model.MotionQuality,
    viewModel: SettingsViewModel,
    contentFocus: FocusRequester,
) {
    SettingsCard(stringResource(R.string.settings_interface)) {
        ToggleRow(
            title = stringResource(R.string.navigation_sounds),
            description = stringResource(R.string.navigation_sounds_desc),
            checked = navigationSounds,
            onCheckedChange = viewModel::setNavigationSounds,
            modifier = Modifier.focusRequester(contentFocus),
        )
        ChoiceRow(
            title = stringResource(R.string.motion_quality),
            description = stringResource(R.string.motion_quality_desc),
            options = listOf(stringResource(R.string.motion_premium), stringResource(R.string.motion_performance)),
            selectedIndex = if (motionQuality == com.enigmacast.domain.model.MotionQuality.PERFORMANCE) 1 else 0,
            onSelect = { index ->
                viewModel.setMotionQuality(
                    if (index == 1) com.enigmacast.domain.model.MotionQuality.PERFORMANCE
                    else com.enigmacast.domain.model.MotionQuality.PREMIUM,
                )
            },
        )
    }
    com.enigmacast.ui.home.HomeFilmSettingsCard()
    com.enigmacast.ui.home.DataSyncSettingsCard()
}

@Composable
private fun InfoSettingsPanel(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    contentFocus: FocusRequester,
) {
    SettingsCard(stringResource(R.string.app)) {
        InfoRow(stringResource(R.string.version), BuildConfig.VERSION_NAME)
        InfoRow(stringResource(R.string.build), BuildConfig.VERSION_CODE.toString())
    }
    SettingsCard(stringResource(R.string.diagnostics)) {
        ToggleRow(stringResource(R.string.debug_overlay), settings.debug.overlay, viewModel::setDebugOverlay, modifier = Modifier.focusRequester(contentFocus))
        ToggleRow(stringResource(R.string.verbose_logging), settings.debug.verboseLogging, viewModel::setVerboseLogging)
        ToggleRow(stringResource(R.string.osd_border), settings.osd.debugBorders, viewModel::setDebugBorders)
    }
}

@Composable
internal fun CompactField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    kind: TvFieldKind = TvFieldKind.TEXT,
    followEdge: Boolean = false,
) {
    TvTextField(label, value, onValueChange, modifier, kind = kind, followEdge = followEdge)
}

@Composable
internal fun MiniStreamPlayer(
    modifier: Modifier = Modifier,
    viewModel: RemoteDisplayViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val radio = ui.own.channel?.takeIf {
        !ui.playerState.filePlayback && BouquetChannels.isRadioBouquet(ui.own.bouquetReference)
    }
    Box(modifier) {
        if (radio != null) {
            RadioNowPlaying(
                channel = radio,
                guide = ui.own.guide,
                streamTitle = ui.playerState.streamTitle,
                streamArtist = ui.playerState.streamArtist,
                picon = viewModel::picon,
                cachedPicon = viewModel::cachedPicon,
                compact = true,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            EnigmaVideoTexture(viewModel.player(), ui.cues, Modifier.fillMaxSize())
        }
        val error = ui.playerState.error
        if (!error.isNullOrBlank()) {
            Text(
                error,
                color = EnigmaColors.Accent,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            )
        }
    }
}
