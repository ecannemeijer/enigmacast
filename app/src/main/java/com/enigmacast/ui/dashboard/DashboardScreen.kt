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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
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
import kotlin.math.roundToInt
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
import com.enigmacast.ui.settings.SettingsViewModel

private val SubmenuSurface = Color.Black
private val FocusedButton = Color(0xFF8A5610)
private val FocusedButtonText = Color.White

private enum class DashSection {
    Stream,
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
) {
    var section by remember { mutableStateOf(DashSection.Stream) }
    val streamFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    val epgFocus = remember { FocusRequester() }
    val settingsMenuFocus = remember { FocusRequester() }
    val settingsPageFocus = remember { FocusRequester() }
    val density = LocalDensity.current
    var contentTop by remember { mutableIntStateOf(0) }
    var rcTop by remember { mutableIntStateOf(0) }
    var settingsTop by remember { mutableIntStateOf(0) }
    fun alignedHeight(itemTop: Int) = with(density) { (itemTop - contentTop).coerceAtLeast(0).toDp() }
    LaunchedEffect(Unit) {
        streamFocus.requestFocus()
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(188.dp)
                    .fillMaxHeight()
                    .background(Color.Black)
                    .padding(horizontal = 10.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.enigmacast_logo),
                    contentDescription = "EnigmaCast",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    contentScale = ContentScale.FillWidth,
                )
                MenuEntry(
                    icon = Icons.Filled.PlayArrow,
                    label = "Stream",
                    modifier = Modifier
                        .focusRequester(streamFocus)
                        .focusProperties { right = channelFocus },
                    onFocus = { section = DashSection.Stream },
                    onClick = onStream,
                )
                MenuEntry(
                    icon = Icons.Filled.Info,
                    label = "RC Info",
                    modifier = Modifier.onGloballyPositioned { rcTop = it.positionInRoot().y.roundToInt() },
                    onFocus = { section = DashSection.RcInfo },
                    onClick = { section = DashSection.RcInfo },
                )
                MenuEntry(
                    icon = Icons.Filled.Settings,
                    label = "Settings",
                    modifier = Modifier
                        .focusRequester(settingsMenuFocus)
                        .focusProperties { right = settingsPageFocus }
                        .onGloballyPositioned { settingsTop = it.positionInRoot().y.roundToInt() },
                    onFocus = { section = DashSection.Settings },
                    onClick = { section = DashSection.Settings },
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black)
                    .padding(start = 8.dp, top = 28.dp, end = 24.dp, bottom = 24.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { contentTop = it.positionInRoot().y.roundToInt() },
                ) {
                    when (section) {
                        DashSection.Stream -> StreamPanel(
                            showMiniPlayer,
                            channelFocus,
                            epgFocus,
                            streamFocus,
                            onMiniPlayer,
                            onStream,
                        )
                        DashSection.RcInfo -> Column(Modifier.fillMaxSize()) {
                            Spacer(Modifier.height(alignedHeight(rcTop)))
                            RcInfoPanel(Modifier.weight(1f))
                        }
                        DashSection.Settings -> Column(Modifier.fillMaxSize()) {
                            Spacer(Modifier.height(alignedHeight(settingsTop)))
                            SettingsHub(settingsPageFocus, settingsMenuFocus, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(
    icon: ImageVector,
    label: String,
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
            containerColor = Color.Black,
            contentColor = Color.White,
            focusedContainerColor = FocusedButton,
            focusedContentColor = FocusedButtonText,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StreamPanel(
    showMiniPlayer: Boolean,
    channelFocus: FocusRequester,
    epgFocus: FocusRequester,
    menuFocus: FocusRequester,
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
    val tuned = guideModel.tunedReference.ifBlank { guide?.serviceReference.orEmpty() }
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
            ChannelColumn(
                bouquetName = guideModel.bouquetName,
                channels = channels,
                tuned = tuned,
                channelFocus = channelFocus,
                menuFocus = menuFocus,
                epgFocus = epgFocus,
                onTune = { channel -> guideModel.tune(channel, onMiniPlayer, onFullscreen) },
                onPreview = guideModel::preview,
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
private fun ChannelColumn(
    bouquetName: String,
    channels: List<BouquetEntry>,
    tuned: String,
    channelFocus: FocusRequester,
    menuFocus: FocusRequester,
    epgFocus: FocusRequester,
    onTune: (BouquetEntry) -> Unit,
    onPreview: (BouquetEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val anchor = channels.indexOfFirst { BouquetChannels.sameService(it.reference, tuned) }.let { if (it >= 0) it else 0 }
    LaunchedEffect(anchor, channels.size) {
        if (channels.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == anchor }
        if (!visible) listState.scrollToItem(anchor)
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
                                left = menuFocus
                                right = epgFocus
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
                                left = menuFocus
                                right = epgFocus
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
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Afstandsbediening", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        RcRow("Pijltjes", "Omhoog, omlaag, links, rechts")
        RcRow("OK kort", "Bevestigen")
        RcRow("OK vasthouden", "Enigma-menu")
        RcRow("Back kort", "Exit op de tuner")
        RcRow("Back vasthouden", "Terug naar EnigmaCast")
        RcRow("Info", "Infobalk")
        RcRow("Guide", "EPG")
        RcRow("P+ / P-", "Zender omhoog of omlaag")
        RcRow("Kleurknoppen", "Rood, groen, geel, blauw")
        RcRow("0–9", "Cijfers")
        RcRow("Play / Pause / Stop", "Afspelen, pauze, stop")
    }
}

@Composable
private fun RcRow(button: String, action: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(button, color = Color.White, fontSize = 15.sp, modifier = Modifier.width(180.dp))
        Text(action, color = Color(0xFFB7C0D0), fontSize = 15.sp)
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
    Row(modifier = modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            modifier = Modifier
                .width(160.dp)
                .fillMaxHeight()
                .background(SubmenuSurface)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingNav("Ontvanger", page == SettingPage.Receiver, pageFocus, menuFocus) { page = SettingPage.Receiver }
            SettingNav("Weergave", page == SettingPage.Playback, null, menuFocus) { page = SettingPage.Playback }
            SettingNav("OSD", page == SettingPage.Osd, null, menuFocus) { page = SettingPage.Osd }
            SettingNav("Info", page == SettingPage.Info, null, menuFocus) { page = SettingPage.Info }
        }
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (page) {
                SettingPage.Receiver -> ReceiverSettingsPanel()
                SettingPage.Playback -> PlaybackSettingsPanel(settings.playback.lowLatency, settings.playback.autoReconnect, viewModel)
                SettingPage.Osd -> OsdSettingsPanel(settings.osd.format, settings.osd.fastPolling, viewModel)
                SettingPage.Info -> InfoSettingsPanel(settings, viewModel)
            }
        }
    }
}

@Composable
private fun SettingNav(
    label: String,
    selected: Boolean,
    pageFocus: FocusRequester?,
    menuFocus: FocusRequester,
    onSelect: () -> Unit,
) {
    Button(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (pageFocus != null) Modifier.focusRequester(pageFocus) else Modifier)
            .focusProperties { left = menuFocus }
            .onFocusChanged { if (it.isFocused) onSelect() },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0xFF161616) else Color.Black,
            contentColor = if (selected) Color(0xFFE8B84A) else Color.White,
            focusedContainerColor = FocusedButton,
            focusedContentColor = FocusedButtonText,
        ),
    ) {
        Text(label, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PlaybackSettingsPanel(
    fast: Boolean,
    autoReconnect: Boolean,
    viewModel: SettingsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 420.dp)) {
        Text("Weergave", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            if (fast) "Snel: minder vertraging, meer kans op haperen." else "Stabiel: grotere buffer, rustiger beeld.",
            color = Color(0xFFB7C0D0),
            fontSize = 13.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileButton("Stabiel", !fast) { viewModel.setLowLatency(false) }
            ProfileButton("Snel", fast) { viewModel.setLowLatency(true) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Automatisch hervatten", color = Color.White, fontSize = 13.sp)
            Switch(checked = autoReconnect, onCheckedChange = viewModel::setAutoReconnect)
        }
    }
}

@Composable
private fun ProfileButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0xFF161616) else Color.Black,
            contentColor = if (selected) Color(0xFFE8B84A) else Color.White,
            focusedContainerColor = FocusedButton,
            focusedContentColor = FocusedButtonText,
        ),
    ) {
        Text(label, fontSize = 14.sp)
    }
}

@Composable
private fun OsdSettingsPanel(
    format: String,
    fastPolling: Boolean,
    viewModel: SettingsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 420.dp)) {
        Text("OSD", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        ProfileButton(if (format == "jpg") "Formaat: JPG" else "Formaat: PNG", selected = true) {
            viewModel.setOsdFormat(if (format == "jpg") "png" else "jpg")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Snel verversen", color = Color.White, fontSize = 13.sp)
            Switch(checked = fastPolling, onCheckedChange = viewModel::setFastPolling)
        }
    }
}

@Composable
private fun InfoSettingsPanel(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 460.dp)) {
        Text("Info", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "${BuildConfig.VERSION_NAME}  ·  build ${BuildConfig.VERSION_CODE}",
            color = Color(0xFFB7C0D0),
            fontSize = 13.sp,
        )
        DebugSwitch("Debug-overlay", settings.debug.overlay, viewModel::setDebugOverlay)
        DebugSwitch("Uitgebreide logging", settings.debug.verboseLogging, viewModel::setVerboseLogging)
        DebugSwitch("OSD-rand", settings.osd.debugBorders, viewModel::setDebugBorders)
    }
}

