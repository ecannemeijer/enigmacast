package com.enigmacast.ui.hotel

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.hotel.HotelConfig
import com.enigmacast.domain.hotel.HotelMenu
import com.enigmacast.domain.hotel.HotelMenuItem
import com.enigmacast.domain.hotel.HotelPin
import com.enigmacast.domain.hotel.HotelSchedule
import com.enigmacast.domain.hotel.HotelSource
import com.enigmacast.domain.hotel.HotelWelcome
import com.enigmacast.domain.hotel.WifiQr
import com.enigmacast.domain.profile.photo.QrCode
import com.enigmacast.domain.xtream.XtreamSection
import com.enigmacast.ui.profile.QrImage
import com.enigmacast.ui.security.PinPad
import com.enigmacast.ui.settingsimport.CompactToggle
import com.enigmacast.ui.theme.ChoiceRow
import com.enigmacast.ui.theme.CompactButton
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaIcons
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.NavIconStyle
import com.enigmacast.ui.theme.NavItem
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.OptionListDialog
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.StepperRow
import com.enigmacast.ui.theme.ToggleRow
import com.enigmacast.ui.theme.TvChoiceDialog
import com.enigmacast.ui.theme.TvTextField
import com.enigmacast.ui.theme.ValueRow
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.skinShape
import com.enigmacast.ui.theme.skinSurface
import com.enigmacast.ui.xtream.IptvLibraryPage
import com.enigmacast.ui.xtream.XtreamViewModel
import kotlinx.coroutines.delay

/** Up/Down never leave this group (D-pad boundary). */
private fun Modifier.verticalBoundary(): Modifier = this
    .focusProperties {
        onExit = {
            if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) cancelFocusChange()
        }
    }
    .focusGroup()

/** No direction leaves this group (dialogs). */
private fun Modifier.closedBoundary(): Modifier = this
    .focusProperties { onExit = { cancelFocusChange() } }
    .focusGroup()

// ---------------------------------------------------------------------------------------------
// The guest's menu
// ---------------------------------------------------------------------------------------------

/**
 * Hotel mode's only menu: live TV (satellite or IPTV), films and series when the owner allows
 * them, and Settings behind the hotel PIN. No Home, no profiles.
 */
