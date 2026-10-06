package com.enigmacast.ui.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.tuner.ChannelListNavigation
import com.enigmacast.domain.tuner.NowEvent
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.domain.tuner.TunerUserKind
import com.enigmacast.live.TunerState
import com.enigmacast.live.TunerStatus
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.StatusPill
import com.enigmacast.ui.theme.requestWhenReady
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The app's own on-screen display for own-tuner mode. Enigma2 draws its OSD on the
 * receiver's HDMI output only, and a direct stream carries none of it, so the channel
 * banner, channel list and "tuners full" notice are drawn here.
 */
@Composable
fun OwnTunerOverlay(
    ui: RemoteDisplayUiState,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    numberOf: (BouquetEntry) -> Int,
    onZap: (BouquetEntry) -> Unit,
    onChooseBouquet: (BouquetEntry) -> Unit,
) {
    val own = ui.own
    val tuner = own.tuner
    Box(modifier = Modifier.fillMaxSize()) {
        val channel = own.channel
        if (tuner.status == TunerStatus.CHECKING || tuner.status == TunerStatus.STARTING) {
            StatusPill(
                text = tuner.message.ifBlank { "Laden…" },
                color = EnigmaColors.Accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp),
            )
        }
        if (tuner.status == TunerStatus.FULL || tuner.status == TunerStatus.PROBLEM || tuner.status == TunerStatus.NO_PICTURE) {
            TunerNotice(
                tuner = tuner,
                channelName = channel?.name.orEmpty(),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (ui.numberEntry.isNotEmpty()) {
            Text(
                ui.numberEntry,
                color = EnigmaColors.Accent,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .background(EnigmaColors.Scrim, RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        if (ui.notice.isNotBlank()) {
            Text(
                ui.notice,
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(32.dp)
                    .background(EnigmaColors.Scrim, RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        if (ui.banner && channel != null && !ui.channelList) {
            ChannelBanner(
                channel = channel,
                number = numberOf(channel),
                guide = own.guide?.takeIf { BouquetChannels.sameService(it.serviceReference, channel.reference) },
                player = ui.playerState,
                tuner = tuner,
                details = ui.details,
                picon = picon,
                cachedPicon = cachedPicon,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (ui.channelList) {
            ChannelListOverlay(
                ui = ui,
                numberOf = numberOf,
                picon = picon,
                cachedPicon = cachedPicon,
                onZap = onZap,
                onChooseBouquet = onChooseBouquet,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}

@Composable
private fun ChannelBanner(
    channel: BouquetEntry,
    number: Int,
    guide: ChannelGuide?,
    player: PlayerState,
    tuner: TunerState,
    details: Boolean,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }
    val events = guide?.events.orEmpty()
    val current = events.firstOrNull { it.beginEpochSec <= nowSec && nowSec < it.beginEpochSec + it.durationSec }
        ?: guide?.currentEvent
    val next = current?.let { now -> events.firstOrNull { it.beginEpochSec >= now.beginEpochSec + now.durationSec } }
    Row(
        modifier = modifier
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .fillMaxWidth()
            .background(EnigmaColors.Scrim, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Picon(channel, picon, cachedPicon, Modifier.width(150.dp).height(90.dp))
        Spacer(Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (number > 0) {
                    Text(
                        "$number",
                        color = EnigmaColors.Accent,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 14.dp),
                    )
                }
                Text(
                    channel.name,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (tuner.status == TunerStatus.FULL) {
                    StatusPill("Tuners bezet", EnigmaColors.Danger)
                }
            }
            if (current != null) {
                EventLine(current, emphasised = true)
                ProgressBar(current, nowSec)
            } else {
                Text("Geen programmagegevens", color = EnigmaColors.Muted, fontSize = 16.sp)
            }
            if (next != null) EventLine(next, emphasised = false)
            if (details && current != null) {
                val plot = current.plot.ifBlank { current.shortDescription }
                if (plot.isNotBlank()) {
                    Text(
                        plot,
                        color = EnigmaColors.Muted,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(
            modifier = Modifier.widthIn(min = 160.dp, max = 260.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            videoLabel(player).takeIf { it.isNotBlank() }?.let { Tag(it) }
            if (player.audioLabel.isNotBlank()) {
                val more = if (player.audioTrackCount > 1) "  (${player.audioTrackCount} talen)" else ""
                Tag("Audio: ${player.audioLabel}$more")
            }
            when {
                player.subtitleLabel.isNotBlank() -> Tag("Ondertitels: ${player.subtitleLabel}")
                player.subtitleTrackCount > 0 -> Tag("Ondertitels beschikbaar")
            }
            Text("OK: zenderlijst · Info: details", color = EnigmaColors.Subtle, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EventLine(event: EpgEvent, emphasised: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOf(event.beginLabel, event.endLabel).filter { it.isNotBlank() }.joinToString(" – "),
            color = if (emphasised) EnigmaColors.Accent else EnigmaColors.Muted,
            fontSize = if (emphasised) 18.sp else 15.sp,
            modifier = Modifier.width(130.dp),
        )
        Text(
            event.title.ifBlank { "Onbekend programma" },
            color = if (emphasised) Color.White else EnigmaColors.Muted,
            fontSize = if (emphasised) 18.sp else 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProgressBar(event: EpgEvent, nowSec: Long) {
    val fraction = if (event.durationSec > 0 && event.beginEpochSec > 0) {
        ((nowSec - event.beginEpochSec).toFloat() / event.durationSec).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(EnigmaColors.Raised, RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(EnigmaColors.Accent, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(EnigmaColors.Raised, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private fun videoLabel(player: PlayerState): String {
    if (player.videoHeight <= 0) return ""
    val kind = when {
        player.videoHeight >= 2000 -> "UHD"
        player.videoHeight >= 700 -> "HD"
        else -> "SD"
    }
    return "$kind ${player.videoWidth}×${player.videoHeight}"
}

@Composable
private fun Picon(
    entry: BouquetEntry,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    modifier: Modifier = Modifier,
    fallbackSize: TextUnit = 14.sp,
) {
    var bitmap by remember(entry.reference) { mutableStateOf(cachedPicon(entry)) }
    LaunchedEffect(entry.reference) {
        if (bitmap == null) bitmap = picon(entry)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = entry.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                entry.name.take(10),
                color = EnigmaColors.Muted,
                fontSize = fallbackSize,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TunerNotice(tuner: TunerState, channelName: String, modifier: Modifier = Modifier) {
    val title = when (tuner.status) {
        TunerStatus.FULL -> "Geen tuner vrij"
        TunerStatus.NO_PICTURE -> "Geen beeld"
        else -> "Stream niet gestart"
    }
    Column(
        modifier = modifier
            .widthIn(max = 760.dp)
            .background(EnigmaColors.Scrim, RoundedCornerShape(16.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(if (tuner.status == TunerStatus.NO_PICTURE) EnigmaColors.Accent else EnigmaColors.Danger, RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if (channelName.isNotBlank()) {
                Text("  ·  $channelName", color = EnigmaColors.Muted, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(tuner.message, color = Color.White, fontSize = 18.sp, lineHeight = 25.sp)
        if (tuner.users.isNotEmpty()) {
            Text("In gebruik:", color = EnigmaColors.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            tuner.users.forEach { user ->
                val prefix = when (user.kind) {
                    TunerUserKind.LIVE -> "📺"
                    TunerUserKind.RECORDING -> "⏺"
                    TunerUserKind.STREAM -> "📱"
                }
                Text("$prefix  ${user.label}", color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val hint = if (tuner.status == TunerStatus.NO_PICTURE) {
            "▲▼ andere zender · Terug: menu"
        } else {
            "OK: opnieuw proberen · ▲▼ andere zender · Terug: menu"
        }
        Text(hint, color = EnigmaColors.Subtle, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ChannelListOverlay(
    ui: RemoteDisplayUiState,
    numberOf: (BouquetEntry) -> Int,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    onZap: (BouquetEntry) -> Unit,
    onChooseBouquet: (BouquetEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(EnigmaColors.Scrim),
    ) {
        if (ui.bouquetPanel) {
            BouquetPanel(
                bouquets = ui.bouquets,
                activeReference = ui.own.bouquetReference,
                onChoose = onChooseBouquet,
            )
            Box(Modifier.fillMaxHeight().width(1.dp).background(EnigmaColors.Raised))
        }
        ChannelPanel(
            ui = ui,
            numberOf = numberOf,
            picon = picon,
            cachedPicon = cachedPicon,
            onZap = onZap,
        )
    }
}

/** The bouquets, shown left of the channel list by Left; OK or Right shows that bouquet's channels. */
@Composable
private fun BouquetPanel(
    bouquets: List<BouquetEntry>,
    activeReference: String,
    onChoose: (BouquetEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val anchor = ChannelListNavigation.bouquetFocusIndex(bouquets, activeReference)
    LaunchedEffect(bouquets.size) {
        if (bouquets.isEmpty()) return@LaunchedEffect
        listState.scrollToItem((anchor - 4).coerceAtLeast(0))
        focus.requestWhenReady()
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(210.dp)
            .padding(horizontal = 10.dp, vertical = 20.dp),
    ) {
        Text("Bouquets", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("OK of ▶ kiest", color = EnigmaColors.Subtle, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (bouquets.isEmpty()) {
            Text("Bouquets laden…", color = EnigmaColors.Muted, fontSize = 13.sp)
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            itemsIndexed(bouquets, key = { index, entry -> "$index:${entry.reference}" }) { index, bouquet ->
                CompactListRow(
                    onClick = { onChoose(bouquet) },
                    selected = index == anchor && activeReference.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                    modifier = Modifier
                        .then(if (index == anchor) Modifier.focusRequester(focus) else Modifier)
                        .focusProperties { left = FocusRequester.Cancel }
                        .onPreviewKeyEvent { event ->
                            if (event.key != Key.DirectionRight) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyDown) onChoose(bouquet)
                            true
                        },
                ) {
                    Text(bouquet.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Channel list: number, picon, name and what is on now (start time, title, thin progress
 * bar) on one dense row, so about 15 channels fit on a 1080p screen.
 */
@Composable
private fun ChannelPanel(
    ui: RemoteDisplayUiState,
    numberOf: (BouquetEntry) -> Int,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    onZap: (BouquetEntry) -> Unit,
) {
    val own = ui.own
    val channels = own.channels
    val current = own.channel?.reference.orEmpty()
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val anchor = ChannelListNavigation.channelFocusIndex(channels, current)
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }
    // Focus the playing channel when the list opens, after a bouquet was chosen and when
    // the bouquet column closes (the token), and once the new bouquet's channels arrive.
    LaunchedEffect(ui.listFocusToken, own.bouquetReference, channels.size) {
        if (channels.isEmpty() || ui.bouquetPanel) return@LaunchedEffect
        listState.scrollToItem((anchor - 5).coerceAtLeast(0))
        focus.requestWhenReady()
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(600.dp)
            .padding(horizontal = 12.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 8.dp)) {
            Text("Zenders", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                own.bouquetName,
                color = EnigmaColors.Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp, bottom = 2.dp),
            )
            if (!ui.bouquetPanel) {
                Text("◀ Bouquets", color = EnigmaColors.Subtle, fontSize = 11.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        if (channels.isEmpty()) {
            Text("Geen zenders geladen", color = EnigmaColors.Muted, fontSize = 13.sp)
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            itemsIndexed(channels, key = { index, entry -> "$index:${entry.reference}" }) { index, entry ->
                CompactListRow(
                    onClick = { onZap(entry) },
                    selected = BouquetChannels.sameService(entry.reference, current),
                    modifier = if (index == anchor) Modifier.focusRequester(focus) else Modifier,
                ) { focused ->
                    ChannelRowContent(
                        entry = entry,
                        number = numberOf(entry),
                        event = NowEvents.lookup(ui.nowEvents, entry.reference, nowSec),
                        nowSec = nowSec,
                        focused = focused,
                        picon = picon,
                        cachedPicon = cachedPicon,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.ChannelRowContent(
    entry: BouquetEntry,
    number: Int,
    event: NowEvent?,
    nowSec: Long,
    focused: Boolean,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
) {
    val secondary = if (focused) EnigmaColors.OnFocus.copy(alpha = 0.8f) else EnigmaColors.Muted
    Text(
        if (number > 0) "$number" else "",
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier.width(34.dp),
    )
    Picon(entry, picon, cachedPicon, Modifier.width(36.dp).height(22.dp), fallbackSize = 8.sp)
    Spacer(Modifier.width(8.dp))
    Text(
        entry.name,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(0.38f),
    )
    Spacer(Modifier.width(8.dp))
    Column(modifier = Modifier.weight(0.62f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                event?.let { timeLabel(it.beginEpochSec) }.orEmpty(),
                color = secondary,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.width(36.dp),
            )
            Text(
                event?.title.orEmpty(),
                color = if (focused) EnigmaColors.OnFocus else EnigmaColors.Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (event != null) {
            Box(
                modifier = Modifier
                    .padding(start = 36.dp, top = 2.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(EnigmaColors.Raised, RoundedCornerShape(1.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(event.progress(nowSec))
                        .height(2.dp)
                        .background(if (focused) EnigmaColors.OnFocus else EnigmaColors.Accent, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun timeLabel(epochSec: Long): String =
    Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(ClockFormat)
