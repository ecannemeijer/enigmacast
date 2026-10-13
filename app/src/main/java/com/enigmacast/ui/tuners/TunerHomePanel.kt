package com.enigmacast.ui.tuners

import com.enigmacast.ui.theme.focusRequesterIf
import com.enigmacast.ui.theme.holdPageFocus
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import com.enigmacast.ui.soccer.trapFocus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.tuner.ScheduleItem
import com.enigmacast.domain.tuner.ScheduleKind
import com.enigmacast.domain.tuner.StopOption
import com.enigmacast.domain.tuner.TunerCard
import com.enigmacast.domain.tuner.TunerUse
import com.enigmacast.domain.tuner.TunerUseKind
import com.enigmacast.domain.tuner.stopOption
import com.enigmacast.ui.home.PivotColumn
import com.enigmacast.ui.home.PivotState
import com.enigmacast.ui.soccer.PageSection
import com.enigmacast.ui.soccer.focusLook
import com.enigmacast.ui.soccer.rememberFocusChain
import com.enigmacast.ui.soccer.rememberTvFocus
import com.enigmacast.ui.soccer.tvClick
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.LocalEdgeLeft
import com.enigmacast.ui.theme.PanelHeader
import com.enigmacast.ui.theme.TvChoiceDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardShape = RoundedCornerShape(12.dp)

/**
 * Home: one card per tuner (who uses it, which channel, programme) and what is scheduled
 * today. OK on a tuner opens Watch along / Stop stream.
 */
