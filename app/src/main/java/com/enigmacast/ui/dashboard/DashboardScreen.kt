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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import kotlinx.coroutines.delay
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.ui.connection.ConnectionViewModel
import com.enigmacast.ui.remote.EnigmaVideoTexture
import com.enigmacast.ui.remote.RemoteDisplayViewModel

private enum class DashSection {
    Stream,
    RcInfo,
    Settings,
}

@Composable
fun DashboardScreen(
    showMiniPlayer: Boolean,
    onStream: () -> Unit,
) {
    var section by remember { mutableStateOf(DashSection.Stream) }
    val streamFocus = remember { FocusRequester() }
    val epgFocus = remember { FocusRequester() }
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
                        .focusProperties { right = epgFocus },
                    onFocus = { section = DashSection.Stream },
                    onClick = onStream,
                )
                MenuEntry(
                    icon = Icons.Filled.Info,
                    label = "RC Info",
                    onFocus = { section = DashSection.RcInfo },
                    onClick = { section = DashSection.RcInfo },
                )
                MenuEntry(
                    icon = Icons.Filled.Settings,
                    label = "Settings",
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
                when (section) {
                    DashSection.Stream -> StreamPanel(showMiniPlayer, epgFocus, streamFocus)
                    DashSection.RcInfo -> RcInfoPanel()
                    DashSection.Settings -> ReceiverSettingsPanel()
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
            focusedContainerColor = Color(0xFFE8B84A),
            focusedContentColor = Color(0xFF1A1404),
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
    epgFocus: FocusRequester,
    menuFocus: FocusRequester,
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
    val events = guide?.events.orEmpty()
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
                    guide?.channelName?.ifBlank { "Geen kanaal" } ?: "Kanaal laden",
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
        val anchor = events.indexOfFirst { it.current }.let { if (it >= 0) it else 0 }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (events.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(epgFocus)
                            .focusProperties { left = menuFocus }
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
                            .focusProperties { left = menuFocus },
                    )
                }
            }
        }
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
private fun RcInfoPanel() {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
        Text("Receiver", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
                focusedContainerColor = Color(0xFFE8B84A),
                focusedContentColor = Color(0xFF1A1404),
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
    EnigmaVideoTexture(viewModel.player(), modifier)
}
