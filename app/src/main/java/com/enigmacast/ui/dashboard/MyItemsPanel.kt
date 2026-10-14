package com.enigmacast.ui.dashboard

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.enigmacast.ui.theme.skinShape
import com.enigmacast.ui.theme.focusRequesterIf
import com.enigmacast.ui.theme.holdPageFocus
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import com.enigmacast.ui.soccer.tvClick
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.recording.RecordingPlace
import com.enigmacast.domain.recording.RecordingStatus
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.LocalEdgeLeft
import com.enigmacast.ui.theme.PanelHeader
import com.enigmacast.ui.theme.TvChoiceDialog
import com.enigmacast.ui.theme.TvConfirmDialog
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.util.asString
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun MyItemsPanel(
    listFocus: FocusRequester,
    menuFocus: FocusRequester,
    rowsFocusable: Boolean,
    onPlay: () -> Unit,
    onRetreatTo: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
    onXtream: () -> Unit = {},
    viewModel: MyItemsViewModel = hiltViewModel(),
) {
    val book by viewModel.items.collectAsStateWithLifecycle()
    val boxName by viewModel.receiverName.collectAsStateWithLifecycle()
    // The section follows where a recording is stored, not which device scheduled it.
    val sections = com.enigmacast.domain.recording.MyItemsSections.split(book, viewModel.boxMovies, viewModel.boxTimers)
    val items = sections.device
    val listState = rememberLazyListState()
    val rowStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val posterMap by viewModel.posters.collectAsStateWithLifecycle()
    val resumeMap by viewModel.resumePoints.collectAsStateWithLifecycle()
    LaunchedEffect(book, viewModel.boxMovies) { viewModel.loadArtwork(book + viewModel.boxMovies) }
    LaunchedEffect(Unit) {
        viewModel.reloadNow()
        while (true) {
            viewModel.refresh()
            delay(20_000)
        }
    }
    val iptvReturnFocus = remember { FocusRequester() }
    val iptvRowCount = if (viewModel.iptvAllowed()) listOf(viewModel.iptvContinue.value, viewModel.iptvFavorites.value).count { it.isNotEmpty() } else 0
    CompositionLocalProvider(LocalEdgeLeft provides menuFocus) {
    Column(
        modifier
            .fillMaxSize()
            .onFocusChanged { if (it.hasFocus) onRetreatTo(menuFocus) }
            // The empty/loading line is replaced by the list: keep the focus on the page (first item).
            .holdPageFocus(listFocus, sections.all.firstOrNull()?.id),
    ) {
        PanelHeader(stringResource(R.string.nav_my_items))
        val notice = viewModel.notice.asString()
        if (notice.isNotBlank()) {
            Text(notice, color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        // IPTV: Continue watching and favorites, above the recordings (Premium IPTV only).
        val iptvContinue by viewModel.iptvContinue.collectAsStateWithLifecycle()
        val iptvFavorites by viewModel.iptvFavorites.collectAsStateWithLifecycle()
        val iptvBlocks = if (viewModel.iptvAllowed()) {
            listOf(R.string.library_continue to iptvContinue, R.string.library_iptv_favorites to iptvFavorites).filter { it.second.isNotEmpty() }
        } else emptyList()
        // One IPTV row (header + sideways cards); inside the LazyColumn when recordings exist so everything scrolls.
        val iptvRow: @Composable (Int, List<com.enigmacast.domain.xtream.IptvLibraryCard>) -> Unit = { header, cards ->
            Column {
                Text(stringResource(header), color = EnigmaColors.Cyan, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 4.dp))
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(cards, key = { _, c -> c.key }) { i, card ->
                        IptvCard(
                            card = card,
                            modifier = Modifier
                                .then(if (viewModel.returnKey == "$header/${card.key}") Modifier.focusRequester(iptvReturnFocus) else Modifier)
                                .focusProperties {
                                    canFocus = rowsFocusable
                                    if (i == 0) left = menuFocus
                                    if (i == cards.lastIndex) right = FocusRequester.Cancel
                                },
                            onClick = { viewModel.openIptv(card, onXtream, "$header/${card.key}") },
                            onOptions = { viewModel.optionsIptv(card) },
                        )
                    }
                }
            }
        }
        val boxMovies = sections.onReceiver
        val boxTimers = sections.scheduledOnReceiver
        val all = items + boxMovies + boxTimers
        if (all.isEmpty()) {
            iptvBlocks.forEach { (h, c) -> iptvRow(h, c) }
            Text(
                stringResource(R.string.my_items_empty),
                color = EnigmaColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier
                    .focusRequester(listFocus)
                    .focusProperties {
                        canFocus = rowsFocusable
                        left = menuFocus
                    }
                    .focusable()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        } else {
            val focusOn = viewModel.focusId?.takeIf { id -> all.any { it.id == id } } ?: all.first().id
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                iptvBlocks.forEach { (h, c) -> item(key = "iptv-$h") { iptvRow(h, c) } }
                // Sections: this app's recordings, recordings on the box, timers on the box. Headers are not focusable.
                val box = boxName.ifBlank { null }
                listOf(
                    Triple("app", if (boxMovies.isEmpty() && boxTimers.isEmpty()) null else R.string.my_items_section_app, items),
                    Triple("movies", if (box != null) R.string.my_items_section_box_named else R.string.my_items_section_box, boxMovies),
                    Triple("timers", if (box != null) R.string.my_items_section_box_timers_named else R.string.my_items_section_box_timers, boxTimers),
                ).forEach { (key, header, list) ->
                    if (list.isEmpty()) return@forEach
                    if (header != null) item(key = "h-$key") {
                        Text(stringResource(header, box.orEmpty()), color = EnigmaColors.Cyan, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp))
                    }
                    val posters = list.filter(viewModel::isPoster)
                    val rows = list.filterNot(viewModel::isPoster)
                    if (posters.isNotEmpty()) item(key = "p-$key") {
                        val rowState = rowStates.getOrPut(key) { androidx.compose.foundation.lazy.LazyListState() }
                        androidx.compose.foundation.lazy.LazyRow(
                            state = rowState,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            itemsIndexed(posters, key = { _, item -> item.id }) { i, item ->
                                PosterCard(
                                    item = item,
                                    movie = posterMap[item.id],
                                    progress = com.enigmacast.domain.resume.ResumePolicy.fraction(resumeMap[item.id], (item.endEpochSec - item.beginEpochSec) * 1000),
                                    modifier = Modifier
                                        .focusRequesterIf(item.id == focusOn, listFocus)
                                        .focusProperties {
                                            canFocus = rowsFocusable
                                            if (i == 0) left = menuFocus
                                        },
                                    onClick = { viewModel.open(item) },
                                )
                            }
                        }
                    }
                    itemsIndexed(rows, key = { _, item -> item.id }) { _, item ->
                        RecordingRow(
                            item = item,
                            modifier = Modifier
                                .focusRequesterIf(item.id == focusOn, listFocus)
                                .focusProperties {
                                    canFocus = rowsFocusable
                                    left = menuFocus
                                },
                            onClick = { viewModel.open(item) },
                        )
                    }
                }
            }
        }
    }
    }
    // Back from an IPTV film started here: focus returns to its card.
    LaunchedEffect(viewModel.returnKey) {
        if (viewModel.returnKey == null) return@LaunchedEffect
        val row = if (viewModel.returnKey!!.startsWith("${R.string.library_continue}/")) 0 else iptvRowCount - 1
        if (row >= 0 && (items + sections.onReceiver + sections.scheduledOnReceiver).isNotEmpty()) listState.scrollToItem(row)
        iptvReturnFocus.requestWhenReady()
        viewModel.consumeReturn()
    }
    val focusTokenOnEnter = remember { viewModel.focusToken }
    LaunchedEffect(viewModel.focusToken) {
        if (viewModel.focusToken == 0 || viewModel.focusToken == focusTokenOnEnter) return@LaunchedEffect
        if (viewModel.selected != null || viewModel.pendingDelete != null) return@LaunchedEffect
        val index = listIndexOf(viewModel.focusId, items, sections.onReceiver, sections.scheduledOnReceiver, viewModel::isPoster)
        if (index >= 0) listState.scrollToItem(index + iptvRowCount)
        // A poster sits inside a sideways row: bring it into view there too.
        for ((key, list) in listOf("app" to items, "movies" to sections.onReceiver)) {
            val i = list.filter(viewModel::isPoster).indexOfFirst { it.id == viewModel.focusId }
            if (i >= 0) rowStates[key]?.scrollToItem(i)
        }
        listFocus.requestWhenReady()
        if (viewModel.holdList) {
            repeat(2) { withFrameNanos { } }
            viewModel.releaseList()
        }
    }
    viewModel.selected?.takeIf { viewModel.isPoster(it) }?.let { item ->
        val point = resumeMap[item.id]
        val offer = com.enigmacast.domain.resume.ResumePolicy.offer(point)
        com.enigmacast.ui.home.RecordingInfoDialog(
            film = recordingFilm(item, posterMap[item.id]),
            detail = rowDetail(item),
            progress = com.enigmacast.domain.resume.ResumePolicy.fraction(point, (item.endEpochSec - item.beginEpochSec) * 1000),
            watchLabel = if (offer != null) stringResource(R.string.resume_from, com.enigmacast.domain.resume.ResumePolicy.clock(offer)) else stringResource(R.string.myitems_watch),
            onWatch = { viewModel.play(onPlay) },
            onDelete = viewModel::askDelete,
            onClose = viewModel::dismiss,
        )
    }
    viewModel.resumeAsk?.let { (item, position) ->
        TvChoiceDialog(
            title = stringResource(R.string.resume_title),
            message = item.title,
            choices = listOf(
                stringResource(R.string.resume_from, com.enigmacast.domain.resume.ResumePolicy.clock(position)),
                stringResource(R.string.resume_start_over),
            ),
            onChoose = { i -> viewModel.answerResume(fromStart = i == 1) },
            onDismiss = viewModel::dismissResume,
            compact = true,
        )
    }
    viewModel.selected?.takeIf { !viewModel.isPoster(it) }?.let { item ->
        val actions = actionsFor(item, viewModel.isBox(item), viewModel.isBoxTimer(item))
        TvChoiceDialog(
            title = item.title,
            message = rowDetail(item),
            choices = actions.map { stringResource(it.label) },
            onChoose = { index ->
                when (actions[index]) {
                    ItemAction.PLAY -> viewModel.play(onPlay)
                    ItemAction.STOP -> viewModel.stop()
                    ItemAction.DELETE -> viewModel.askDelete()
                    ItemAction.CANCEL -> viewModel.dismiss()
                }
            },
            onDismiss = viewModel::dismiss,
            compact = true,
        )
    }
    viewModel.iptvOptions?.let { card ->
        TvChoiceDialog(
            title = card.title,
            message = card.subtitle,
            choices = listOf(
                stringResource(if (card.key.startsWith("fav/")) R.string.iptv_library_remove else R.string.library_remove_continue),
                stringResource(R.string.cancel),
            ),
            onChoose = { i -> if (i == 0) viewModel.askRemoveIptv() else viewModel.dismissIptvOptions() },
            onDismiss = viewModel::dismissIptvOptions,
            compact = true,
        )
    }
    viewModel.iptvPendingRemove?.let { card ->
        TvConfirmDialog(
            title = stringResource(R.string.library_remove_title),
            message = stringResource(R.string.library_remove_message, card.title),
            confirmLabel = stringResource(R.string.library_remove),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = viewModel::confirmRemoveIptv,
            onDismiss = viewModel::dismissRemoveIptv,
            destructive = true,
            compact = true,
        )
    }
    viewModel.pendingDelete?.let { item ->
        TvConfirmDialog(
            title = stringResource(R.string.my_items_delete_title),
            message = stringResource(R.string.my_items_delete_message, item.title),
            confirmLabel = stringResource(R.string.my_items_delete),
            dismissLabel = stringResource(R.string.cancel),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
            destructive = true,
            compact = true,
        )
    }
}

