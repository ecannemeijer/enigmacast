package com.enigmacast.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.epg.GuideCell
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.epg.GuideTimeline
import com.enigmacast.domain.epg.RecordPrompt
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.live.GuideGridState
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.TvConfirmDialog
import com.enigmacast.ui.theme.requestWhenReady
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the channel list's programme panel can ask the view model to do. */
data class GuideActions(
    val onOpenChannelEpg: (BouquetEntry) -> Unit,
    val onChooseProgramme: (BouquetEntry, GuideEvent) -> Unit,
    val onConfirmRecording: () -> Unit,
    val onDismissRecording: () -> Unit,
)

private val ChannelColumn = 200.dp
private val RowHeight = 38.dp
private val CellShape = RoundedCornerShape(4.dp)
private val Airing = Color(0xFF262626)
private val Empty = Color(0xFF121212)

/** The current time in seconds, refreshed every 30 s so now-lines and progress move. */
@Composable
fun rememberGuideClock(): Long {
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }
    return nowSec
}

/**
 * The programme grid (dashboard, Stream): channels as rows on the left, programmes on a
 * two-hour time axis with a now-line. The view model moves the highlight; this only draws,
 * so no cell needs focus and only the rows on screen are composed. [active] is false while
 * the grid does not have the focus (the highlight is then drawn dimmed).
 */
