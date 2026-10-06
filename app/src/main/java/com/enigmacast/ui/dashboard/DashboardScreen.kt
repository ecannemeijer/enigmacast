package com.enigmacast.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.enigmacast.BuildConfig
import com.enigmacast.R
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.ui.connection.ConnectionViewModel
import com.enigmacast.ui.remote.EnigmaVideoTexture
import com.enigmacast.ui.remote.RemoteDisplayViewModel
import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.power.PowerEvent
import com.enigmacast.ui.power.ReceiverPowerPanel
import com.enigmacast.ui.power.ReceiverPowerViewModel
import com.enigmacast.ui.power.powerStateColor
import com.enigmacast.ui.settings.SettingsViewModel
import com.enigmacast.ui.theme.FeedbackSnackbar
import com.enigmacast.ui.theme.StatusPill
import com.enigmacast.ui.theme.ActionTile
import com.enigmacast.ui.theme.ChoiceRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaIcons
import com.enigmacast.ui.theme.InfoRow
import com.enigmacast.ui.theme.NavItem
import com.enigmacast.ui.theme.PanelHeader
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.TileTone
import com.enigmacast.ui.theme.ToggleRow
import com.enigmacast.ui.theme.TvConfirmDialog

private val FocusedButton = Color(0xFF8A5610)
private val FocusedButtonText = Color.White

private enum class DashSection {
    Stream,
    Receiver,
    RcInfo,
    Settings,
}

private enum class SettingPage {
    Receiver,
    Playback,
    Osd,
    Info,
}