/** Position of [id] in the LazyColumn, counting the section headers drawn when box items exist. */
internal fun listIndexOf(
    id: String?,
    app: List<Recording>,
    movies: List<Recording>,
    timers: List<Recording>,
    poster: (Recording) -> Boolean = { false },
): Int {
    if (id == null) return -1
    val headers = movies.isNotEmpty() || timers.isNotEmpty()
    var offset = 0
    for (list in listOf(app, movies, timers)) {
        if (list.isEmpty()) continue
        if (headers) offset++
        // Posters of a section share one LazyColumn item (their sideways row), before the plain rows.
        val posters = list.filter(poster)
        val rows = list.filterNot(poster)
        if (posters.any { it.id == id }) return offset
        if (posters.isNotEmpty()) offset++
        val i = rows.indexOfFirst { it.id == id }
        if (i >= 0) return offset + i
        offset += rows.size
    }
    return -1
}

/** Small poster card: TMDB poster (or placeholder), watched bar, title and date. */
@Composable
private fun PosterCard(
    item: Recording,
    movie: com.enigmacast.domain.films.TmdbMovie?,
    progress: Float,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val focus = com.enigmacast.ui.soccer.rememberTvFocus()
    val film = recordingFilm(item, movie)
    Column(
        modifier
            .width(100.dp)
            .tvClick(focus, onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier
                .width(100.dp)
                .height(150.dp)
                .border(if (focus.focused) 3.dp else 0.dp, if (focus.focused) EnigmaColors.Cyan else androidx.compose.ui.graphics.Color.Transparent, skinShape(12.dp)),
        ) {
            com.enigmacast.ui.home.PosterBox(film, Modifier.fillMaxSize())
            if (item.status == RecordingStatus.RECORDING) {
                Text(
                    stringResource(R.string.my_items_recording),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(EnigmaColors.Danger, skinShape(4.dp)).padding(horizontal = 4.dp),
                )
            }
            if (progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(androidx.compose.ui.graphics.Color(0x66000000))) {
                    Box(Modifier.fillMaxWidth(progress).height(4.dp).background(EnigmaColors.Cyan))
                }
            }
        }
        Text(item.title, color = if (focus.focused) EnigmaColors.Text else EnigmaColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            Instant.ofEpochSecond(item.beginEpochSec).atZone(ZoneId.systemDefault()).format(DayFormat),
            color = EnigmaColors.Subtle, fontSize = 10.sp, maxLines = 1,
        )
    }
}