@Composable
fun HotelScreen(onLive: () -> Unit, onPlayIptv: () -> Unit) {
    val model: HotelViewModel = hiltViewModel()
    val config by model.config.collectAsState()
    val unlocked by model.unlocked.collectAsState()
    val items = remember(config.source, config.iptvVod) { HotelMenu.items(config) }
    var shown by rememberSaveable { mutableStateOf(items.first()) }
    if (shown !in items) shown = items.first()
    val itemFocus = remember(items) { items.associateWith { FocusRequester() } }
    val contentFocus = remember { FocusRequester() }
    var sectionEnter by remember { mutableIntStateOf(0) }
    var wantsSettings by remember { mutableStateOf(false) }
    val panelFocus = remember { FocusRequester() }

    LaunchedEffect(items) { itemFocus[shown]?.requestWhenReady() }
    // Leaving the menu (live TV) locks the settings again: the next guest finds them closed.
    DisposableEffect(Unit) { onDispose { model.relock() } }
    LaunchedEffect(unlocked, wantsSettings) {
        if (unlocked && wantsSettings) {
            wantsSettings = false
            shown = HotelMenuItem.SETTINGS
            contentFocus.requestWhenReady()
        }
    }
    BackHandler(enabled = shown == HotelMenuItem.SETTINGS && unlocked) {
        model.relock()
        shown = items.first()
        itemFocus[shown]?.requestFocus()
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(EnigmaColors.Canvas)
            .padding(horizontal = 48.dp, vertical = 27.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            Modifier
                .width(240.dp)
                .fillMaxHeight()
                .focusProperties { right = if (shown == HotelMenuItem.SETTINGS && !unlocked) FocusRequester.Cancel else FocusRequester.Default }
                .verticalBoundary(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                config.welcome.name.ifBlank { stringResource(R.string.hotel_title) },
                color = EnigmaColors.Text,
                fontSize = EnigmaType.Title,
                fontWeight = EnigmaType.Strong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, bottom = 16.dp),
            )
            items.filter { it != HotelMenuItem.SETTINGS }.forEach { item ->
                NavItem(
                    icon = hotelIcon(item),
                    label = hotelLabel(item),
                    selected = shown == item,
                    onClick = {
                        when (item) {
                            HotelMenuItem.SAT_TV, HotelMenuItem.IPTV_LIVE -> onLive()
                            else -> sectionEnter++
                        }
                    },
                    onFocus = {
                        if (shown == HotelMenuItem.SETTINGS) model.relock()
                        shown = item
                    },
                    modifier = Modifier.focusRequester(itemFocus.getValue(item)),
                )
            }
            Spacer(Modifier.weight(1f))
            NavItem(
                icon = Icons.Filled.Settings,
                label = stringResource(R.string.hotel_menu_settings),
                selected = shown == HotelMenuItem.SETTINGS,
                onClick = {
                    if (unlocked) {
                        shown = HotelMenuItem.SETTINGS
                        runCatching { contentFocus.requestFocus() }
                    } else {
                        wantsSettings = true
                        model.startPin(HotelPinStep.UNLOCK)
                    }
                },
                onFocus = { shown = HotelMenuItem.SETTINGS },
                modifier = Modifier.focusRequester(itemFocus.getValue(HotelMenuItem.SETTINGS)),
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (shown) {
                HotelMenuItem.SAT_TV, HotelMenuItem.IPTV_LIVE -> HotelLivePane(onLive)
                HotelMenuItem.IPTV_MOVIES, HotelMenuItem.IPTV_SERIES -> {
                    val xtream: XtreamViewModel = hiltViewModel()
                    IptvLibraryPage(
                        model = xtream,
                        menuFocus = itemFocus.getValue(shown),
                        sectionEnter = sectionEnter,
                        section = if (shown == HotelMenuItem.IPTV_SERIES) XtreamSection.SERIES else XtreamSection.MOVIES,
                        onRetreatTo = {},
                        onPlay = onPlayIptv,
                    )
                }
                HotelMenuItem.SETTINGS -> if (unlocked) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).verticalBoundary(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CompactButton(
                            label = stringResource(R.string.hotel_lock),
                            onClick = { model.relock(); onLive() },
                            icon = EnigmaIcons.Lock,
                            modifier = Modifier.focusRequester(contentFocus),
                        )
                        HotelSettingsPanel(panelFocus, model = model, inHotel = true)
                    }
                } else {
                    HotelHint(stringResource(R.string.hotel_settings_locked))
                }
            }
        }
    }
    if (model.pinStep != null) HotelPinDialog(model) { runCatching { itemFocus[shown]?.requestFocus() } }
}

@Composable
private fun hotelLabel(item: HotelMenuItem): String = stringResource(
    when (item) {
        HotelMenuItem.SAT_TV -> R.string.hotel_menu_live_sat
        HotelMenuItem.IPTV_LIVE -> R.string.hotel_menu_live_iptv
        HotelMenuItem.IPTV_MOVIES -> R.string.hotel_menu_movies
        HotelMenuItem.IPTV_SERIES -> R.string.hotel_menu_series
        HotelMenuItem.SETTINGS -> R.string.hotel_menu_settings
    },
)

private fun hotelIcon(item: HotelMenuItem) = when (item) {
    HotelMenuItem.SAT_TV -> NavIconStyle.Enigma
    HotelMenuItem.IPTV_LIVE -> NavIconStyle.Iptv
    HotelMenuItem.IPTV_MOVIES -> EnigmaIcons.Film
    HotelMenuItem.IPTV_SERIES -> EnigmaIcons.Series
    HotelMenuItem.SETTINGS -> Icons.Filled.Settings
}

@Composable
private fun HotelLivePane(onLive: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.hotel_live_hint), color = EnigmaColors.Muted, fontSize = EnigmaType.Body)
        Spacer(Modifier.size(16.dp))
        CompactButton(label = stringResource(R.string.hotel_watch), onClick = onLive, icon = EnigmaIcons.Tv)
    }
}

@Composable
private fun HotelHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = EnigmaColors.Muted, fontSize = EnigmaType.Body)
    }
}

// ---------------------------------------------------------------------------------------------
// Hotel PIN
// ---------------------------------------------------------------------------------------------