@Composable
fun TunerHomePanel(
    contentFocus: FocusRequester,
    menuFocus: FocusRequester,
    onRetreatTo: (FocusRequester) -> Unit,
    onWatch: (BouquetEntry) -> Unit,
    model: TunerHomeViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { model.whileVisible() }
    val left = Modifier.focusProperties { left = menuFocus }
    var chosen by remember { mutableStateOf<TunerCard?>(null) }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmStandby by remember { mutableStateOf(false) }
    var openSeries by remember { mutableStateOf<com.enigmacast.domain.series.SeriesGroup?>(null) }
    var openItem by remember { mutableStateOf<ScheduleItem?>(null) }
    var confirmCancel by remember { mutableStateOf<ScheduleItem?>(null) }

    CompositionLocalProvider(LocalEdgeLeft provides menuFocus) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(0f to Color(0xFF0E1C2E), 0.6f to EnigmaColors.Canvas))
                .onFocusChanged { if (it.hasFocus) onRetreatTo(menuFocus) }
                .holdPageFocus(contentFocus, model.cards.map { it.index }),
        ) {
            val sections = remember { PivotState() }
            val chain = rememberFocusChain()
            // Explicit Up/Down to the neighbouring row on every card: the column is clipped and moved
            // by an offset, so a plain focus search may find nothing below and focus would stay put.
            fun rowNav(key: String) = Modifier.focusProperties {
                down = chain.neighbourKey(key, 1)?.let(chain::requester) ?: FocusRequester.Cancel
                up = chain.neighbourKey(key, -1)?.let(chain::requester) ?: FocusRequester.Cancel
            }
            val list = buildList {
                add(PageSection("tuners") { TunersRow(model, left, rowNav("tuners")) { chosen = it } })
                if (model.series.isNotEmpty()) add(PageSection("series") { SeriesRow(model, left, rowNav("series")) { openSeries = it } })
                add(PageSection("today") {
                    ScheduleRow(stringResource(R.string.home_schedule_title), model.schedule, model, left, rowNav("today"), emptyText = stringResource(R.string.home_schedule_empty)) { openItem = it }
                })
                if (model.upcoming.isNotEmpty()) add(PageSection("upcoming") {
                    ScheduleRow(stringResource(R.string.home_upcoming_title), model.upcoming, model, left, rowNav("upcoming"), withDay = true) { openItem = it }
                })
            }
            chain.keys = list.map { it.key }
            PivotColumn(sections, Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                PanelHeader(
                    stringResource(R.string.home_tuners_title),
                    when {
                        model.failed && !model.loading -> stringResource(R.string.home_unreachable)
                        model.inStandby -> stringResource(R.string.home_standby)
                        else -> stringResource(R.string.home_tuners_subtitle)
                    },
                )
                list.forEachIndexed { i, section ->
                    key(section.key) {
                        val entry = if (i == 0) Modifier.focusRequester(contentFocus) else Modifier
                        Box(sections.section(i).then(entry).then(chain.section(section.key))) { section.content() }
                    }
                }
            }
        }
    }

    chosen?.let { card ->
        val use = card.primary
        val canWatch = card.uses.any { it.reference.isNotBlank() }
        val choices = buildList {
            if (canWatch) add(stringResource(R.string.home_watch_along))
            if (!card.idle) add(stringResource(R.string.home_stop_stream))
            add(stringResource(R.string.cancel))
        }
        val watch = stringResource(R.string.home_watch_along)
        val stop = stringResource(R.string.home_stop_stream)
        val otherTitle = stringResource(R.string.home_stop_other_title)
        val other = card.uses.firstOrNull { it.kind == TunerUseKind.OTHER_STREAM }
        val otherMsg = stringResource(R.string.home_stop_other_message, other?.address?.ifBlank { null } ?: stringResource(R.string.home_unknown_client))
        val recMsg = stringResource(R.string.home_stop_rec_message)
        val stopped = stringResource(R.string.home_stopped_own)
        val lines = card.uses.map { useLine(it) }
        TvChoiceDialog(
            title = stringResource(R.string.home_tuner_name, card.letter) + (use?.let { " · " + it.channelName } ?: ""),
            message = lines.joinToString("\n").ifBlank { stringResource(R.string.home_tuner_idle) },
            choices = choices,
            onChoose = { i ->
                chosen = null
                when (choices[i]) {
                    watch -> card.uses.firstOrNull { it.reference.isNotBlank() }?.let { onWatch(BouquetEntry(it.channelName, it.reference)) }
                    stop -> when (card.stopOption()) {
                        StopOption.STOP_OWN -> { model.stopOwnStream(); info = stop to stopped }
                        StopOption.STANDBY_RECEIVER -> confirmStandby = true
                        StopOption.NOT_POSSIBLE_OTHER -> info = otherTitle to otherMsg
                        StopOption.NOT_POSSIBLE_RECORDING -> info = otherTitle to recMsg
                        StopOption.NOTHING -> Unit
                    }
                }
            },
            onDismiss = { chosen = null },
        )
    }
    if (confirmStandby) {
        TvChoiceDialog(
            title = stringResource(R.string.home_standby_title),
            message = stringResource(R.string.home_standby_message),
            choices = listOf(stringResource(R.string.home_standby_confirm), stringResource(R.string.cancel)),
            onChoose = { i -> confirmStandby = false; if (i == 0) model.standby() },
            onDismiss = { confirmStandby = false },
        )
    }
    openItem?.let { item ->
        val cancelRec = stringResource(R.string.home_cancel_recording)
        val cancelAuto = stringResource(R.string.home_cancel_autoswitch)
        val choices = buildList {
            if (item.recordingId != null || item.boxTimer != null) add(cancelRec)
            if (item.reminderKey != null) add(cancelAuto)
            add(stringResource(R.string.close))
        }
        TvChoiceDialog(
            title = item.title,
            message = scheduleLine(item, withDay = true),
            choices = choices,
            onChoose = { i -> openItem = null; if (choices[i] == cancelRec || choices[i] == cancelAuto) confirmCancel = item },
            onDismiss = { openItem = null },
        )
    }
    confirmCancel?.let { item ->
        val auto = item.kind == ScheduleKind.AUTOSWITCH
        TvChoiceDialog(
            title = stringResource(if (auto) R.string.home_cancel_autoswitch_title else R.string.home_cancel_recording_title, item.title),
            message = scheduleLine(item, withDay = true),
            choices = listOf(stringResource(if (auto) R.string.home_cancel_autoswitch else R.string.home_cancel_recording), stringResource(R.string.cancel)),
            onChoose = { i -> confirmCancel = null; if (i == 0) model.cancel(item) },
            onDismiss = { confirmCancel = null },
        )
    }
    openSeries?.let { group ->
        // Keep the dialog in step with refreshes (episodes cancelled meanwhile).
        val live = model.series.firstOrNull { it.key == group.key } ?: group
        SeriesEpisodesDialog(
            group = live,
            onCancelEpisode = { model.cancelEpisode(it) },
            onCancelAll = { model.cancelSeries(live); openSeries = null },
            onClose = { openSeries = null },
        )
    }
    info?.let { (title, message) ->
        TvChoiceDialog(title, message, listOf(stringResource(R.string.home_ok)), onChoose = { info = null }, onDismiss = { info = null })
    }
}