/** A recording dressed as a [HomeFilm] for the poster and the info page (placeholder movie without a match). */
internal fun recordingFilm(item: Recording, movie: com.enigmacast.domain.films.TmdbMovie?): com.enigmacast.domain.films.HomeFilm {
    val candidate = com.enigmacast.live.RecordingArtwork.candidateOf(item)
        ?: com.enigmacast.domain.films.FilmCandidate(
            com.enigmacast.domain.epg.GuideEvent(item.eventId, item.reference, item.title, "", "", item.beginEpochSec, (item.endEpochSec - item.beginEpochSec).coerceAtLeast(0)),
            item.channelName, item.title, null,
        )
    return com.enigmacast.domain.films.HomeFilm(candidate, movie ?: com.enigmacast.domain.films.TmdbMovie(id = 0, title = item.title))
}

@Composable
private fun RecordingRow(item: Recording, modifier: Modifier, onClick: () -> Unit) {
    CompactListRow(onClick = onClick, modifier = modifier) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(item.title, color = EnigmaColors.Text, fontSize = 14.sp, maxLines = 1)
            Text(rowDetail(item), color = EnigmaColors.Muted, fontSize = 12.sp, maxLines = 1)
        }
        Text(
            statusLabel(item.status),
            color = when (item.status) {
                RecordingStatus.RECORDING -> EnigmaColors.Accent
                RecordingStatus.FAILED -> EnigmaColors.Danger
                else -> EnigmaColors.Subtle
            },
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

@Composable
private fun rowDetail(item: Recording): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochSecond(item.beginEpochSec).atZone(zone)
    val end = Instant.ofEpochSecond(item.endEpochSec).atZone(zone)
    val minutes = ((item.endEpochSec - item.beginEpochSec) / 60).coerceAtLeast(1)
    val place = stringResource(if (item.place == RecordingPlace.RECEIVER) R.string.my_items_place_receiver else R.string.my_items_place_tv)
    val clock = "${start.format(DayFormat)}  ${start.format(ClockFormat)}–${end.format(ClockFormat)}  ·  $minutes min  ·  $place"
    return if (item.status == RecordingStatus.FAILED && item.error.isNotBlank()) {
        "${item.channelName}  ·  $clock  ·  ${item.error}"
    } else {
        "${item.channelName}  ·  $clock"
    }
}

