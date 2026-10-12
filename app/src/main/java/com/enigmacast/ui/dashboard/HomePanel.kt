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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelMemory
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.recording.Recording
import com.enigmacast.ui.components.EnigmaChannelCard
import com.enigmacast.ui.components.EnigmaGlassButton
import com.enigmacast.ui.components.EnigmaGlassPanel
import com.enigmacast.ui.components.EnigmaProgramProgress
import com.enigmacast.ui.power.ReceiverPowerViewModel
import com.enigmacast.ui.remote.rememberGuideClock
import com.enigmacast.ui.settings.SettingsViewModel
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.LocalEdgeLeft
import com.enigmacast.util.asString
import androidx.compose.ui.res.stringResource

/**
 * Cinematic landing page. Cards only appear when the receiver already has that data.
 * Focus never starts a stream.
 */
@Composable
internal fun HomePanel(
    contentFocus: FocusRequester,
    menuFocus: FocusRequester,
    onRetreatTo: (FocusRequester) -> Unit,
    showMiniPlayer: Boolean,
    onContinue: (LastChannel) -> Unit,
    onPlayChannel: (BouquetEntry) -> Unit,
    onOpenBouquet: (BouquetEntry) -> Unit,
    onOpenRecording: (Recording) -> Unit,
    guideModel: StreamGuideViewModel,
    powerModel: ReceiverPowerViewModel,
    myItemsModel: MyItemsViewModel,
    settingsModel: SettingsViewModel = hiltViewModel(),
) {
    BindGuide(GuideMedium.Television, guideModel)
    val settings by settingsModel.settings.collectAsStateWithLifecycle()
    val receiver = settings.activeReceiver()
    val receiverId = receiver?.id.orEmpty()
    val last = ChannelMemory.lastFor(settings, receiverId)
    val recents = ChannelMemory.recentFor(settings, receiverId)
    val recordings by myItemsModel.items.collectAsStateWithLifecycle()
    val grid by guideModel.grid.state.collectAsState()
    val nowSec = rememberGuideClock()
    val guide = guideModel.guide
    val current = guide?.events?.firstOrNull { it.beginEpochSec <= nowSec && nowSec < it.beginEpochSec + it.durationSec }
        ?: guide?.currentEvent
    val next = current?.let { now ->
        guide?.events?.firstOrNull { it.beginEpochSec >= now.beginEpochSec + now.durationSec }
    }
    val channelName = guide?.channelName?.ifBlank { guideModel.displayName }.orEmpty().ifBlank { guideModel.displayName }
    val onNow = grid.channels.mapNotNull { entry ->
        val event = grid.eventsOf(entry).firstOrNull { it.isAiring(nowSec) } ?: return@mapNotNull null
        entry to event
    }.take(12)
    val left = Modifier.focusProperties { left = menuFocus }
    val continueVisible = last.reference.isNotBlank()
    val focusRecents = !continueVisible && recents.isNotEmpty()
    val focusNow = !focusRecents && !continueVisible && onNow.isNotEmpty()
    val focusGroups = !continueVisible && !focusRecents && !focusNow && guideModel.bouquets.isNotEmpty()
    val focusRecordings = !continueVisible && !focusRecents && !focusNow && !focusGroups && recordings.isNotEmpty()
    val focusEmpty = !continueVisible && !focusRecents && !focusNow && !focusGroups && !focusRecordings
    fun cardFocus(index: Int, anchor: Boolean, width: Dp): Modifier {
        val focus = if (index == 0 && anchor) Modifier.focusRequester(contentFocus) else Modifier
        val toMenu = if (index == 0) left else Modifier
        return Modifier.width(width).then(focus).then(toMenu)
    }
    CompositionLocalProvider(LocalEdgeLeft provides menuFocus) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .onFocusChanged { if (it.hasFocus) onRetreatTo(menuFocus) },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
        EnigmaGlassPanel(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    receiver?.name?.ifBlank { receiver.host } ?: stringResource(R.string.home_receiver),
                    color = EnigmaColors.Cyan,
                    fontSize = EnigmaType.Label,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        current != null -> current.title
                        channelName.isNotBlank() -> channelName
                        else -> stringResource(R.string.home_empty)
                    },
                    color = EnigmaColors.Text,
                    fontSize = EnigmaType.Hero,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (current != null) {
                    if (channelName.isNotBlank()) {
                        Text(channelName, color = EnigmaColors.Muted, fontSize = EnigmaType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val plot = current.plot.ifBlank { current.shortDescription }
                    if (plot.isNotBlank()) {
                        Text(plot, color = EnigmaColors.Muted, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        listOf(current.beginLabel, current.endLabel).filter { it.isNotBlank() }.joinToString(" – "),
                        color = EnigmaColors.Accent,
                        fontSize = 14.sp,
                    )
                    EnigmaProgramProgress(progressOf(current, nowSec), Modifier.width(360.dp))
                    if (next != null && next.title.isNotBlank()) {
                        Text(stringResource(R.string.home_next) + "  " + next.title, color = EnigmaColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else if (channelName.isNotBlank()) {
                    Text(stringResource(R.string.home_no_programme), color = EnigmaColors.Muted, fontSize = EnigmaType.Body)
                }
                if (continueVisible) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                        EnigmaGlassButton(
                            text = stringResource(R.string.home_continue),
                            onClick = { onContinue(last) },
                            modifier = Modifier.focusRequester(contentFocus).then(left),
                        )
                    }
                }
            }
        }
        if (showMiniPlayer && !guideModel.loadingChannels) {
            Box(
                Modifier
                    .width(320.dp)
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                MiniStreamPlayer(Modifier.fillMaxSize())
            }
        }
        }
        if (focusEmpty) {
            Spacer(Modifier.size(1.dp).focusRequester(contentFocus).then(left).focusable())
        }
        if (recents.isNotEmpty()) {
            HomeRow(stringResource(R.string.home_recent), recents) { index, item ->
                EnigmaChannelCard(
                    title = item.name.ifBlank { item.reference },
                    subtitle = "",
                    onClick = { onContinue(item) },
                    modifier = cardFocus(index, focusRecents, 240.dp),
                )
            }
        }
        if (onNow.isNotEmpty()) {
            HomeRow(stringResource(R.string.home_on_now), onNow) { index, pair ->
                val (entry, event) = pair
                EnigmaChannelCard(
                    title = entry.name,
                    subtitle = event.title,
                    progress = event.progress(nowSec),
                    onClick = { onPlayChannel(entry) },
                    modifier = cardFocus(index, focusNow, 260.dp),
                )
            }
        }
        if (guideModel.bouquets.isNotEmpty()) {
            HomeRow(stringResource(R.string.home_groups), guideModel.bouquets.take(16)) { index, bouquet ->
                EnigmaChannelCard(
                    title = bouquet.name,
                    subtitle = "",
                    onClick = { onOpenBouquet(bouquet) },
                    modifier = cardFocus(index, focusGroups, 220.dp),
                )
            }
        }
        if (recordings.isNotEmpty()) {
            HomeRow(stringResource(R.string.home_recordings), recordings.take(12)) { index, item ->
                EnigmaChannelCard(
                    title = item.title,
                    subtitle = item.channelName,
                    onClick = { onOpenRecording(item) },
                    modifier = cardFocus(index, focusRecordings, 240.dp),
                )
            }
        }
        Text(
            powerModel.stateLabel.asString(),
            color = EnigmaColors.Subtle,
            fontSize = EnigmaType.Caption,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
    }
}

@Composable
private fun <T> HomeRow(
    title: String,
    items: List<T>,
    card: @Composable (index: Int, item: T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = EnigmaColors.Text, fontSize = EnigmaType.Body, fontWeight = FontWeight.SemiBold)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(132.dp),
            contentPadding = PaddingValues(end = 24.dp),
        ) {
            itemsIndexed(items, key = { index, item -> "$title:$index:${rowKey(item)}" }) { index, item ->
                card(index, item)
            }
        }
    }
}

private fun rowKey(item: Any?): String = when (item) {
    is LastChannel -> item.reference
    is BouquetEntry -> item.reference
    is Recording -> item.id
    is Pair<*, *> -> rowKey(item.first ?: item)
    else -> (item?.hashCode() ?: 0).toString()
}

private fun progressOf(event: EpgEvent, nowSec: Long): Float {
    if (event.durationSec <= 0L) return 0f
    return ((nowSec - event.beginEpochSec).toFloat() / event.durationSec).coerceIn(0f, 1f)
}