@Composable
fun GuideTimelineGrid(
    state: GuideGridState,
    playingReference: String,
    active: Boolean,
    nowSec: Long,
    numberOf: (BouquetEntry) -> Int,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val cursor = state.cursor
    val focusSec = maxOf(cursor.focusSec, nowSec)
    BoxWithConstraints(modifier = modifier.background(EnigmaColors.Canvas)) {
        val timeline = maxWidth - ChannelColumn
        val dpPerSec = timeline.value / GuideTimeline.WINDOW_SEC
        Column(Modifier.fillMaxSize()) {
            TimeRuler(cursor.windowStartSec, nowSec, dpPerSec)
            if (state.channels.isEmpty()) {
                Text(
                    stringResource(R.string.osd_no_channels_loaded),
                    color = EnigmaColors.Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
            GuideRows(
                state = state,
                playingReference = playingReference,
                active = active,
                focusSec = focusSec,
                nowSec = nowSec,
                dpPerSec = dpPerSec,
                numberOf = numberOf,
                picon = picon,
                cachedPicon = cachedPicon,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.epg_guide_hint),
                color = EnigmaColors.Subtle,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            )
        }
        val nowOffset = (nowSec - cursor.windowStartSec) * dpPerSec
        if (nowOffset >= 0 && nowOffset <= timeline.value) {
            Box(
                Modifier
                    .offset(x = ChannelColumn + nowOffset.dp)
                    .padding(bottom = 20.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(EnigmaColors.Accent.copy(alpha = 0.85f)),
            )
        }
    }
}

/** Details of the highlighted programme: channel, title, time, progress, description and what OK does. */
@Composable
fun GuideDetails(
    title: String,
    channel: BouquetEntry?,
    number: Int,
    cell: GuideCell?,
    nowSec: Long,
    recorded: Boolean,
    loading: Boolean,
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    GuideHeader(title, channel, number, cell, nowSec, recorded, loading, failed, modifier)
}

@Composable
private fun GuideHeader(
    title: String,
    channel: BouquetEntry?,
    number: Int,
    cell: GuideCell?,
    nowSec: Long,
    recorded: Boolean,
    loading: Boolean,
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    val event = cell?.event
    Column(
        modifier = modifier
            .background(EnigmaColors.Canvas)
            .padding(start = 16.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = EnigmaColors.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            if (channel != null) {
                Text(
                    (if (number > 0) "   $number  " else "   ") + channel.name,
                    color = EnigmaColors.Muted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            when {
                failed -> Text(stringResource(R.string.epg_load_failed), color = EnigmaColors.Danger, fontSize = 12.sp)
                loading -> Text(stringResource(R.string.epg_loading), color = EnigmaColors.Subtle, fontSize = 12.sp)
            }
        }
        Text(
            event?.title ?: stringResource(R.string.osd_no_programme_data),
            color = if (event != null) Color.White else EnigmaColors.Muted,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (event != null) {
            val airing = event.isAiring(nowSec)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeRange(event, nowSec), color = EnigmaColors.Accent, fontSize = 14.sp)
                if (airing) {
                    val left = ((event.endEpochSec - nowSec + 59) / 60).toInt()
                    Text("  ·  " + stringResource(R.string.epg_minutes_left, left), color = EnigmaColors.Muted, fontSize = 14.sp)
                }
                if (recorded) {
                    Text("  ●  " + stringResource(R.string.epg_recording), color = EnigmaColors.Danger, fontSize = 14.sp)
                }
            }
            if (airing) GuideProgress(event.progress(nowSec), Modifier.width(280.dp))
            Text(
                event.description,
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                stringResource(if (event.beginEpochSec <= nowSec) R.string.epg_hint_watch else R.string.epg_hint_record),
                color = EnigmaColors.Subtle,
                fontSize = 11.sp,
            )
        } else if (cell != null && cell.startSec <= nowSec) {
            Text(stringResource(R.string.epg_hint_watch), color = EnigmaColors.Subtle, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TimeRuler(windowStartSec: Long, nowSec: Long, dpPerSec: Float) {
    Row(modifier = Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            dayLabel(windowStartSec, nowSec),
            color = EnigmaColors.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.width(ChannelColumn).padding(start = 16.dp),
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val slots = (GuideTimeline.WINDOW_SEC / GuideTimeline.SLOT_SEC).toInt()
            for (index in 0 until slots) {
                val sec = windowStartSec + index * GuideTimeline.SLOT_SEC
                Text(
                    clock(sec),
                    color = EnigmaColors.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = ((sec - windowStartSec) * dpPerSec).dp + 4.dp),
                )
            }
        }
    }
}

@Composable
private fun GuideRows(
    state: GuideGridState,
    playingReference: String,
    active: Boolean,
    focusSec: Long,
    nowSec: Long,
    dpPerSec: Float,
    numberOf: (BouquetEntry) -> Int,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val channels = state.channels
    val row = state.cursor.row
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (row - 2).coerceAtLeast(0))
    // Keep the highlighted row on screen without jumping when it already is.
    LaunchedEffect(row, channels.size) {
        if (channels.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) {
            listState.scrollToItem((row - 2).coerceAtLeast(0))
            return@LaunchedEffect
        }
        val first = visible.first().index
        val lastFull = visible.lastOrNull { it.offset + it.size <= info.viewportEndOffset }?.index ?: visible.last().index
        when {
            row < first -> listState.scrollToItem(row)
            row > lastFull -> listState.scrollToItem((row - (lastFull - first)).coerceAtLeast(0))
        }
    }
    val playingKey = NowEvents.key(playingReference)
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        itemsIndexed(channels, key = { index, entry -> "$index:${entry.reference}" }) { index, entry ->
            GuideRow(
                entry = entry,
                number = numberOf(entry),
                events = state.eventsOf(entry),
                windowStartSec = state.cursor.windowStartSec,
                focusSec = if (index == row) focusSec else null,
                active = active,
                playing = playingReference.isNotBlank() && NowEvents.key(entry.reference) == playingKey,
                nowSec = nowSec,
                recordedKeys = state.recordedKeys,
                dpPerSec = dpPerSec,
                picon = picon,
                cachedPicon = cachedPicon,
            )
        }
    }
}

@Composable
private fun GuideRow(
    entry: BouquetEntry,
    number: Int,
    events: List<GuideEvent>,
    windowStartSec: Long,
    focusSec: Long?,
    active: Boolean,
    playing: Boolean,
    nowSec: Long,
    recordedKeys: Set<String>,
    dpPerSec: Float,
    picon: suspend (BouquetEntry) -> ImageBitmap?,
    cachedPicon: (BouquetEntry) -> ImageBitmap?,
) {
    val cells = remember(events, windowStartSec) {
        GuideTimeline.cells(events, windowStartSec, windowStartSec + GuideTimeline.WINDOW_SEC)
    }
    Row(modifier = Modifier.fillMaxWidth().height(RowHeight).padding(vertical = 1.dp)) {
        Row(
            modifier = Modifier
                .width(ChannelColumn)
                .fillMaxHeight()
                .padding(end = 2.dp)
                .background(if (focusSec != null && active) EnigmaColors.Raised else Color.Transparent, CellShape)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (number > 0) "$number" else "",
                color = if (playing) EnigmaColors.Accent else EnigmaColors.Muted,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier.width(32.dp),
            )
            Picon(entry, picon, cachedPicon, Modifier.width(42.dp).height(26.dp), fallbackSize = 8.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                entry.name,
                color = if (playing) EnigmaColors.Accent else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            cells.forEach { cell ->
                val focused = focusSec != null && cell.contains(focusSec)
                GuideCellBox(
                    cell = cell,
                    focused = focused && active,
                    marked = focused && !active,
                    airing = cell.startSec <= nowSec && nowSec < cell.endSec,
                    recorded = cell.event?.recordKey?.let { it in recordedKeys } == true,
                    x = ((cell.startSec - windowStartSec) * dpPerSec).dp,
                    width = ((cell.endSec - cell.startSec) * dpPerSec).dp,
                )
            }
        }
    }
}

@Composable
private fun GuideCellBox(cell: GuideCell, focused: Boolean, marked: Boolean, airing: Boolean, recorded: Boolean, x: Dp, width: Dp) {
    val event = cell.event
    val background = when {
        focused -> EnigmaColors.Focus
        marked -> EnigmaColors.Raised
        event == null -> Empty
        airing -> Airing
        else -> EnigmaColors.Card
    }
    Row(
        modifier = Modifier
            .offset(x = x)
            .width(width)
            .fillMaxHeight()
            .padding(end = 2.dp)
            .background(background, CellShape)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (recorded) Text("● ", color = EnigmaColors.Danger, fontSize = 11.sp)
        Text(
            event?.title.orEmpty(),
            color = when {
                focused -> EnigmaColors.OnFocus
                airing -> Color.White
                else -> EnigmaColors.Muted
            },
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GuideProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier = modifier.height(3.dp).background(EnigmaColors.Raised, RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(EnigmaColors.Accent, RoundedCornerShape(2.dp)))
    }
}

/**
 * Right of the channel list (Right on a channel): that channel's coming programmes with the
 * description of the highlighted one. OK watches the channel or offers to record.
 */
@Composable
fun ChannelEpgPanel(
    ui: RemoteDisplayUiState,
    actions: GuideActions,
) {
    val channel = ui.epgChannel ?: return
    val events = ui.epgEvents
    val focus = remember { FocusRequester() }
    var focusedIndex by remember(channel.reference) { mutableIntStateOf(0) }
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }
    LaunchedEffect(channel.reference, ui.epgLoading, events.isEmpty()) {
        focus.requestWhenReady()
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(480.dp)
            .padding(horizontal = 12.dp, vertical = 20.dp),
    ) {
        Text(channel.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.epg_panel_hint), color = EnigmaColors.Subtle, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (events.isEmpty()) {
            CompactListRow(onClick = {}, modifier = Modifier.focusRequester(focus)) {
                Text(
                    stringResource(if (ui.epgLoading) R.string.epg_loading else R.string.osd_no_programme_data),
                    fontSize = 13.sp,
                )
            }
            return@Column
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            itemsIndexed(events, key = { _, event -> "${event.eventId}:${event.beginEpochSec}" }) { index, event ->
                val airing = event.isAiring(nowSec)
                CompactListRow(
                    onClick = { actions.onChooseProgramme(channel, event) },
                    selected = airing,
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(focus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedIndex = index },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) { focused ->
                    val secondary = if (focused) EnigmaColors.OnFocus.copy(alpha = 0.8f) else EnigmaColors.Muted
                    Text(clock(event.beginEpochSec), color = secondary, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (event.recordKey in ui.recordedKeys) Text("● ", color = EnigmaColors.Danger, fontSize = 11.sp)
                            Text(
                                event.title,
                                fontSize = 13.sp,
                                fontWeight = if (airing) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.quick_minutes, (event.durationSec / 60).toInt()),
                                color = secondary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        if (airing) GuideProgress(event.progress(nowSec), Modifier.fillMaxWidth().padding(top = 3.dp))
                    }
                }
            }
        }
        val selected = events.getOrNull(focusedIndex) ?: events.first()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(top = 10.dp)
                .background(EnigmaColors.Card, RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(selected.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(timeRange(selected, nowSec), color = EnigmaColors.Accent, fontSize = 12.sp)
            Text(
                selected.description,
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Record this programme?" with what, when and where. */
@Composable
fun RecordDialog(prompt: RecordPrompt, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val nowSec = System.currentTimeMillis() / 1000
    TvConfirmDialog(
        title = stringResource(R.string.epg_record_title),
        message = stringResource(R.string.epg_record_message, prompt.event.title, timeRange(prompt.event, nowSec), prompt.channelName),
        confirmLabel = stringResource(R.string.epg_record),
        dismissLabel = stringResource(R.string.cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        busy = busy,
    )
}

/** "Today 20:30 – 21:15 · 45 min". */
@Composable
private fun timeRange(event: GuideEvent, nowSec: Long): String {
    val range = stringResource(
        R.string.epg_time_range,
        clock(event.beginEpochSec),
        clock(event.endEpochSec),
        (event.durationSec / 60).toInt(),
    )
    return dayLabel(event.beginEpochSec, nowSec) + "  " + range
}

@Composable
private fun dayLabel(epochSec: Long, nowSec: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate()
    val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
    return when (day) {
        today -> stringResource(R.string.epg_today)
        today.plusDays(1) -> stringResource(R.string.epg_tomorrow)
        else -> dayFormat(day, LocalConfiguration.current.locales[0] ?: Locale.getDefault())
    }
}

private fun dayFormat(day: LocalDate, locale: Locale): String =
    day.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))

private val GuideClock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun clock(epochSec: Long): String = Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(GuideClock)