@Composable
private fun DebugSwitch(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color.White, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ReceiverSettingsPanel(
    viewModel: ConnectionViewModel = hiltViewModel(),
) {
    Column(
        modifier = Modifier
            .widthIn(max = 460.dp)
            .heightIn(max = 420.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Ontvanger", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactField("Naam", viewModel.name, { viewModel.name = it }, Modifier.weight(1f))
            CompactField("IP-adres", viewModel.host, { viewModel.host = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactField("Webif-poort", viewModel.httpPort, { viewModel.httpPort = it }, Modifier.weight(1f))
            CompactField("Stream-poort", viewModel.streamPort, { viewModel.streamPort = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactField("Gebruiker", viewModel.username, { viewModel.username = it }, Modifier.weight(1f))
            CompactField("Wachtwoord", viewModel.password, { viewModel.password = it }, Modifier.weight(1f), password = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("HTTPS", color = Color.White, fontSize = 13.sp)
            Switch(checked = viewModel.useHttps, onCheckedChange = { viewModel.useHttps = it })
        }
        Button(
            onClick = { viewModel.save() },
            enabled = !viewModel.busy,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            colors = ButtonDefaults.colors(
                containerColor = Color(0xFF161616),
                contentColor = Color.White,
                focusedContainerColor = FocusedButton,
                focusedContentColor = FocusedButtonText,
            ),
        ) {
            Text("Opslaan", fontSize = 15.sp)
        }
        if (viewModel.status.isNotBlank()) {
            Text(viewModel.status, color = Color(0xFFB7C0D0), fontSize = 14.sp)
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
    Column(modifier) {
        Text(label, color = Color(0xFFB7C0D0), fontSize = 11.sp)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            cursorBrush = SolidColor(Color(0xFFE8B84A)),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .border(1.dp, Color(0xFF3A4458))
                .padding(horizontal = 8.dp, vertical = 6.dp),
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
        EnigmaVideoTexture(viewModel.player(), Modifier.fillMaxSize())
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