@Composable
private fun hotelPinTitle(step: HotelPinStep): String = stringResource(
    when (step) {
        HotelPinStep.UNLOCK -> R.string.hotel_pin_enter
        HotelPinStep.NEW -> R.string.hotel_pin_new
        HotelPinStep.REPEAT -> R.string.hotel_pin_repeat
        HotelPinStep.CURRENT_TO_CHANGE -> R.string.hotel_pin_current
    },
)

@Composable
fun HotelPinDialog(model: HotelViewModel, onClosed: () -> Unit = {}) {
    val step = model.pinStep ?: return
    val mismatch = stringResource(R.string.pin_mismatch)
    val wrongFmt = stringResource(R.string.pin_wrong)
    val coolFmt = stringResource(R.string.pin_cooldown)
    val invalid = stringResource(R.string.hotel_pin_invalid)
    Dialog(onDismissRequest = { model.cancelPin(); onClosed() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        BackHandler { model.cancelPin(); onClosed() }
        PinPad(
            title = hotelPinTitle(step),
            error = model.pinError,
            busy = model.pinBusy,
            onSubmit = { pin -> model.submitPin(pin, { wrongFmt.format(it) }, { coolFmt.format(it) }, mismatch, invalid) },
            onCancel = { model.cancelPin(); onClosed() },
            lengths = HotelPin.LENGTHS,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Settings › Hotel mode (normal Settings with hotel mode off, the hotel menu behind the PIN when on)
// ---------------------------------------------------------------------------------------------

@Composable
fun HotelSettingsPanel(contentFocus: FocusRequester, model: HotelViewModel = hiltViewModel(), inHotel: Boolean = false) {
    val config by model.config.collectAsState()
    val pinSet by model.pinSet.collectAsState()
    val profiles by model.profiles.collectAsState()
    val xtream: XtreamViewModel = hiltViewModel()
    val context = LocalContext.current
    var profileDialog by remember { mutableStateOf(false) }
    var startDialog by remember { mutableStateOf(false) }
    var secondsDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.loadWifiPassword() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // Back from Android's "default home app" screen: show what was chosen.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshLauncher() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(Modifier.focusRequester(contentFocus).focusGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard(stringResource(R.string.hotel_title)) {
            ToggleRow(
                title = stringResource(R.string.hotel_enabled),
                description = stringResource(if (pinSet) R.string.hotel_enabled_desc else R.string.hotel_pin_needed),
                checked = config.enabled,
                onCheckedChange = model::setEnabled,
            )
            ValueRow(
                title = stringResource(if (pinSet) R.string.hotel_pin_change else R.string.hotel_pin_set),
                value = if (pinSet) "••••" else "—",
                onClick = { model.startPin(if (pinSet) HotelPinStep.CURRENT_TO_CHANGE else HotelPinStep.NEW) },
            )
            ChoiceRow(
                title = stringResource(R.string.hotel_source),
                options = listOf(stringResource(R.string.hotel_source_sat), stringResource(R.string.hotel_source_iptv)),
                selectedIndex = if (config.iptv) 1 else 0,
                onSelect = { model.setSource(if (it == 1) HotelSource.IPTV else HotelSource.SATELLITE) },
                description = if (config.iptv) stringResource(R.string.hotel_iptv_one_connection) else null,
            )
            if (config.iptv) ToggleRow(
                title = stringResource(R.string.hotel_vod),
                description = stringResource(R.string.hotel_vod_desc),
                checked = config.iptvVod,
                onCheckedChange = model::setVod,
            )
            val profileName = profiles.profiles.firstOrNull { it.uuid == config.profileUuid }?.name
                ?: profiles.profiles.firstOrNull { it.uuid == profiles.activeUuid }?.name.orEmpty()
            ValueRow(
                title = stringResource(R.string.hotel_profile),
                value = profileName,
                description = stringResource(R.string.hotel_profile_desc),
                onClick = { profileDialog = true },
            )
        }

        SettingsCard(stringResource(R.string.hotel_visible)) {
            if (!config.iptv) ValueRow(
                title = stringResource(R.string.hotel_bouquets),
                // An empty selection shows everything to guests (never a TV without channels).
                value = config.allowedBouquets?.takeIf { it.isNotEmpty() }?.size?.toString() ?: stringResource(R.string.hotel_all),
                description = stringResource(R.string.hotel_bouquets_desc),
                onClick = model::openBouquets,
            ) else ValueRow(
                title = stringResource(R.string.hotel_categories),
                value = if (config.allowedCategories.values.all { it.isEmpty() }) stringResource(R.string.hotel_all) else config.allowedCategories.values.count { it.isNotEmpty() }.toString(),
                description = stringResource(R.string.hotel_categories_desc),
                onClick = model::openCategories,
            )
        }

        SettingsCard(stringResource(R.string.hotel_start)) {
            val startName = if (config.iptv) config.startIptv?.name else config.startSat?.name
            ValueRow(
                title = stringResource(R.string.hotel_start_channel),
                value = startName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.hotel_start_none),
                description = stringResource(R.string.hotel_start_channel_desc),
                onClick = { startDialog = true },
            )
            StepperRow(
                title = stringResource(R.string.hotel_start_volume),
                value = if (config.startVolume < 0) stringResource(R.string.hotel_volume_unchanged) else "${config.startVolume} %",
                onDecrease = { model.stepVolume(-1) },
                onIncrease = { model.stepVolume(1) },
            )
        }

        SettingsCard(stringResource(R.string.hotel_welcome)) {
            ToggleRow(
                title = stringResource(R.string.hotel_welcome_show),
                checked = config.welcome.enabled,
                onCheckedChange = { on -> model.setWelcome { it.copy(enabled = on) } },
            )
            if (config.welcome.enabled) {
                ValueRow(
                    title = stringResource(R.string.hotel_welcome_seconds),
                    value = stringResource(R.string.hotel_seconds, config.welcome.seconds),
                    onClick = { secondsDialog = true },
                )
                TvTextField(
                    label = stringResource(R.string.hotel_welcome_name),
                    value = config.welcome.name,
                    onValueChange = { v -> model.setWelcome { it.copy(name = v.take(60)) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                TvTextField(
                    label = stringResource(R.string.hotel_welcome_ssid),
                    value = config.welcome.ssid,
                    onValueChange = { v -> model.setWelcome { it.copy(ssid = v.take(64)) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                TvTextField(
                    label = stringResource(R.string.hotel_welcome_password),
                    value = model.wifiPassword,
                    onValueChange = { model.editWifiPassword(it.take(64)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ToggleRow(
                    title = stringResource(R.string.hotel_welcome_qr),
                    checked = config.welcome.showQr,
                    onCheckedChange = { on -> model.setWelcome { it.copy(showQr = on) } },
                )
                TvTextField(
                    label = stringResource(R.string.hotel_welcome_text),
                    value = config.welcome.text,
                    onValueChange = { v -> model.setWelcome { it.copy(text = v.take(400)) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SettingsCard(stringResource(R.string.hotel_cleanup)) {
            Text(stringResource(R.string.hotel_cleanup_desc), color = EnigmaColors.Muted, fontSize = EnigmaType.Label)
            CompactButton(
                label = stringResource(if (model.cleanupMessage) R.string.hotel_cleanup_done else R.string.hotel_cleanup_now),
                onClick = model::cleanupNow,
                icon = EnigmaIcons.History,
            )
            ToggleRow(
                title = stringResource(R.string.hotel_cleanup_daily),
                checked = config.cleanupDaily,
                onCheckedChange = model::setCleanupDaily,
            )
            if (config.cleanupDaily) StepperRow(
                title = stringResource(R.string.hotel_cleanup_time),
                value = HotelSchedule.label(config.cleanupMinute),
                onDecrease = { model.stepCleanupTime(-1) },
                onIncrease = { model.stepCleanupTime(1) },
            )
        }
        LaunchedEffect(model.cleanupMessage) {
            if (model.cleanupMessage) { delay(3_000); model.cleanupMessageShown() }
        }

        SettingsCard(stringResource(R.string.hotel_launcher)) {
            ToggleRow(
                title = stringResource(R.string.hotel_launcher),
                description = stringResource(R.string.hotel_launcher_desc),
                checked = config.launcher,
                onCheckedChange = model::setLauncher,
            )
            if (config.launcher) ValueRow(
                title = stringResource(R.string.hotel_launcher_open),
                value = stringResource(if (model.isDefaultLauncher) R.string.hotel_launcher_is_default else R.string.hotel_launcher_not_default),
                onClick = {
                    val home = Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val fallback = Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(home) }.recoverCatching { context.startActivity(fallback) }
                },
            )
        }

        SettingsCard(stringResource(R.string.hotel_exit_title)) {
            Text(stringResource(R.string.hotel_exit_text), color = EnigmaColors.Muted, fontSize = EnigmaType.Label)
        }
    }

    if (profileDialog) {
        val list = profiles.profiles
        OptionListDialog(
            title = stringResource(R.string.hotel_profile),
            options = list.map { it.name },
            selectedIndex = list.indexOfFirst { it.uuid == config.profileUuid },
            onSelect = { i -> list.getOrNull(i)?.let { model.setProfile(it.uuid) }; profileDialog = false },
            onDismiss = { profileDialog = false },
        )
    }
    if (secondsDialog) {
        OptionListDialog(
            title = stringResource(R.string.hotel_welcome_seconds),
            options = HotelWelcome.SECONDS.map { stringResource(R.string.hotel_seconds, it) },
            selectedIndex = HotelWelcome.SECONDS.indexOf(config.welcome.seconds),
            onSelect = { i -> model.setWelcome { it.copy(seconds = HotelWelcome.SECONDS[i]) }; secondsDialog = false },
            onDismiss = { secondsDialog = false },
        )
    }
    if (startDialog) {
        TvChoiceDialog(
            title = stringResource(R.string.hotel_start_channel),
            message = stringResource(R.string.hotel_start_channel_desc),
            choices = listOf(stringResource(R.string.hotel_start_use_current), stringResource(R.string.hotel_start_none)),
            onChoose = { i ->
                if (i == 0) model.useCurrentChannel(xtream.account?.key) else model.clearStartChannel()
                startDialog = false
            },
            onDismiss = { startDialog = false },
            stacked = true,
        )
    }
    if (!inHotel && model.pinStep != null) HotelPinDialog(model) { runCatching { contentFocus.requestFocus() } }
    model.bouquetPage?.let { HotelBouquetDialog(model, it, config) }
    model.categoryPage?.let { HotelCategoryDialog(model, it, config) }
}

@Composable
private fun HotelListDialog(
    title: String,
    onDismiss: () -> Unit,
    header: @Composable () -> Unit = {},
    body: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        Column(
            Modifier
                .width(560.dp)
                .heightIn(max = 600.dp)
                .skinSurface(EnigmaColors.Card, skinShape(16.dp))
                .padding(20.dp)
                .closedBoundary(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = EnigmaColors.Text, fontSize = EnigmaType.Title, fontWeight = EnigmaType.Strong)
            header()
            body()
            CompactButton(label = stringResource(R.string.hotel_close), onClick = onDismiss)
        }
    }
}

@Composable
private fun HotelBouquetDialog(model: HotelViewModel, page: HotelViewModel.BouquetPage, config: HotelConfig) {
    val first = remember { FocusRequester() }
    HotelListDialog(
        title = stringResource(R.string.hotel_bouquets),
        onDismiss = model::closeBouquets,
        header = {
            Text(stringResource(R.string.hotel_bouquets_desc), color = EnigmaColors.Muted, fontSize = EnigmaType.Label)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton(label = stringResource(R.string.hotel_all), onClick = model::allBouquets, modifier = Modifier.focusRequester(first))
                CompactButton(label = stringResource(R.string.hotel_none), onClick = model::noBouquets)
            }
        },
    ) {
        LaunchedEffect(Unit) { first.requestWhenReady() }
        when {
            page.loading -> Text(stringResource(R.string.hotel_loading), color = EnigmaColors.Muted)
            page.failed -> Text(stringResource(R.string.hotel_load_failed), color = EnigmaColors.Danger)
            else -> LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(page.bouquets, key = { it.reference }) { bouquet ->
                    CompactToggle(
                        title = bouquet.name,
                        checked = config.allowedBouquets?.contains(bouquet.reference) ?: true,
                        onToggle = { model.toggleBouquet(bouquet.reference) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun HotelCategoryDialog(model: HotelViewModel, page: HotelViewModel.CategoryPage, config: HotelConfig) {
    val first = remember { FocusRequester() }
    val sections = buildList {
        add(XtreamSection.LIVE)
        if (config.iptvVod) { add(XtreamSection.MOVIES); add(XtreamSection.SERIES) }
    }
    HotelListDialog(
        title = stringResource(R.string.hotel_categories),
        onDismiss = model::closeCategories,
        header = {
            Text(stringResource(R.string.hotel_categories_desc), color = EnigmaColors.Muted, fontSize = EnigmaType.Label)
            if (page.providers.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                page.providers.forEach { provider ->
                    CompactButton(
                        label = provider.displayName(),
                        onClick = { model.showCategories(page.section, provider.id) },
                        selected = provider.id == page.providerId,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sections.forEachIndexed { i, section ->
                    CompactButton(
                        label = stringResource(
                            when (section) {
                                XtreamSection.LIVE -> R.string.hotel_menu_live_iptv
                                XtreamSection.MOVIES -> R.string.hotel_menu_movies
                                else -> R.string.hotel_menu_series
                            },
                        ),
                        onClick = { model.showCategories(section, page.providerId.ifBlank { null }) },
                        selected = section == page.section,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton(label = stringResource(R.string.hotel_all), onClick = model::allCategories)
                CompactButton(label = stringResource(R.string.hotel_none), onClick = model::noCategories)
            }
        },
    ) {
        LaunchedEffect(Unit) { first.requestWhenReady() }
        val shown = model.shownCategories(page, config)
        when {
            page.loading -> Text(stringResource(R.string.hotel_loading), color = EnigmaColors.Muted)
            page.failed -> Text(stringResource(R.string.hotel_load_failed), color = EnigmaColors.Danger)
            else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(page.categories, key = { it.id }) { category ->
                    CompactToggle(
                        title = category.name,
                        checked = category.id in shown,
                        onToggle = { model.toggleCategory(category.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Welcome screen
// ---------------------------------------------------------------------------------------------

/** The B&B's welcome: name, Wi-Fi (with QR) and a text; any key or the timer starts live TV. */
@Composable
fun HotelWelcomeScreen(onDone: () -> Unit) {
    val model: HotelViewModel = hiltViewModel()
    val config by model.config.collectAsState()
    LaunchedEffect(Unit) { model.loadWifiPassword() }
    LaunchedEffect(Unit) {
        delay(config.welcome.seconds.coerceIn(1, 300) * 1_000L)
        onDone()
    }
    HotelWelcomeContent(config.welcome, model.wifiPassword)
}

@Composable
fun HotelWelcomeContent(welcome: HotelWelcome, password: String) {
    Box(Modifier.fillMaxSize().background(EnigmaColors.Canvas), contentAlignment = Alignment.Center) {
        Row(
            Modifier.padding(horizontal = 96.dp, vertical = 54.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.hotel_welcome_default), color = EnigmaColors.Accent, fontSize = EnigmaType.Title)
                if (welcome.name.isNotBlank()) Text(welcome.name, color = EnigmaColors.Text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                if (welcome.text.isNotBlank()) Text(welcome.text, color = EnigmaColors.Text, fontSize = EnigmaType.Body, lineHeight = 24.sp)
                if (welcome.ssid.isNotBlank()) {
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.hotel_welcome_wifi), color = EnigmaColors.Muted, fontSize = EnigmaType.Label)
                    Text(welcome.ssid, color = EnigmaColors.Text, fontSize = EnigmaType.Title, fontWeight = EnigmaType.Strong)
                    if (password.isNotBlank()) {
                        Text(stringResource(R.string.hotel_welcome_password_label), color = EnigmaColors.Muted, fontSize = EnigmaType.Label)
                        Text(password, color = EnigmaColors.Text, fontSize = EnigmaType.Title, fontWeight = EnigmaType.Strong)
                    }
                }
                Spacer(Modifier.size(16.dp))
                Text(stringResource(R.string.hotel_welcome_skip), color = EnigmaColors.Muted, fontSize = EnigmaType.Label)
            }
            if (welcome.showQr && welcome.ssid.isNotBlank()) {
                val qr = remember(welcome.ssid, password) { runCatching { QrCode.encode(WifiQr.payload(welcome.ssid, password)) }.getOrNull() }
                if (qr != null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(260.dp).background(androidx.compose.ui.graphics.Color.White, skinShape(12.dp)).padding(14.dp)) {
                        QrImage(qr, Modifier.fillMaxSize())
                    }
                    Text(stringResource(R.string.hotel_welcome_scan), color = EnigmaColors.Muted, fontSize = EnigmaType.Label)
                }
            }
        }
    }
}
