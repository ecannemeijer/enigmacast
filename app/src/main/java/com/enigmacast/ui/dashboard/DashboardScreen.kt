package com.enigmacast.ui.dashboard

import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.skinShape
import com.enigmacast.ui.theme.focusRequesterIf
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.foundation.focusGroup
import com.enigmacast.ui.theme.ValueRow
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

internal enum class DashSection {
    Home,
    Movies,
    Series,
    Soccer,
    Stream,
    Guide,
    Radio,
    Search,
    MyItems,
    Settings,
}

/** Where Back goes while a column to the right of the main menu has focus. */
private class DashBack {
    var target: FocusRequester? = null
}

/** Settings submenu, in display order (see README "Settings"). */
private enum class SettingPage {
    Account,
    Profiles,
    Receivers,
    Receiver,
    Playback,
    Viewing,
    Keys,
    Guide,
    Iptv,
    Recordings,
    Appearance,
    Remote,
    Soccer,
    About,
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
    onSettingsImport: () -> Unit = {},
    onXtream: () -> Unit = {},
    onRunSetup: () -> Unit = {},
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
                else -> DashSection.Home
            },
        )
    }
    var navFocused by remember { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    val homeFocus = remember { FocusRequester() }
    val homeContentFocus = remember { FocusRequester() }
    val moviesFocus = remember { FocusRequester() }
    val moviesContentFocus = remember { FocusRequester() }
    val seriesFocus = remember { FocusRequester() }
    val seriesContentFocus = remember { FocusRequester() }
    val soccerFocus = remember { FocusRequester() }
    val soccerContentFocus = remember { FocusRequester() }
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
    val settingsMenuFocus = remember { FocusRequester() }
    val settingsPageFocus = remember { FocusRequester() }
    /** Address form is open; focus landing on the main menu must not leave Settings. */
    var settingsFormOpen by remember { mutableStateOf(false) }
    val bouquetFocus = remember { FocusRequester() }
    val tvMenuFocus = remember { FocusRequester() }
    /** The TV / Library flyout, open for that group (null when closed), and where it sits. */
    var flyout by remember { mutableStateOf<DashGroup?>(null) }
    val flyoutAnchor = remember { androidx.compose.runtime.mutableStateMapOf<DashGroup, androidx.compose.ui.geometry.Offset>() }
    val libraryMenuFocus = remember { FocusRequester() }
    val tabContext = androidx.compose.ui.platform.LocalContext.current
    val tabMemory = remember { DashTabMemory(tabContext) }
    /** The tab of [target] in its group's tab row (the old menu item's requester moved there). */
    fun tabFocus(target: DashSection): FocusRequester = when (target) {
        DashSection.Stream -> streamFocus
        DashSection.Guide -> guideFocus
        DashSection.Movies -> moviesFocus
        DashSection.Series -> seriesFocus
        DashSection.Radio -> radioFocus
        DashSection.MyItems -> myItemsMenuFocus
        DashSection.Search -> searchMenuFocus
        else -> FocusRequester.Default
    }
    val dashBack = remember { DashBack() }
    val retreat: (FocusRequester) -> Unit = { dashBack.target = it }
    val myItemsModel: MyItemsViewModel = hiltViewModel()
    val accountModel: com.enigmacast.ui.account.AccountViewModel = hiltViewModel()
    val account by accountModel.account.collectAsState()
    val premium = accountModel.isPremium(account)
    val xtreamModel: com.enigmacast.ui.xtream.XtreamViewModel = hiltViewModel()
    val xtreamAvailable = xtreamModel.detection.collectAsState().value?.available == true
    /** Free accounts: these menu items show a lock and open the Premium dialog. */
    fun lockedFeature(target: DashSection): com.enigmacast.domain.account.PremiumFeature? = if (premium) null else when (target) {
        DashSection.Guide -> com.enigmacast.domain.account.PremiumFeature.EPG
        DashSection.Movies -> com.enigmacast.domain.account.PremiumFeature.MOVIES
        DashSection.Series -> com.enigmacast.domain.account.PremiumFeature.SERIES
        DashSection.Soccer -> com.enigmacast.domain.account.PremiumFeature.SOCCER
        else -> null
    }
    // The plan ran out while a Premium section was open: back to Home.
    LaunchedEffect(premium) {
        if (lockedFeature(section) != null) section = DashSection.Home
    }
    fun sectionMenuOf(): FocusRequester = when (section) {
        DashSection.Home -> homeFocus
        DashSection.Movies -> moviesFocus
        DashSection.Series -> seriesFocus
        DashSection.Soccer -> soccerFocus
        DashSection.Stream -> streamFocus
        DashSection.Guide -> guideFocus
        DashSection.Radio -> radioFocus
        DashSection.Search -> searchMenuFocus
        DashSection.MyItems -> myItemsMenuFocus
        DashSection.Settings -> settingsMenuFocus
    }
    fun sectionMenu(): FocusRequester = when (DashGroups.groupOf(section)) {
        DashGroup.TV -> tvMenuFocus
        DashGroup.LIBRARY -> libraryMenuFocus
        null -> sectionMenuOf()
    }
    DisposableEffect(contentFocused, section, guideModel.askWake, flyout) {
        onBindBack {
            if (flyout != null) {
                val group = flyout
                flyout = null
                runCatching { (if (group == DashGroup.LIBRARY) libraryMenuFocus else tvMenuFocus).requestFocus() }
                true
            } else if (guideModel.askWake) {
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
            // Back from the player: Live TV opens the player, so focus its menu item.
            focusPlayingChannel -> { tvMenuFocus.requestWhenReady(); onPlayingChannelFocused() }
            else -> homeFocus.requestWhenReady()
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
    LaunchedEffect(section) { DashGroups.groupOf(section)?.let { tabMemory.remember(it, section) } }
    fun openSection(target: DashSection) {
        if (lockedFeature(target) != null) return
        val keepList = myItemsModel.holdList && section == DashSection.MyItems && target != DashSection.MyItems
        val keepSettings = settingsFormOpen && target != DashSection.Settings
        if (!keepList && !keepSettings) section = target
    }
    /** OK on a side-menu item opens that section and moves focus to its first entry. */
    fun enterSection(target: DashSection) {
        lockedFeature(target)?.let { accountModel.gate.require(it); return }
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
    val profileModel: com.enigmacast.ui.profile.ProfileViewModel = hiltViewModel()
    if (profileModel.menuOpen) {
        val standby = powerModel.powerState == com.enigmacast.domain.power.ReceiverPowerState.STANDBY
        com.enigmacast.ui.profile.ProfileMenu(
            profileModel,
            powerLabel = stringResource(R.string.profile_receiver_state, powerModel.stateLabel.asString()),
            powerAction = stringResource(if (standby) R.string.profile_receiver_wake else R.string.profile_receiver_standby),
            onPower = { powerModel.request(if (standby) PowerAction.WAKE_UP else PowerAction.STANDBY) },
        )
    }
    if (profileModel.pickerOpen) com.enigmacast.ui.profile.ProfilePicker(profileModel)
    Box(modifier = Modifier.fillMaxSize().background(EnigmaColors.Canvas)) {
        val drawerExpanded = navFocused || !contentFocused
        Row(modifier = Modifier.fillMaxSize()) {
            EnigmaNavigationDrawer(
                expanded = drawerExpanded,
                modifier = Modifier.onFocusChanged { navFocused = it.hasFocus },
            ) {
                EnigmaCastLogo(expanded = drawerExpanded)
                // Who is watching (avatar + name); OK opens switch / manage / receiver standby.
                com.enigmacast.ui.profile.ProfileChip(
                    profileModel, drawerExpanded,
                    Modifier.focusProperties { right = FocusRequester.Cancel },
                )
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
                // TV: Live TV · TV Guide · Movies · TV Series · Radio in a flyout right of the item.
                // The pages' Left/Back requesters all land on this item.
                NavItem(
                    icon = EnigmaIcons.Tv,
                    label = stringResource(R.string.nav_tv),
                    subtitle = DashGroups.current(DashGroup.TV, section, tabMemory.last(DashGroup.TV))?.let { stringResource(DashGroups.label(it)) },
                    selected = DashGroups.groupOf(section) == DashGroup.TV,
                    showLabel = drawerExpanded,
                    staggerIndex = 1,
                    modifier = Modifier
                        .focusRequester(tvMenuFocus)
                        .focusRequester(streamFocus).focusRequester(guideFocus).focusRequester(moviesFocus)
                        .focusRequester(seriesFocus).focusRequester(radioFocus)
                        .onGloballyPositioned { flyoutAnchor[DashGroup.TV] = it.positionInRoot().let { p -> androidx.compose.ui.geometry.Offset(p.x + it.size.width, p.y) } }
                        .onFocusChanged {
                            streamItemFocused = it.isFocused && section == DashSection.Stream
                            guideItemFocused = it.isFocused && section == DashSection.Guide
                            radioItemFocused = it.isFocused && section == DashSection.Radio
                        }
                        .onPreviewKeyEvent { e ->
                            if (e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                            if (e.type == KeyEventType.KeyDown) flyout = DashGroup.TV
                            true
                        }
                        .focusProperties { right = FocusRequester.Cancel },
                    onClick = { flyout = DashGroup.TV },
                )
                if (xtreamAvailable) {
                    NavItem(
                        icon = EnigmaIcons.Tv,
                        label = stringResource(R.string.nav_iptv),
                        selected = false,
                        showLabel = drawerExpanded,
                        locked = !premium,
                        staggerIndex = 2,
                        modifier = Modifier.focusProperties { right = FocusRequester.Cancel },
                        onClick = {
                            if (accountModel.gate.require(com.enigmacast.domain.account.PremiumFeature.XTREAM)) onXtream()
                        },
                    )
                }
                NavItem(
                    icon = EnigmaIcons.Soccer,
                    label = stringResource(R.string.nav_sport),
                    selected = section == DashSection.Soccer,
                    showLabel = drawerExpanded,
                    locked = lockedFeature(DashSection.Soccer) != null,
                    staggerIndex = 3,
                    modifier = Modifier
                        .focusRequester(soccerFocus)
                        .focusProperties { right = if (section == DashSection.Soccer && lockedFeature(DashSection.Soccer) == null) soccerContentFocus else FocusRequester.Cancel },
                    onClick = { enterSection(DashSection.Soccer) },
                )
                // Library: My items · Search in the same flyout.
                NavItem(
                    icon = EnigmaIcons.Record,
                    label = stringResource(R.string.nav_library),
                    subtitle = DashGroups.current(DashGroup.LIBRARY, section, tabMemory.last(DashGroup.LIBRARY))?.let { stringResource(DashGroups.label(it)) },
                    selected = DashGroups.groupOf(section) == DashGroup.LIBRARY,
                    showLabel = drawerExpanded,
                    staggerIndex = 4,
                    modifier = Modifier
                        .focusRequester(libraryMenuFocus)
                        .focusRequester(myItemsMenuFocus).focusRequester(searchMenuFocus)
                        .onGloballyPositioned { flyoutAnchor[DashGroup.LIBRARY] = it.positionInRoot().let { p -> androidx.compose.ui.geometry.Offset(p.x + it.size.width, p.y) } }
                        .onPreviewKeyEvent { e ->
                            if (e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                            if (e.type == KeyEventType.KeyDown) flyout = DashGroup.LIBRARY
                            true
                        }
                        .focusProperties { right = FocusRequester.Cancel },
                    onClick = { flyout = DashGroup.LIBRARY },
                )
                Spacer(Modifier.weight(1f))
                // Settings and Exit sit at the bottom, under the five sections (Down from Library).
                NavItem(
                    icon = Icons.Filled.Settings,
                    label = stringResource(R.string.nav_settings),
                    selected = section == DashSection.Settings,
                    showLabel = drawerExpanded,
                    staggerIndex = 5,
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
                    staggerIndex = 6,
                    onClick = onExit,
                )
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
                    Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    EnigmaScreenTransition(targetState = section, modifier = Modifier.fillMaxSize()) { shown ->
                    when (shown) {
                        DashSection.Home -> {
                        LaunchedEffect(sectionEnter) {
                            if (sectionEnter == 0) return@LaunchedEffect
                            homeContentFocus.requestWhenReady()
                        }
                        com.enigmacast.ui.tuners.TunerHomePanel(
                            contentFocus = homeContentFocus,
                            menuFocus = homeFocus,
                            onRetreatTo = retreat,
                            onWatch = { entry -> guideModel.tune(entry, onStream, onStream) },
                            onXtream = onXtream,
                            library = myItemsModel,
                        )
                        }
                        DashSection.Movies -> {
                        LaunchedEffect(sectionEnter) {
                            if (sectionEnter == 0) return@LaunchedEffect
                            moviesContentFocus.requestWhenReady()
                        }
                        com.enigmacast.ui.home.FilmHomePanel(
                            contentFocus = moviesContentFocus,
                            menuFocus = moviesFocus,
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
                        DashSection.Series -> {
                        LaunchedEffect(sectionEnter) {
                            if (sectionEnter == 0) return@LaunchedEffect
                            seriesContentFocus.requestWhenReady()
                        }
                        com.enigmacast.ui.home.FilmHomePanel(
                            series = true,
                            contentFocus = seriesContentFocus,
                            menuFocus = seriesFocus,
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
                        DashSection.Soccer -> {
                        LaunchedEffect(sectionEnter) {
                            if (sectionEnter == 0) return@LaunchedEffect
                            soccerContentFocus.requestWhenReady()
                        }
                        com.enigmacast.ui.soccer.SoccerPanel(
                            contentFocus = soccerContentFocus,
                            menuFocus = soccerFocus,
                            onRetreatTo = retreat,
                            onWatch = { entry ->
                                val known = guideModel.channels.firstOrNull {
                                    BouquetChannels.sameService(it.reference, entry.reference)
                                } ?: entry
                                guideModel.tune(known, onStream, onStream)
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
                            onXtream = onXtream,
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
                            onSettingsImport = onSettingsImport,
                            onRunSetup = onRunSetup,
                            powerModel = powerModel,
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
            }
        }
        flyout?.let { group ->
            val anchor = flyoutAnchor[group] ?: androidx.compose.ui.geometry.Offset.Zero
            val menu = if (group == DashGroup.TV) tvMenuFocus else libraryMenuFocus
            fun close() { flyout = null; runCatching { menu.requestFocus() } }
            DashFlyout(
                entries = DashGroups.tabs(group).map { target ->
                    DashFlyoutEntry(target, stringResource(DashGroups.label(target)), lockedFeature(target) != null)
                },
                active = DashGroups.current(group, section, tabMemory.last(group)),
                anchorPx = anchor,
                onClose = ::close,
                onChoose = { entry ->
                    flyout = null
                    when (entry.section) {
                        // Live TV still opens the full player.
                        DashSection.Stream -> { runCatching { menu.requestFocus() }; guideModel.openLive(onStream) }
                        else -> {
                            enterSection(entry.section)
                            // A locked entry stays closed (the gate asked); focus goes back to the item.
                            if (entry.locked) runCatching { menu.requestFocus() }
                        }
                    }
                },
            )
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
            fontWeight = EnigmaType.Strong,
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
                            .focusRequesterIf(index == focusIndex, bouquetFocus)
                            .focusRequesterIf(index == 0, firstFocus)
                            .focusRequesterIf(index == lastIndex && lastIndex > 0, lastFocus)
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun SettingsHub(
    pageFocus: FocusRequester,
    menuFocus: FocusRequester,
    focusLanguage: Boolean,
    onLanguageFocused: () -> Unit,
    onChooseLanguage: (AppLanguage) -> Unit,
    onSignalMeter: () -> Unit,
    onOscam: () -> Unit,
    onSettingsImport: () -> Unit,
    onRunSetup: () -> Unit,
    powerModel: com.enigmacast.ui.power.ReceiverPowerViewModel,
    onFormActive: (Boolean) -> Unit,
    onRetreatTo: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    connection: ConnectionViewModel = hiltViewModel(),
) {
    var page by remember { mutableStateOf(if (focusLanguage) SettingPage.Appearance else SettingPage.Receivers) }
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
    val premiumGate = hiltViewModel<com.enigmacast.ui.account.AccountViewModel>().gate
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val focusScope = androidx.compose.runtime.rememberCoroutineScope()
    Column(
        modifier.onFocusChanged { state ->
            val dialogOpen = connection.autodetectState !is AutodetectState.Hidden
            if (formActive && !state.hasFocus && !dialogOpen) {
                // Not from inside the focus change: the old node may be detaching right now.
                focusScope.launch { formFocus.requestWhenReady() }
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
                    .onFocusChanged { if (it.hasFocus) onRetreatTo(menuFocus) }
                    .verticalBoundary(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SettingNav(EnigmaIcons.Lock, stringResource(R.string.settings_account), page == SettingPage.Account, navFocus.getValue(SettingPage.Account), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Account) }) { enterPage(SettingPage.Account) }
                SettingNav(Icons.Filled.Person, stringResource(R.string.settings_profiles), page == SettingPage.Profiles, navFocus.getValue(SettingPage.Profiles), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Profiles) }) { enterPage(SettingPage.Profiles) }
                SettingNav(EnigmaIcons.Tv, stringResource(R.string.settings_receivers), page == SettingPage.Receivers, navFocus.getValue(SettingPage.Receivers), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Receivers) }) { enterPage(SettingPage.Receivers) }
                SettingNav(EnigmaIcons.Power, stringResource(R.string.nav_receiver), page == SettingPage.Receiver, navFocus.getValue(SettingPage.Receiver), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Receiver) }) { enterPage(SettingPage.Receiver) }
                SettingNav(Icons.Filled.PlayArrow, stringResource(R.string.settings_playback), page == SettingPage.Playback, navFocus.getValue(SettingPage.Playback), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Playback) }) { enterPage(SettingPage.Playback) }
                SettingNav(EnigmaIcons.Layers, stringResource(R.string.settings_viewing), page == SettingPage.Viewing, navFocus.getValue(SettingPage.Viewing), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Viewing) }) { enterPage(SettingPage.Viewing) }
                SettingNav(Icons.Filled.Settings, stringResource(R.string.settings_keys), page == SettingPage.Keys, navFocus.getValue(SettingPage.Keys), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Keys) }) { enterPage(SettingPage.Keys) }
                SettingNav(EnigmaIcons.Guide, stringResource(R.string.settings_guide_home), page == SettingPage.Guide, navFocus.getValue(SettingPage.Guide), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Guide) }) { enterPage(SettingPage.Guide) }
                SettingNav(EnigmaIcons.Tv, stringResource(R.string.settings_iptv), page == SettingPage.Iptv, navFocus.getValue(SettingPage.Iptv), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Iptv) }) { enterPage(SettingPage.Iptv) }
                SettingNav(EnigmaIcons.Record, stringResource(R.string.settings_recordings), page == SettingPage.Recordings, navFocus.getValue(SettingPage.Recordings), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Recordings) }) { enterPage(SettingPage.Recordings) }
                SettingNav(EnigmaIcons.Palette, stringResource(R.string.settings_appearance), page == SettingPage.Appearance, navFocus.getValue(SettingPage.Appearance), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Appearance) }) { enterPage(SettingPage.Appearance) }
                SettingNav(EnigmaIcons.Lock, stringResource(R.string.settings_remote), page == SettingPage.Remote, navFocus.getValue(SettingPage.Remote), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Remote) }) { enterPage(SettingPage.Remote) }
                SettingNav(EnigmaIcons.Soccer, stringResource(R.string.settings_soccer), page == SettingPage.Soccer, navFocus.getValue(SettingPage.Soccer), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.Soccer) }) { enterPage(SettingPage.Soccer) }
                SettingNav(EnigmaIcons.Speed, stringResource(R.string.signal_meter), false, null, null, menuFocus, pageContentFocus, opensPage = false, onSelect = onSignalMeter)
                SettingNav(EnigmaIcons.Sync, stringResource(R.string.oscam), false, null, null, menuFocus, pageContentFocus, opensPage = false, onSelect = onOscam)
                SettingNav(EnigmaIcons.Layers, stringResource(R.string.settings_import), false, null, null, menuFocus, pageContentFocus, opensPage = false) {
                    if (premiumGate.require(com.enigmacast.domain.account.PremiumFeature.SETTINGS_IMPORT)) onSettingsImport()
                }
                SettingNav(Icons.Filled.Info, stringResource(R.string.settings_about), page == SettingPage.About, navFocus.getValue(SettingPage.About), pageFocus, menuFocus, pageContentFocus, opensPage = true, onFocus = { showPage(SettingPage.About) }) { enterPage(SettingPage.About) }
            }
            }
            if (page == SettingPage.Receiver) {
                // Receiver (power, details) scrolls itself, so it sits outside the page's scroll column.
                Box(Modifier.weight(1f).fillMaxHeight().verticalBoundary()) {
                    LaunchedEffect(pageEnter) {
                        if (pageEnter == 0) return@LaunchedEffect
                        pageContentFocus.requestWhenReady()
                    }
                    com.enigmacast.ui.power.ReceiverPowerPanel(
                        pageFocus = pageContentFocus,
                        menuFocus = navFocus.getValue(SettingPage.Receiver),
                        onRetreatTo = onRetreatTo,
                        viewModel = powerModel,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else
            CompositionLocalProvider(
                LocalEdgeLeft provides pageFocus,
                androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides com.enigmacast.ui.theme.MinimalBringIntoView,
            ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .onFocusChanged { if (it.hasFocus) onRetreatTo(pageFocus) }
                    .verticalBoundary(),
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
                        onSelect = { id ->
                            // The same switch as the Red key; on Free it opens the Premium dialog.
                            if (id != settings.activeReceiver()?.id) viewModel.selectReceiver(id)
                        },
                        channelRefresh = viewModel.channelRefresh.collectAsState().value,
                        onRefreshChannels = viewModel::refreshChannels,
                        onDelete = viewModel::removeReceiver,
                        menuAnchor = pageFocus,
                        contentFocus = pageContentFocus,
                        onFormActive = { active ->
                            formActive = active
                            onFormActive(active)
                        },
                        formFocus = formFocus,
                    )
                    SettingPage.Receiver -> Unit
                    SettingPage.Profiles -> ProfilesSettingsPanel(pageContentFocus)
                    SettingPage.Account -> com.enigmacast.ui.account.AccountSettingsPanel(pageContentFocus)
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
                        part = PlaybackPart.STREAMING,
                    )
                    SettingPage.Viewing -> PlaybackSettingsPanel(
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
                        part = PlaybackPart.VIEWING,
                    )
                    SettingPage.Keys -> com.enigmacast.ui.keys.RemoteKeysSettingsPanel(pageContentFocus, settings.streamMode)
                    SettingPage.Guide -> GuideHomeSettingsPanel(pageContentFocus)
                    SettingPage.Iptv -> IptvSettingsPanel(pageContentFocus)
                    SettingPage.Recordings -> PlaybackSettingsPanel(
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
                        part = PlaybackPart.RECORDINGS,
                    )
                    SettingPage.Appearance -> {
                        LanguageSettingsPanel(focusLanguage, onLanguageFocused, onChooseLanguage, pageContentFocus)
                        GeneralSettingsPanel(settings.navigationSounds, settings.motionQuality, viewModel, remember { FocusRequester() })
                        OsdSettingsPanel(
                            settings.osd.format,
                            settings.osd.fastPolling,
                            settings.osd.transparencyPercent,
                            settings.osdInfo,
                            viewModel,
                            contentFocus = remember { FocusRequester() },
                            skin = settings.osdSkin,
                        )
                    }
                    SettingPage.Soccer -> SoccerSettingsPanel(pageContentFocus)
                    SettingPage.About -> InfoSettingsPanel(settings, viewModel, pageContentFocus, onRunSetup)
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
        // Focusing a section opens it, so every focused section was also "selected" and got the dot.
        showSelectedDot = false,
        modifier = Modifier
            .focusRequesterIf(true, itemFocus)
            .focusRequesterIf(selected && entryFocus != null, entryFocus)
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
    part: PlaybackPart,
    remote: RemoteDisplayViewModel = hiltViewModel(),
) {
    val playback by remote.uiState.collectAsStateWithLifecycle()
    val passthroughActive by remote.audioPassthroughActive.collectAsStateWithLifecycle()
    // Where to record is asked per recording (box or this device); only the size of captures on this device is set here.
    if (part == PlaybackPart.RECORDINGS) SettingsCard(stringResource(R.string.record_settings)) {
        val presets = TranscodePreset.entries
        ChoiceRow(
            title = stringResource(R.string.record_size),
            description = stringResource(R.string.record_size_desc),
            options = listOf(stringResource(R.string.record_size_original)) +
                presets.map { stringResource(R.string.quality_transcoded, it.height, it.bitrateKbps / 1000f) },
            selectedIndex = presets.indexOf(recordingPreset).let { if (it < 0) 0 else it + 1 },
            onSelect = { viewModel.setRecordingPreset(presets.getOrNull(it - 1)) },
            firstOptionModifier = Modifier.focusRequester(contentFocus),
        )
    }
    if (part == PlaybackPart.VIEWING) SettingsCard(stringResource(R.string.viewing_mode)) {
        ChoiceRow(
            firstOptionModifier = Modifier.focusRequester(contentFocus),
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
    if (part != PlaybackPart.STREAMING) return
    SettingsCard(stringResource(R.string.picture)) {
        ChoiceRow(
            firstOptionModifier = Modifier.focusRequester(contentFocus),
            title = stringResource(R.string.buffer),
            description = if (fast) stringResource(R.string.buffer_fast_desc) else stringResource(R.string.buffer_stable_desc),
            options = listOf(stringResource(R.string.buffer_stable), stringResource(R.string.buffer_fast)),
            selectedIndex = if (fast) 1 else 0,
            onSelect = { viewModel.setLowLatency(it == 1) },
        )
    }
    VideoDecoderCard(viewModel)
    StreamTypeCard()
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
                        .background(Color.Black, skinShape(8.dp)),
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
    skin: com.enigmacast.domain.model.OsdSkinId = com.enigmacast.domain.model.OsdSkinId.STANDARD,
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
            stringResource(R.string.skins_detail),
            color = EnigmaColors.Muted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        com.enigmacast.ui.settings.SkinChooser(skin, viewModel::setSkin)
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
}

@Composable
private fun InfoSettingsPanel(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    contentFocus: FocusRequester,
    onRunSetup: () -> Unit,
) {
    SettingsCard(stringResource(R.string.setup_title)) {
        ValueRow(
            title = stringResource(R.string.setup_run_again),
            value = "",
            description = stringResource(R.string.setup_run_again_desc),
            onClick = onRunSetup,
            modifier = Modifier.focusRequester(contentFocus),
        )
    }
    SettingsCard(stringResource(R.string.app)) {
        InfoRow(stringResource(R.string.version), BuildConfig.VERSION_NAME)
        InfoRow(stringResource(R.string.build), BuildConfig.VERSION_CODE.toString())
    }
    SettingsCard(stringResource(R.string.diagnostics)) {
        ToggleRow(stringResource(R.string.debug_overlay), settings.debug.overlay, viewModel::setDebugOverlay)
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

private enum class PlaybackPart { STREAMING, VIEWING, RECORDINGS }

/** Settings › Profiles: who is watching now, switch, and manage (add, edit, delete). */
@Composable
private fun ProfilesSettingsPanel(contentFocus: FocusRequester, model: com.enigmacast.ui.profile.ProfileViewModel = hiltViewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val active = state.profiles.firstOrNull { it.id == state.activeId }
    SettingsCard(stringResource(R.string.settings_profiles)) {
        ValueRow(
            title = stringResource(R.string.profile_switch),
            value = active?.name.orEmpty(),
            description = stringResource(R.string.profile_switch_desc),
            onClick = { model.openPicker(false) },
            modifier = Modifier.focusRequester(contentFocus),
        )
        ValueRow(
            title = stringResource(R.string.profile_manage),
            value = state.profiles.size.toString(),
            description = stringResource(R.string.profile_manage_desc),
            onClick = { model.openPicker(true) },
        )
    }
}

/** Settings › Guide & Home: Home country, Home bouquets, TMDB key, and data sync. */
@Composable
private fun GuideHomeSettingsPanel(contentFocus: FocusRequester) {
    Column(Modifier.focusRequester(contentFocus).focusGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.enigmacast.ui.home.HomeFilmSettingsCard()
        com.enigmacast.ui.home.DataSyncSettingsCard()
    }
}

/** Settings › IPTV: source (XStreamity / playlist), M3U or Xtream login, Save & load, status. */
@Composable
private fun IptvSettingsPanel(contentFocus: FocusRequester) {
    Column(Modifier.focusRequester(contentFocus).focusGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.enigmacast.ui.xtream.IptvSettingsCard()
    }
}

@Composable
private fun SoccerSettingsPanel(contentFocus: FocusRequester) {
    Column(Modifier.focusRequester(contentFocus).focusGroup()) {
        com.enigmacast.ui.soccer.SoccerSettingsCard()
    }
}

/**
 * Up on the first option (or Down on the last) of a settings column stays there. Without it
 * the focus search left the column for the main menu, where nothing showed it. Left and Right
 * still leave the column as before.
 */
private fun Modifier.verticalBoundary(): Modifier = this
    .focusProperties {
        onExit = {
            if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Up ||
                requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Down
            ) cancelFocusChange()
        }
    }
    .focusGroup()

@Composable
private fun VideoDecoderCard(viewModel: SettingsViewModel) {
    val all by viewModel.settings.collectAsStateWithLifecycle()
    val decoder = all.playback.videoDecoder
    val compat = all.playback.videoCompatibility
    SettingsCard(stringResource(R.string.video_decoder_card)) {
        val decoders = com.enigmacast.domain.model.VideoDecoderMode.entries
        ChoiceRow(
            title = stringResource(R.string.video_decoder),
            description = stringResource(R.string.video_decoder_desc),
            options = listOf(
                stringResource(R.string.video_decoder_auto),
                stringResource(R.string.video_decoder_hardware),
                stringResource(R.string.video_decoder_software),
            ),
            selectedIndex = decoders.indexOf(decoder),
            onSelect = { viewModel.setVideoDecoder(decoders[it]) },
        )
        val modes = com.enigmacast.domain.model.VideoCompatibility.entries
        ChoiceRow(
            title = stringResource(R.string.video_compat),
            description = stringResource(
                if (com.enigmacast.playback.VideoQuirks.thisDevice) R.string.video_compat_desc_detected else R.string.video_compat_desc,
            ),
            options = listOf(
                stringResource(R.string.video_compat_auto),
                stringResource(R.string.video_compat_on),
                stringResource(R.string.video_compat_off),
            ),
            selectedIndex = modes.indexOf(compat),
            onSelect = { viewModel.setVideoCompatibility(modes[it]) },
        )
    }
}
