package com.enigmacast.ui.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.domain.model.PlayerState
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.tuner.TunerUserKind
import com.enigmacast.live.TunerState
import com.enigmacast.live.TunerStatus
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.StatusPill
import kotlinx.coroutines.delay

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
                bouquetName = own.bouquetName,
                channels = own.channels,
                current = channel?.reference.orEmpty(),
                numberOf = numberOf,
                picon = picon,
                cachedPicon = cachedPicon,
                onZap = onZap,
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
                fontSize = 14.sp,
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
    bouquetName: String,
    channels: List<BouquetEntry>,
    current: String,
    numberOf: (BouquetEntry) -> Int,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    onZap: (BouquetEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val anchor = channels.indexOfFirst { BouquetChannels.sameService(it.reference, current) }.coerceAtLeast(0)
    LaunchedEffect(Unit) {
        if (channels.isEmpty()) return@LaunchedEffect
        listState.scrollToItem((anchor - 3).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(460.dp)
            .background(EnigmaColors.Scrim)
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Text("Zenders", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        if (bouquetName.isNotBlank()) {
            Text(bouquetName, color = EnigmaColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        if (channels.isEmpty()) {
            Text("Geen zenders geladen", color = EnigmaColors.Muted, fontSize = 16.sp)
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(channels, key = { index, entry -> "$index:${entry.reference}" }) { index, entry ->
                val tuned = BouquetChannels.sameService(entry.reference, current)
                Button(
                    onClick = { onZap(entry) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == anchor) Modifier.focusRequester(focus) else Modifier),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = if (tuned) EnigmaColors.Selected else Color.Transparent,
                        contentColor = if (tuned) EnigmaColors.Accent else Color.White,
                        focusedContainerColor = EnigmaColors.Focus,
                        focusedContentColor = EnigmaColors.OnFocus,
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val number = numberOf(entry)
                        Text(
                            if (number > 0) "$number" else "",
                            fontSize = 15.sp,
                            modifier = Modifier.width(52.dp),
                        )
                        Picon(entry, picon, cachedPicon, Modifier.width(56.dp).height(34.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(entry.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