@Composable
fun DashboardScreen(
    showMiniPlayer: Boolean,
    onStream: () -> Unit,
    onMiniPlayer: () -> Unit,
    onExit: () -> Unit,
    onBindBack: ((() -> Boolean)?) -> Unit,
    guideModel: StreamGuideViewModel = hiltViewModel(),
    powerModel: ReceiverPowerViewModel = hiltViewModel(),
) {
    var section by remember { mutableStateOf(DashSection.Stream) }
    var contentFocused by remember { mutableStateOf(false) }
    val streamFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    val epgFocus = remember { FocusRequester() }
    val receiverMenuFocus = remember { FocusRequester() }
    val receiverPageFocus = remember { FocusRequester() }
    val rcMenuFocus = remember { FocusRequester() }
    val settingsMenuFocus = remember { FocusRequester() }
    val settingsPageFocus = remember { FocusRequester() }
    val bouquetFocus = remember { FocusRequester() }
    DisposableEffect(contentFocused, section, guideModel.askWake) {
        onBindBack {
            if (guideModel.askWake) {
                guideModel.declineWake()
                true
            } else if (contentFocused) {
                runCatching {
                    when (section) {
                        DashSection.Stream -> streamFocus.requestFocus()
                        DashSection.Receiver -> receiverMenuFocus.requestFocus()
                        DashSection.RcInfo -> rcMenuFocus.requestFocus()
                        DashSection.Settings -> settingsMenuFocus.requestFocus()
                    }
                }
                true
            } else {
                false
            }
        }
        onDispose { onBindBack(null) }
    }
    LaunchedEffect(Unit) {
        streamFocus.requestFocus()
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
                PowerEvent.WentToStandby -> guideModel.onReceiverOff("Ontvanger staat in stand-by")
                is PowerEvent.GoingOffline -> guideModel.onReceiverOff(
                    if (event.action == PowerAction.DEEP_STANDBY) "Ontvanger is uitgeschakeld" else "Ontvanger herstart…",
                )
                PowerEvent.WokeUp -> guideModel.onReceiverOn()
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(EnigmaColors.Canvas)) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(200.dp)
                    .fillMaxHeight()
                    .background(EnigmaColors.Canvas)
                    .padding(horizontal = 12.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.enigmacast_logo),
                    contentDescription = "EnigmaCast",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .padding(bottom = 14.dp),
                    contentScale = ContentScale.FillWidth,
                )
                StatusPill(
                    text = powerModel.stateLabel,
                    color = powerStateColor(powerModel.powerState, powerModel.restarting),
                    modifier = Modifier.padding(start = 8.dp, bottom = 10.dp),
                )
                NavItem(
                    icon = Icons.Filled.PlayArrow,
                    label = "Stream",
                    selected = section == DashSection.Stream,
                    modifier = Modifier
                        .focusRequester(streamFocus)
                        .focusProperties { right = channelFocus },
                    onFocus = { section = DashSection.Stream },
                    onClick = onStream,
                )
                NavItem(
                    icon = EnigmaIcons.Power,
                    label = "Receiver",
                    selected = section == DashSection.Receiver,
                    modifier = Modifier
                        .focusRequester(receiverMenuFocus)
                        .focusProperties { right = receiverPageFocus },
                    onFocus = { section = DashSection.Receiver },
                    onClick = { section = DashSection.Receiver },
                )
                NavItem(
                    icon = Icons.Filled.Info,
                    label = "RC Info",
                    selected = section == DashSection.RcInfo,
                    modifier = Modifier.focusRequester(rcMenuFocus),
                    onFocus = { section = DashSection.RcInfo },
                    onClick = { section = DashSection.RcInfo },
                )
                NavItem(
                    icon = Icons.Filled.Settings,
                    label = "Settings",
                    selected = section == DashSection.Settings,
                    modifier = Modifier
                        .focusRequester(settingsMenuFocus)
                        .focusProperties { right = settingsPageFocus },
                    onFocus = { section = DashSection.Settings },
                    onClick = { section = DashSection.Settings },
                )
                Spacer(Modifier.height(8.dp))
                NavItem(
                    icon = Icons.Filled.Close,
                    label = "Exit",
                    selected = false,
                    onClick = onExit,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}",
                    color = EnigmaColors.Subtle,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                )
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
                    when (section) {
                        DashSection.Stream -> StreamPanel(
                            showMiniPlayer,
                            channelFocus,
                            epgFocus,
                            bouquetFocus,
                            streamFocus,
                            settingsMenuFocus,
                            onMiniPlayer,
                            onStream,
                        )
                        DashSection.Receiver -> ReceiverPowerPanel(
                            pageFocus = receiverPageFocus,
                            menuFocus = receiverMenuFocus,
                            viewModel = powerModel,
                            modifier = Modifier.fillMaxSize(),
                        )
                        DashSection.RcInfo -> RcInfoPanel(Modifier.fillMaxSize())
                        DashSection.Settings -> SettingsHub(settingsPageFocus, settingsMenuFocus, Modifier.fillMaxSize())
                    }
                }
            }
        }
        if (guideModel.askWake) {
            TvConfirmDialog(
                title = "De ontvanger staat uit",
                message = if (guideModel.waking) "Bezig met aanzetten…" else "Zal ik de Enigma2 aanzetten?",
                confirmLabel = "Ja, aanzetten",
                dismissLabel = "Nee",
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
                message = feedback.message,
                isError = feedback.isError,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun StreamPanel(
    showMiniPlayer: Boolean,
    channelFocus: FocusRequester,
    epgFocus: FocusRequester,
    bouquetFocus: FocusRequester,
    menuFocus: FocusRequester,
    settingsFocus: FocusRequester,
    onMiniPlayer: () -> Unit,
    onFullscreen: () -> Unit,
    guideModel: StreamGuideViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) {
        guideModel.refresh()
        var elapsed = 0
        while (true) {
            delay(2_000)
            elapsed += 2_000
            if (elapsed >= 30_000) {
                elapsed = 0
                guideModel.refresh()
            } else {
                guideModel.refreshIfServiceChanged()
            }
        }
    }
    val guide = guideModel.guide
    var focusedEvent by remember { mutableStateOf<EpgEvent?>(null) }
    LaunchedEffect(guide?.serviceReference) {
        focusedEvent = null
    }
    val events = guide?.events.orEmpty()
    val channels = guideModel.channels
    val bouquets = guideModel.bouquets
    val tuned = guideModel.tunedReference.ifBlank { guide?.serviceReference.orEmpty() }
    var bouquetsOpen by remember { mutableStateOf(false) }
    var bouquetOpenToken by remember { mutableIntStateOf(0) }
    var channelFocusToken by remember { mutableIntStateOf(0) }
    val shown = focusedEvent?.takeIf { selected -> events.any { it.beginEpochSec == selected.beginEpochSec && it.title == selected.title } }
        ?: guide?.currentEvent
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .width(280.dp)
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                if (showMiniPlayer) MiniStreamPlayer(Modifier.fillMaxSize())
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    guideModel.displayName.ifBlank { guide?.channelName?.ifBlank { "Geen kanaal" } ?: "Kanaal laden" },
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    shown?.title ?: "Geen programma",
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val plot = shown?.plot?.ifBlank { shown.shortDescription }.orEmpty()
                if (plot.isNotBlank()) {
                    Text(
                        plot,
                        color = Color(0xFFB7C0D0),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (guideModel.status.isNotBlank()) {
                    Text(guideModel.status, color = Color(0xFFE8B84A), fontSize = 13.sp)
                }
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 12.dp),
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
                        channelFocusToken += 1
                    },
                    modifier = Modifier.width(220.dp).fillMaxHeight(),
                )
            }
            ChannelColumn(
                bouquetName = guideModel.bouquetName,
                channels = channels,
                tuned = tuned,
                channelFocus = channelFocus,
                bouquetFocus = bouquetFocus,
                epgFocus = epgFocus,
                bouquetsOpen = bouquetsOpen,
                onTune = { channel -> guideModel.tune(channel, onMiniPlayer, onFullscreen) },
                onPreview = guideModel::preview,
                onOpenBouquets = {
                    bouquetsOpen = true
                    bouquetOpenToken += 1
                },
                channelFocusToken = channelFocusToken,
                modifier = Modifier.width(220.dp).fillMaxHeight(),
            )
            val anchor = events.indexOfFirst { it.current }.let { if (it >= 0) it else 0 }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (events.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(epgFocus)
                                .focusProperties { left = channelFocus }
                                .focusable(),
                        )
                    }
                } else {
                    itemsIndexed(events) { index, event ->
                        EpgRow(
                            begin = event.beginLabel,
                            end = event.endLabel,
                            title = event.title,
                            current = event.current,
                            onFocus = { focusedEvent = event },
                            modifier = Modifier
                                .then(if (index == anchor) Modifier.focusRequester(epgFocus) else Modifier)
                                .focusProperties { left = channelFocus },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BouquetColumn(
    bouquets: List<BouquetEntry>,
    selectedReference: String,
    bouquetFocus: FocusRequester,
    settingsFocus: FocusRequester,
    openToken: Int,
    onPreview: (BouquetEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val focusIndex = remember(openToken) {
        bouquets.indexOfFirst { it.reference == selectedReference }.let { if (it >= 0) it else 0 }
    }
    LaunchedEffect(openToken) {
        if (openToken == 0) return@LaunchedEffect
        if (bouquets.isNotEmpty()) listState.scrollToItem(focusIndex)
        runCatching { bouquetFocus.requestFocus() }
    }
    Column(modifier) {
        Text(
            "Bouquets",
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
                        onFocus = { onPreview(bouquet) },
                        modifier = Modifier
                            .then(if (index == focusIndex) Modifier.focusRequester(bouquetFocus) else Modifier)
                            .focusProperties { left = settingsFocus }
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
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = {},
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0xFF161616) else Color.Black,
            contentColor = if (selected) Color(0xFFE8B84A) else Color.White,
            focusedContainerColor = FocusedButton,
            focusedContentColor = FocusedButtonText,
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
private fun ChannelColumn(
    bouquetName: String,
    channels: List<BouquetEntry>,
    tuned: String,
    channelFocus: FocusRequester,
    bouquetFocus: FocusRequester,
    epgFocus: FocusRequester,
    bouquetsOpen: Boolean,
    onTune: (BouquetEntry) -> Unit,
    onPreview: (BouquetEntry) -> Unit,
    onOpenBouquets: () -> Unit,
    channelFocusToken: Int,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val anchor = channels.indexOfFirst { BouquetChannels.sameService(it.reference, tuned) }.let { if (it >= 0) it else 0 }
    LaunchedEffect(anchor, channels.size) {
        if (channels.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == anchor }
        if (!visible) listState.scrollToItem(anchor)
    }
    LaunchedEffect(channelFocusToken) {
        if (channelFocusToken == 0) return@LaunchedEffect
        if (channels.isNotEmpty()) listState.scrollToItem(anchor)
        runCatching { channelFocus.requestFocus() }
    }
    Column(modifier.fillMaxHeight()) {
        Text(
            "Kanalen",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp, start = 8.dp),
        )
        if (bouquetName.isNotBlank()) {
            Text(
                bouquetName,
                color = Color(0xFFB7C0D0),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 6.dp, start = 8.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (channels.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(channelFocus)
                            .focusProperties {
                                if (bouquetsOpen) left = bouquetFocus
                                right = epgFocus
                            }
                            .onPreviewKeyEvent { event ->
                                if (bouquetsOpen || event.type != KeyEventType.KeyDown || event.key != Key.DirectionLeft) {
                                    return@onPreviewKeyEvent false
                                }
                                onOpenBouquets()
                                true
                            }
                            .focusable(),
                    )
                }
            } else {
                itemsIndexed(channels) { index, channel ->
                    ChannelRow(
                        name = channel.name,
                        tuned = BouquetChannels.sameService(channel.reference, tuned),
                        onTune = { onTune(channel) },
                        onFocus = { onPreview(channel) },
                        modifier = Modifier
                            .then(if (index == anchor) Modifier.focusRequester(channelFocus) else Modifier)
                            .focusProperties {
                                if (bouquetsOpen) left = bouquetFocus
                                right = epgFocus
                            }
                            .onPreviewKeyEvent { event ->
                                if (bouquetsOpen || event.type != KeyEventType.KeyDown || event.key != Key.DirectionLeft) {
                                    return@onPreviewKeyEvent false
                                }
                                onOpenBouquets()
                                true
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    name: String,
    tuned: Boolean,
    onTune: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onTune,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (tuned) Color(0xFF161616) else Color.Black,
            contentColor = if (tuned) Color(0xFFE8B84A) else Color.White,
            focusedContainerColor = FocusedButton,
            focusedContentColor = FocusedButtonText,
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
private fun EpgRow(
    begin: String,
    end: String,
    title: String,
    current: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val color = when {
        focused -> Color(0xFF1A1404)
        current -> Color(0xFFE8B84A)
        else -> Color.White
    }
    val muted = when {
        focused -> Color(0xFF1A1404)
        current -> Color(0xFFE8B84A)
        else -> Color(0xFFB7C0D0)
    }
    val background = when {
        focused -> Color(0xFFE8B84A)
        current -> Color(0xFF161616)
        else -> Color.Black
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (begin.isBlank()) "" else "$begin – $end",
            color = muted,
            fontSize = 13.sp,
            modifier = Modifier.width(108.dp),
        )
        Text(title, color = color, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RcInfoPanel(modifier: Modifier = Modifier) {
    Column(modifier) {
        PanelHeader("Afstandsbediening", "Zo werken de knoppen van je tv-afstandsbediening in EnigmaCast")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            SettingsCard("Navigatie", Modifier.weight(1f)) {
                RcRow("Pijltjes", "Omhoog, omlaag, links, rechts")
                RcRow("OK kort", "Bevestigen")
                RcRow("OK vasthouden", "Enigma-menu")
                RcRow("Back kort", "Exit op de tuner")
                RcRow("Back vasthouden", "Terug naar EnigmaCast")
                RcRow("Back in het menu", "App sluiten en stream stoppen")
            }
            SettingsCard("Tv-knoppen", Modifier.weight(1f)) {
                RcRow("Info", "Infobalk")
                RcRow("Guide", "EPG")
                RcRow("P+ / P-", "Zender omhoog of omlaag")
                RcRow("Kleurknoppen", "Rood, groen, geel, blauw")
                RcRow("Blauw vasthouden", "Teletekst")
                RcRow("0–9", "Cijfers")
                RcRow("Play / Pause / Stop", "Afspelen, pauze, stop")
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
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var page by remember { mutableStateOf(SettingPage.Receiver) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Column(modifier) {
        PanelHeader("Instellingen", settings.activeReceiver()?.let { "${it.name}  ·  ${it.host}" }.orEmpty())
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(
                modifier = Modifier
                    .width(190.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SettingNav(EnigmaIcons.Tv, "Ontvanger", page == SettingPage.Receiver, pageFocus, menuFocus) { page = SettingPage.Receiver }
                SettingNav(Icons.Filled.PlayArrow, "Weergave", page == SettingPage.Playback, null, menuFocus) { page = SettingPage.Playback }
                SettingNav(EnigmaIcons.Layers, "OSD", page == SettingPage.Osd, null, menuFocus) { page = SettingPage.Osd }
                SettingNav(Icons.Filled.Info, "Info", page == SettingPage.Info, null, menuFocus) { page = SettingPage.Info }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    SettingPage.Receiver -> ReceiverSettingsPanel()
                    SettingPage.Playback -> PlaybackSettingsPanel(
                        settings.playback.lowLatency,
                        settings.playback.autoReconnect,
                        settings.playback.hdmiPassthrough,
                        settings.playback.subtitlesEnabled,
                        viewModel,
                    )
                    SettingPage.Osd -> OsdSettingsPanel(settings.osd.format, settings.osd.fastPolling, viewModel)
                    SettingPage.Info -> InfoSettingsPanel(settings, viewModel)
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
    pageFocus: FocusRequester?,
    menuFocus: FocusRequester,
    onSelect: () -> Unit,
) {
    NavItem(
        icon = icon,
        label = label,
        selected = selected,
        onClick = onSelect,
        onFocus = onSelect,
        modifier = Modifier
            .then(if (pageFocus != null) Modifier.focusRequester(pageFocus) else Modifier)
            .focusProperties { left = menuFocus },
    )
}

@Composable
private fun PlaybackSettingsPanel(
    fast: Boolean,
    autoReconnect: Boolean,
    hdmiPassthrough: Boolean,
    subtitlesEnabled: Boolean,
    viewModel: SettingsViewModel,
    remote: RemoteDisplayViewModel = hiltViewModel(),
) {
    val playback by remote.uiState.collectAsStateWithLifecycle()
    SettingsCard("Beeld") {
        ChoiceRow(
            title = "Buffer",
            description = if (fast) "Snel: minder vertraging, meer kans op haperen." else "Stabiel: grotere buffer, rustiger beeld.",
            options = listOf("Stabiel", "Snel"),
            selectedIndex = if (fast) 1 else 0,
            onSelect = { viewModel.setLowLatency(it == 1) },
        )
    }
    SettingsCard("Geluid") {
        ChoiceRow(
            title = "Audio-uitvoer",
            description = if (hdmiPassthrough) {
                "HDMI stuurt AC3 en E-AC3 ongewijzigd door als de tv dat ondersteunt."
            } else {
                "Stereo mixt het geluid, ook als de zender AC3 of E-AC3 stuurt."
            },
            options = listOf("Stereo", "HDMI"),
            selectedIndex = if (hdmiPassthrough) 1 else 0,
            onSelect = { viewModel.setHdmiPassthrough(it == 1) },
        )
    }
    SettingsCard("Ondertiteling") {
        ChoiceRow(
            title = "DVB-ondertiteling",
            description = if (playback.subtitlesAvailable) {
                "Deze stream heeft ondertiteling."
            } else {
                "Geen ondertiteling in deze stream."
            },
            options = listOf("Uit", "Aan"),
            selectedIndex = if (subtitlesEnabled) 1 else 0,
            onSelect = { viewModel.setSubtitlesEnabled(it == 1) },
        )
    }
    SettingsCard("Verbinding") {
        ToggleRow(
            title = "Automatisch hervatten",
            description = "Start de stream opnieuw als de verbinding wegvalt.",
            checked = autoReconnect,
            onCheckedChange = viewModel::setAutoReconnect,
        )
    }
}

@Composable
private fun OsdSettingsPanel(
    format: String,
    fastPolling: Boolean,
    viewModel: SettingsViewModel,
) {
    SettingsCard("OSD van de ontvanger") {
        ChoiceRow(
            title = "Formaat",
            description = "PNG is scherper, JPG is sneller op een trage verbinding.",
            options = listOf("PNG", "JPG"),
            selectedIndex = if (format == "jpg") 1 else 0,
            onSelect = { viewModel.setOsdFormat(if (it == 1) "jpg" else "png") },
        )
        ToggleRow(
            title = "Snel verversen",
            description = "Haalt het menu vaker op terwijl je bedient.",
            checked = fastPolling,
            onCheckedChange = viewModel::setFastPolling,
        )
    }
}

@Composable
private fun InfoSettingsPanel(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SettingsCard("App") {
        InfoRow("Versie", BuildConfig.VERSION_NAME)
        InfoRow("Build", BuildConfig.VERSION_CODE.toString())
    }
    SettingsCard("Diagnose") {
        ToggleRow("Debug-overlay", settings.debug.overlay, viewModel::setDebugOverlay)
        ToggleRow("Uitgebreide logging", settings.debug.verboseLogging, viewModel::setVerboseLogging)
        ToggleRow("OSD-rand", settings.osd.debugBorders, viewModel::setDebugBorders)
    }
}

@Composable
private fun ReceiverSettingsPanel(
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    SettingsCard("Verbinding") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
            CompactField("Naam", viewModel.name, { viewModel.name = it }, Modifier.weight(1f))
            CompactField("IP-adres", viewModel.host, { viewModel.host = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
            CompactField("Webif-poort", viewModel.httpPort, { viewModel.httpPort = it }, Modifier.weight(1f))
            CompactField("Stream-poort", viewModel.streamPort, { viewModel.streamPort = it }, Modifier.weight(1f))
        }
    }
    SettingsCard("Inloggen") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
            CompactField("Gebruiker", viewModel.username, { viewModel.username = it }, Modifier.weight(1f))
            CompactField("Wachtwoord", viewModel.password, { viewModel.password = it }, Modifier.weight(1f), password = true)
        }
        ToggleRow(
            title = "HTTPS",
            description = "Versleutelde verbinding met OpenWebif.",
            checked = viewModel.useHttps,
            onCheckedChange = { viewModel.useHttps = it },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ActionTile(
            icon = Icons.Filled.Check,
            label = "Opslaan",
            onClick = { viewModel.save() },
            enabled = !viewModel.busy,
            tone = TileTone.Primary,
        )
        if (viewModel.status.isNotBlank()) {
            Text(viewModel.status, color = EnigmaColors.Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun CompactField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier) {
        Text(label, color = if (focused) EnigmaColors.Accent else EnigmaColors.Muted, fontSize = 11.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            interactionSource = interaction,
            textStyle = TextStyle(color = EnigmaColors.Text, fontSize = 14.sp),
            cursorBrush = SolidColor(EnigmaColors.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .background(EnigmaColors.Raised, RoundedCornerShape(8.dp))
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) EnigmaColors.Accent else Color(0xFF3A4458),
                    RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun MiniStreamPlayer(
    modifier: Modifier = Modifier,
    viewModel: RemoteDisplayViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    Box(modifier) {
        EnigmaVideoTexture(viewModel.player(), ui.cues, Modifier.fillMaxSize())
        val error = ui.playerState.error
        if (!error.isNullOrBlank()) {
            Text(
                error,
                color = Color(0xFFE8B84A),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            )
        }
    }
}