@Composable
private fun statusLabel(status: RecordingStatus): String = stringResource(
    when (status) {
        RecordingStatus.SCHEDULED -> R.string.my_items_scheduled
        RecordingStatus.RECORDING -> R.string.my_items_recording
        RecordingStatus.READY -> R.string.my_items_ready
        RecordingStatus.FAILED -> R.string.my_items_failed
    },
)

private enum class ItemAction(val label: Int) {
    PLAY(R.string.my_items_play),
    STOP(R.string.my_items_stop),
    DELETE(R.string.my_items_delete),
    CANCEL(R.string.cancel),
}

private fun actionsFor(item: Recording, box: Boolean, boxTimer: Boolean): List<ItemAction> = buildList {
    if (!boxTimer && (item.status == RecordingStatus.READY || item.status == RecordingStatus.RECORDING)) add(ItemAction.PLAY)
    if (!box && item.status == RecordingStatus.RECORDING) add(ItemAction.STOP)
    add(ItemAction.DELETE)
    add(ItemAction.CANCEL)
}

/**
 * An IPTV card in the recordings' poster style. OK opens it; Menu or holding OK opens the
 * options (remove from favorites / from Continue watching).
 */
@Composable
internal fun IptvCard(
    card: com.enigmacast.domain.xtream.IptvLibraryCard,
    modifier: Modifier,
    onClick: () -> Unit,
    onOptions: () -> Unit,
) {
    val focus = com.enigmacast.ui.soccer.rememberTvFocus()
    var held by remember { mutableStateOf(false) }
    Column(
        modifier
            .width(100.dp)
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                when {
                    e.key == Key.Menu -> { if (e.type == KeyEventType.KeyUp) onOptions(); true }
                    ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> {
                        if (!held) { held = true; focus.pressed = false; onOptions() }
                        true
                    }
                    ok && e.type == KeyEventType.KeyUp && held -> { held = false; true }
                    else -> false
                }
            }
            .tvClick(focus, onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier
                .width(100.dp)
                .height(150.dp)
                .border(if (focus.focused) 3.dp else 0.dp, if (focus.focused) EnigmaColors.Cyan else androidx.compose.ui.graphics.Color.Transparent, skinShape(12.dp)),
        ) {
            val film = com.enigmacast.domain.films.HomeFilm(
                com.enigmacast.domain.films.FilmCandidate(
                    com.enigmacast.domain.epg.GuideEvent(-1, "iptv:${card.id}", card.title, "", "", 0, 0), "", card.title, null,
                ),
                com.enigmacast.domain.films.TmdbMovie(id = 0, title = card.title, posterPath = card.poster, isSeries = card.kind != "m"),
            )
            com.enigmacast.ui.home.PosterBox(film, Modifier.fillMaxSize())
            if (card.progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(androidx.compose.ui.graphics.Color(0x66000000))) {
                    Box(Modifier.fillMaxWidth(card.progress).height(4.dp).background(EnigmaColors.Cyan))
                }
            }
        }
        Text(card.title, color = if (focus.focused) EnigmaColors.Text else EnigmaColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (card.subtitle.isNotBlank()) Text(card.subtitle, color = EnigmaColors.Subtle, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
