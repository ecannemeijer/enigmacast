package com.enigmacast.ui.dashboard

import com.enigmacast.ui.theme.trapVertical
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.enigmacast.ui.theme.skinShape
import com.enigmacast.ui.theme.focusRequesterIf
import com.enigmacast.ui.theme.requestWhenAttached
import com.enigmacast.ui.theme.trackedFocusRequester
import com.enigmacast.ui.theme.trackedFocusRequesterIf
import com.enigmacast.ui.theme.holdPageFocus
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.flow.first
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
    /** A Torrent card: the dashboard opens the Torrent page (its info page is already requested). */
    onTorrent: () -> Unit = {},
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
    // IPTV and Torrent: Continue watching and favorites, above the recordings.
    val iptvContinue by viewModel.iptvContinue.collectAsStateWithLifecycle()
    val iptvFavorites by viewModel.iptvFavorites.collectAsStateWithLifecycle()
    val torrentFavorites by viewModel.torrentFavorites.collectAsStateWithLifecycle()
    val iptvArt by viewModel.iptvPosters.collectAsStateWithLifecycle()
    val iptvBlocks = if (viewModel.iptvAllowed()) {
        listOf(
            R.string.library_continue to iptvContinue,
            R.string.library_iptv_favorites to iptvFavorites,
            R.string.library_torrent_favorites to torrentFavorites,
        ).filter { it.second.isNotEmpty() }
    } else emptyList()
    val iptvRowCount = iptvBlocks.size
    val iptvRowStates = remember { mutableMapOf<Int, androidx.compose.foundation.lazy.LazyListState>() }
    val iptvReturnKey by viewModel.returnKeys.collectAsStateWithLifecycle()
    // Where Right from the menu lands: a recording that had the focus, else the first card on the
    // page (the IPTV/Torrent rows come first; the recordings may be scrolled out of the list then).
    val allRecordings = items + sections.onReceiver + sections.scheduledOnReceiver
    val recordingEntry = viewModel.focusId?.takeIf { id -> allRecordings.any { it.id == id } }
    val entryOnIptv = iptvBlocks.isNotEmpty() && recordingEntry == null
    // Entering the list: bring the entry on screen first, so its requester is attached.
    LaunchedEffect(rowsFocusable, entryOnIptv, recordingEntry) {
        if (!rowsFocusable || allRecordings.isEmpty() || com.enigmacast.domain.xtream.LibraryReturn.isMyItems(iptvReturnKey)) return@LaunchedEffect
        val index = if (entryOnIptv) 0 else listIndexOf(recordingEntry ?: allRecordings.first().id, items, sections.onReceiver, sections.scheduledOnReceiver, viewModel::isPoster)
            .let { if (it < 0) -1 else it + iptvRowCount }
        if (index >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
    }
    CompositionLocalProvider(LocalEdgeLeft provides menuFocus) {
    Column(
        modifier
            .fillMaxSize()
            .onFocusChanged { if (it.hasFocus) onRetreatTo(menuFocus) }
            // The empty/loading line is replaced by the list: keep the focus on the page (first item).
            .holdPageFocus(listFocus, sections.all.firstOrNull()?.id)
            // Up at the first row and Down at the last stay on the page (Left still goes to the menu).
            .trapVertical(),
    ) {
        PanelHeader(stringResource(R.string.nav_my_items))
        val notice = viewModel.notice.asString()
        if (notice.isNotBlank()) {
            Text(notice, color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        // One IPTV row (header + sideways cards); inside the LazyColumn when recordings exist so everything scrolls.
        val iptvRow: @Composable (Int, List<com.enigmacast.domain.xtream.IptvLibraryCard>) -> Unit = { header, cards ->
            Column {
                Text(stringResource(header), color = EnigmaColors.Cyan, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 4.dp))
                androidx.compose.foundation.lazy.LazyRow(
                    state = iptvRowStates.getOrPut(header) { androidx.compose.foundation.lazy.LazyListState() },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(cards, key = { _, c -> c.key }) { i, card ->
                        IptvCard(
                            card = card,
                            poster = viewModel.iptvPoster(card, iptvArt),
                            modifier = Modifier
                                // The page's entry is the first card of the first row (see entryOnIptv).
                                .trackedFocusRequesterIf(entryOnIptv && i == 0 && header == iptvBlocks.first().first, listFocus)
                                .then(if (iptvReturnKey == "$header/${card.key}") Modifier.trackedFocusRequester(iptvReturnFocus) else Modifier)
                                .focusProperties {
                                    canFocus = rowsFocusable
                                    if (i == 0) left = menuFocus
                                    if (i == cards.lastIndex) right = FocusRequester.Cancel
                                },
                            // OK: the info page; long press or Menu: Play / Info / Remove.
                            onClick = { viewModel.okIptv(card, "$header/${card.key}", onXtream, onTorrent) },
                            onOptions = { viewModel.optionsIptv(card, "$header/${card.key}") },
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
                    .trackedFocusRequesterIf(!entryOnIptv, listFocus)
                    .focusProperties {
                        canFocus = rowsFocusable
                        left = menuFocus
                    }
                    .focusable()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        } else {
            val focusOn = recordingEntry ?: all.first().id
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
                                        .trackedFocusRequesterIf(!entryOnIptv && item.id == focusOn, listFocus)
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
                                .trackedFocusRequesterIf(!entryOnIptv && item.id == focusOn, listFocus)
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
    // Back from an IPTV or Torrent info page (or its player) opened here: its row and card on
    // screen, then the focus on that card. The rows may still be loading when the page returns
    // (each comes from its own flow), so wait for the card a moment before giving up.
    val currentBlocks by androidx.compose.runtime.rememberUpdatedState(iptvBlocks)
    val currentRecordings by androidx.compose.runtime.rememberUpdatedState(allRecordings.isNotEmpty())
    LaunchedEffect(iptvReturnKey) {
        val key = iptvReturnKey ?: return@LaunchedEffect
        if (!com.enigmacast.domain.xtream.LibraryReturn.isMyItems(key)) return@LaunchedEffect
        val rows = { currentBlocks.map { (h, cards) -> h.toString() to cards.map { it.key } } }
        val found = kotlinx.coroutines.withTimeoutOrNull(2_000) {
            androidx.compose.runtime.snapshotFlow { com.enigmacast.domain.xtream.LibraryReturn.locate(key, rows()) }
                .first { it != null }
        }
        if (found == null) {
            // The card is gone (removed elsewhere): the page's own entry instead.
            viewModel.consumeReturn()
            listFocus.requestWhenAttached()
            return@LaunchedEffect
        }
        val (row, card) = found
        // The rows sit inside the LazyColumn when there are recordings: that row first.
        if (currentRecordings) listState.scrollToItem(row)
        val header = currentBlocks[row].first
        iptvRowStates.getOrPut(header) { androidx.compose.foundation.lazy.LazyListState() }.scrollToItem(card)
        // The card's requester is attached once the row composed it; the list may also still be
        // waiting to become focusable (the dashboard hands it over a few frames later).
        if (!iptvReturnFocus.requestWhenAttached(maxFrames = 90)) listFocus.requestWhenAttached()
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
        listFocus.requestWhenAttached()
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
    IptvCardDialogs(viewModel, onXtream, onTorrent)
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
 * Long press (or Menu) on a My Items or Home IPTV/Torrent card: Play / Info / Remove, then the
 * confirmation of the removal.
 */
@Composable
internal fun IptvCardDialogs(viewModel: MyItemsViewModel, onXtream: () -> Unit, onTorrent: () -> Unit) {
    viewModel.iptvOptions?.let { card ->
        val play = stringResource(R.string.iptv_play)
        val info = stringResource(R.string.iptv_info)
        val remove = stringResource(if (card.key.startsWith("fav/")) R.string.iptv_library_remove else R.string.library_remove_continue)
        TvChoiceDialog(
            title = card.title,
            message = card.subtitle,
            choices = listOf(play, info, remove),
            onChoose = { i ->
                when (i) {
                    0 -> viewModel.playChosenIptv(onXtream, onTorrent)
                    1 -> viewModel.infoChosenIptv(onXtream, onTorrent)
                    else -> viewModel.askRemoveIptv()
                }
            },
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
}

/**
 * An IPTV card in the recordings' poster style. OK opens it (My Items: the info page); Menu or holding OK opens
 * the options on release (Play / Info / remove from favorites or Continue watching).
 */
@Composable
internal fun IptvCard(
    card: com.enigmacast.domain.xtream.IptvLibraryCard,
    modifier: Modifier,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    poster: String? = card.poster,
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
                    // A new press starts clean (a hold whose release went elsewhere is forgotten).
                    ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> { held = false; false }
                    // Held OK: no click on release, the options instead. They open on the release, so
                    // the key's repeats and release do not reach the dialog and pick its first answer.
                    ok && e.type == KeyEventType.KeyDown -> { held = true; focus.pressed = false; true }
                    ok && e.type == KeyEventType.KeyUp && held -> { held = false; focus.pressed = false; onOptions(); true }
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
                com.enigmacast.domain.films.TmdbMovie(id = 0, title = card.title, posterPath = poster, isSeries = card.kind != "m"),
            )
            com.enigmacast.ui.home.PosterBox(film, Modifier.fillMaxSize())
            if (card.progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(androidx.compose.ui.graphics.Color(0x66000000))) {
                    Box(Modifier.fillMaxWidth(card.progress).height(4.dp).background(if (card.watched) EnigmaColors.Success else EnigmaColors.Cyan))
                }
            }
            if (card.watched) com.enigmacast.ui.components.WatchedCheck(Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(card.title, color = if (focus.focused) EnigmaColors.Text else EnigmaColors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val subtitle = if (card.upNext) listOf(stringResource(R.string.library_up_next), card.subtitle).filter { it.isNotBlank() }.joinToString(" · ") else card.subtitle
        if (subtitle.isNotBlank()) Text(subtitle, color = EnigmaColors.Subtle, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