@Composable
private fun useLine(use: TunerUse): String = when (use.kind) {
    TunerUseKind.LIVE -> stringResource(R.string.home_use_live)
    TunerUseKind.RECORDING -> stringResource(R.string.home_use_recording)
    TunerUseKind.OWN_STREAM -> stringResource(R.string.home_use_own)
    TunerUseKind.OTHER_STREAM -> stringResource(R.string.home_use_other, use.address.ifBlank { "?" })
} + " · " + use.channelName

@Composable
private fun TunersRow(model: TunerHomeViewModel, left: Modifier, nav: Modifier, onOpen: (TunerCard) -> Unit) {
    // Centred across the page, all cards as tall as the tallest.
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).padding(vertical = 6.dp),
    ) {
        model.cards.forEachIndexed { i, card ->
            TunerCardView(card, model, (if (i == 0) left else Modifier).then(nav)) { onOpen(card) }
        }
        if (model.cards.isEmpty()) {
            // Keeps the section focusable (and the menu's Right working) before the first answer.
            val focus = rememberTvFocus()
            Box(left.then(nav).width(232.dp).height(96.dp).then(focusLook(focus.focused, CardShape)).tvClick(focus) { model.message = null }, contentAlignment = Alignment.Center) {
                Text(
                    if (model.loading) "…" else stringResource(R.string.home_unreachable),
                    color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TunerCardView(card: TunerCard, model: TunerHomeViewModel, modifier: Modifier, onClick: () -> Unit) {
    val focus = rememberTvFocus()
    val use = card.primary
    val channel = use?.reference?.let { model.channelInfo[it] }
    Column(
        modifier
            .width(232.dp)
            .fillMaxHeight()
            .heightIn(min = 96.dp)
            .then(focusLook(focus.focused, CardShape))
            .tvClick(focus, onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (card.idle) EnigmaColors.CardBorder else EnigmaColors.Cyan),
                contentAlignment = Alignment.Center,
            ) { Text(card.letter, color = if (card.idle) EnigmaColors.Text else Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(card.name.ifBlank { stringResource(R.string.home_tuner_name, card.letter) }, color = EnigmaColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(card.type, color = EnigmaColors.Subtle, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (use != null && use.reference.isNotBlank()) Picon(model, use)
        }
        if (use == null) {
            Text(stringResource(R.string.home_tuner_idle), color = EnigmaColors.Muted, fontSize = 12.sp)
        } else {
            Text(use.channelName, color = EnigmaColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Bouquet and programme share a line to keep the card short.
            val sub = listOfNotNull(channel?.bouquet?.takeIf { it.isNotBlank() }, channel?.now?.title).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, color = EnigmaColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            card.uses.forEach { u ->
                Text(useLine(u).substringBefore(" · "), color = if (u.kind == TunerUseKind.OWN_STREAM) EnigmaColors.Cyan else EnigmaColors.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Picon(model: TunerHomeViewModel, use: TunerUse, small: Boolean = false) {
    val bitmap by produceState<ImageBitmap?>(model.cachedPicon(use.reference, use.channelName), use.reference) {
        if (value == null) value = model.picon(use.reference, use.channelName)
    }
    bitmap?.let { Image(it, contentDescription = null, modifier = if (small) Modifier.width(26.dp).height(16.dp) else Modifier.width(44.dp).height(27.dp)) }
}

@Composable
private fun scheduleLine(item: ScheduleItem, withDay: Boolean): String {
    val fmt = remember(withDay) { SimpleDateFormat(if (withDay) "EEE d MMM  HH:mm" else "HH:mm", Locale.getDefault()) }
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val span = fmt.format(Date(item.beginEpochSec * 1000)) +
        if (item.endEpochSec > item.beginEpochSec) "–" + clock.format(Date(item.endEpochSec * 1000)) else ""
    val margins = if (item.marginBeforeMin > 0 || item.marginAfterMin > 0) "  " + stringResource(R.string.home_margins, item.marginBeforeMin, item.marginAfterMin) else ""
    return listOf(item.channelName, span + margins).filter { it.isNotBlank() }.joinToString(" · ")
}

/** A row of scheduled cards (recordings and AutoSwitches); OK opens Cancel / Close. */
@Composable
private fun ScheduleRow(
    title: String,
    items: List<ScheduleItem>,
    model: TunerHomeViewModel,
    left: Modifier,
    nav: Modifier,
    withDay: Boolean = false,
    emptyText: String = "",
    onOpen: (ScheduleItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = EnigmaColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (items.isEmpty()) {
            val focus = rememberTvFocus()
            Box(left.then(nav).width(320.dp).then(focusLook(focus.focused, CardShape)).tvClick(focus) {}.padding(12.dp)) {
                Text(emptyText, color = EnigmaColors.Muted, fontSize = 13.sp)
            }
            return@Column
        }
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp, horizontal = 2.dp)) {
            items(items.size, key = { items[it].id }) { i ->
                val item = items[i]
                ScheduleCard(item, model, withDay, (if (i == 0) left else Modifier).then(nav)) { onOpen(item) }
            }
        }
    }
}

@Composable
private fun ScheduleCard(item: ScheduleItem, model: TunerHomeViewModel, withDay: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val focus = rememberTvFocus()
    val fmt = remember(withDay) { SimpleDateFormat(if (withDay) "EEE HH:mm" else "HH:mm", Locale.getDefault()) }
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val poster = model.titlePosters[item.title]
    Row(
        modifier.width(256.dp).height(88.dp).then(focusLook(focus.focused, CardShape)).tvClick(focus, onClick).padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(49.dp).height(74.dp).clip(RoundedCornerShape(8.dp)).background(EnigmaColors.CardBorder), contentAlignment = Alignment.Center) {
            if (poster != null) {
                coil3.compose.AsyncImage(
                    model = com.enigmacast.data.tmdb.TmdbJson.image(poster, "w185"),
                    contentDescription = item.title,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                com.enigmacast.ui.components.DefaultArtwork(item.title, Modifier.fillMaxSize())
            }
            if (item.running) {
                Text(
                    stringResource(R.string.home_now_running), color = Color.White, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(3.dp).background(Color(0xFFE53935), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(item.title, color = EnigmaColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.reference.isNotBlank()) Picon(model, TunerUse(TunerUseKind.LIVE, item.channelName, item.reference), small = true)
                Text(item.channelName, color = EnigmaColors.Subtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val span = fmt.format(Date(item.beginEpochSec * 1000)) +
                if (item.endEpochSec > item.beginEpochSec) "–" + clock.format(Date(item.endEpochSec * 1000)) else ""
            Text(span, color = EnigmaColors.Cyan, fontSize = 11.sp)
            Text(
                stringResource(
                    when (item.kind) {
                        ScheduleKind.BOX_TIMER, ScheduleKind.BOX_RECORDING -> R.string.home_kind_box_rec
                        ScheduleKind.TV_RECORDING -> R.string.home_kind_tv_rec
                        ScheduleKind.AUTOSWITCH -> R.string.home_kind_autoswitch
                    },
                ),
                color = if (item.kind == ScheduleKind.AUTOSWITCH) EnigmaColors.Accent else Color(0xFFFF7A7A),
                fontSize = 11.sp,
                modifier = Modifier.border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp),
            )
        }
    }
}

@Composable
private fun SeriesRow(model: TunerHomeViewModel, left: Modifier, nav: Modifier, onOpen: (com.enigmacast.domain.series.SeriesGroup) -> Unit) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.home_series_title), color = EnigmaColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp, horizontal = 2.dp)) {
            items(model.series.size, key = { model.series[it].key }) { i ->
                val group = model.series[i]
                SeriesCard(group, model.posters[group.key], (if (i == 0) left else Modifier).then(nav)) { onOpen(group) }
            }
        }
    }
}

@Composable
private fun SeriesCard(group: com.enigmacast.domain.series.SeriesGroup, poster: String?, modifier: Modifier, onClick: () -> Unit) {
    val focus = rememberTvFocus()
    val time = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
    Row(
        modifier.width(256.dp).height(88.dp).then(focusLook(focus.focused, CardShape)).tvClick(focus, onClick).padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(49.dp).height(74.dp).clip(RoundedCornerShape(8.dp)).background(EnigmaColors.CardBorder), contentAlignment = Alignment.Center) {
            if (poster != null) {
                coil3.compose.AsyncImage(
                    model = com.enigmacast.data.tmdb.TmdbJson.image(poster, "w185"),
                    contentDescription = group.title,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                com.enigmacast.ui.components.DefaultArtwork(group.title, Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(group.title, color = EnigmaColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(group.channelName, color = EnigmaColors.Subtle, fontSize = 11.sp, maxLines = 1)
            if (group.episodes.isEmpty()) {
                Text(stringResource(R.string.series_rule_only), color = EnigmaColors.Muted, fontSize = 11.sp, maxLines = 2)
            } else {
                Text(androidx.compose.ui.res.pluralStringResource(R.plurals.home_series_count, group.episodes.size, group.episodes.size), color = EnigmaColors.Cyan, fontSize = 11.sp)
                group.next?.let { Text(stringResource(R.string.home_series_next, time.format(Date(it.beginEpochSec * 1000))), color = EnigmaColors.Muted, fontSize = 11.sp) }
            }
        }
    }
}

/** Episodes of a series card: OK on an episode cancels it (asks first); Cancel all asks, then removes everything. */
@Composable
private fun SeriesEpisodesDialog(
    group: com.enigmacast.domain.series.SeriesGroup,
    onCancelEpisode: (com.enigmacast.domain.recording.Recording) -> Unit,
    onCancelAll: () -> Unit,
    onClose: () -> Unit,
) {
    var askEpisode by remember { mutableStateOf<com.enigmacast.domain.recording.Recording?>(null) }
    var askAll by remember { mutableStateOf(false) }
    val time = remember { SimpleDateFormat("EEE d MMM  HH:mm", Locale.getDefault()) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        com.enigmacast.ui.theme.NavigationSoundsForWindow()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        Column(
            Modifier.width(560.dp).background(EnigmaColors.Selected, RoundedCornerShape(14.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(14.dp)).padding(16.dp)
                .trapFocus(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.home_series_dialog, group.title, group.channelName), color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (group.episodes.isEmpty()) Text(stringResource(R.string.series_rule_only), color = EnigmaColors.Muted, fontSize = 13.sp)
                group.episodes.forEachIndexed { i, ep ->
                    val focus = rememberTvFocus()
                    Row(
                        Modifier.fillMaxWidth().focusRequesterIf(i == 0, first)
                            .then(focusLook(focus.focused, CardShape)).tvClick(focus) { askEpisode = ep }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(time.format(Date(ep.beginEpochSec * 1000)), color = EnigmaColors.Cyan, fontSize = 13.sp, modifier = Modifier.width(150.dp))
                        Text(ep.title, color = EnigmaColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(if (ep.place == com.enigmacast.domain.recording.RecordingPlace.TV) R.string.myitems_on_tv else R.string.myitems_on_box),
                            color = EnigmaColors.Subtle, fontSize = 11.sp,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.enigmacast.ui.components.EnigmaGlassButton(
                    stringResource(R.string.series_cancel_all), { askAll = true },
                    if (group.episodes.isEmpty()) Modifier.focusRequester(first) else Modifier,
                )
                com.enigmacast.ui.components.EnigmaGlassButton(stringResource(R.string.close), onClose)
            }
        }
    }
    askEpisode?.let { ep ->
        TvChoiceDialog(
            title = stringResource(R.string.series_cancel_episode),
            message = ep.title + " · " + time.format(Date(ep.beginEpochSec * 1000)),
            choices = listOf(stringResource(R.string.series_cancel_episode), stringResource(R.string.cancel)),
            onChoose = { i -> askEpisode = null; if (i == 0) onCancelEpisode(ep) },
            onDismiss = { askEpisode = null },
        )
    }
    if (askAll) {
        TvChoiceDialog(
            title = stringResource(R.string.series_cancel_all_title, group.title),
            message = stringResource(R.string.series_cancel_all_message),
            choices = listOf(stringResource(R.string.series_cancel_all), stringResource(R.string.cancel)),
            onChoose = { i -> askAll = false; if (i == 0) onCancelAll() },
            onDismiss = { askAll = false },
        )
    }
}
