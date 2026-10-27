package com.enigmacast.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.enigmacast.R
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.ui.remote.GuideDetails
import com.enigmacast.ui.remote.GuideTimelineGrid
import com.enigmacast.ui.remote.rememberGuideClock
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.xtream.IptvGuideRows
import com.enigmacast.ui.xtream.XtreamViewModel
import kotlinx.coroutines.delay

/**
 * IPTV TV guide (dashboard IPTV › TV Guide, Back from IPTV live, hotel menu): the mini player
 * with the highlighted programme, and the channels of one live category as a programme grid.
 * It opens on the playing channel (else the last watched one) at the current time. OK on a
 * programme on air plays that channel full screen; Left on the programme on air opens the
 * categories. Back is left to the caller (to the menu). No catch-up.
 */
@Composable
internal fun IptvGuidePanel(
    model: XtreamViewModel,
    showMiniPlayer: Boolean,
    gridFocus: FocusRequester,
    categoryFocus: FocusRequester,
    menuFocus: FocusRequester,
    /** Changes when the guide is entered (menu OK, Back from live): reopen on the playing channel, focus the grid. */
    enterToken: Int,
    /** The channel started full screen from the guide. */
    onPlay: () -> Unit,
    onRetreatTo: (FocusRequester) -> Unit,
) {
    val guide = model.iptvGuide
    val grid by guide.grid.state.collectAsState()
    val nowSec = rememberGuideClock()
    var categoriesOpen by remember { mutableStateOf(false) }
    var categoryOpenToken by remember { mutableIntStateOf(0) }
    var gridFocusToken by remember { mutableIntStateOf(0) }
    var gridFocused by remember { mutableStateOf(false) }
    LaunchedEffect(enterToken) {
        model.openIptvGuide()
        // The grid is focusable while its rows load; the highlight follows the data.
        if (enterToken != 0) gridFocus.requestWhenReady(attempts = 30)
    }
    LaunchedEffect(gridFocusToken) {
        if (gridFocusToken == 0) return@LaunchedEffect
        gridFocus.requestWhenReady()
    }
    LaunchedEffect(categoriesOpen, gridFocused) {
        if (gridFocused) onRetreatTo(if (categoriesOpen) categoryFocus else menuFocus)
    }
    // Listings for the rows around the highlight once scrolling rests; again every minute
    // (the provider is only asked again after the cache time).
    LaunchedEffect(grid.cursor.row, grid.bouquetReference) { guide.requestRows() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            guide.requestRows(settle = false)
        }
    }
    val playingId = model.playing?.item?.takeIf { model.playingLive }?.id
    val playingReference = playingId?.let(IptvGuideRows::reference).orEmpty()
    val focused = grid.focusedChannel
    val focusedCell = remember(grid, nowSec) { guide.grid.focused()?.second }
    val noGuide = stringResource(R.string.epg_no_guide_info)
    val checked = guide.checked
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IptvMiniHeight),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                if (showMiniPlayer) MiniStreamPlayer(Modifier.fillMaxSize())
            }
            GuideDetails(
                title = guide.category?.name ?: stringResource(R.string.nav_guide),
                channel = focused,
                number = focused?.number ?: 0,
                cell = focusedCell,
                nowSec = nowSec,
                autoSwitch = false,
                loading = guide.loadingChannels,
                failed = false,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (categoriesOpen) {
                BouquetColumn(
                    bouquets = guide.categories.map(IptvGuideRows::categoryEntry),
                    selectedReference = guide.category?.let(IptvGuideRows::categoryReference).orEmpty(),
                    bouquetFocus = categoryFocus,
                    settingsFocus = menuFocus,
                    openToken = categoryOpenToken,
                    onPreview = guide::previewCategory,
                    onClose = {
                        categoriesOpen = false
                        gridFocusToken += 1
                    },
                    onColumnFocus = { onRetreatTo(menuFocus) },
                    title = stringResource(R.string.xtream_categories),
                    modifier = Modifier.width(220.dp).fillMaxHeight(),
                )
            }
            GuideTimelineGrid(
                state = grid,
                playingReference = playingReference,
                active = gridFocused,
                nowSec = nowSec,
                numberOf = BouquetEntry::number,
                picon = { entry -> guide.itemOf(entry)?.icon?.takeIf { it.isNotBlank() }?.let { model.logo(it) } },
                cachedPicon = { entry -> guide.itemOf(entry)?.icon?.takeIf { it.isNotBlank() }?.let(model::cachedLogo) },
                emptyLabel = { entry -> noGuide.takeIf { entry.reference in checked && grid.eventsOf(entry).isEmpty() } },
                hint = stringResource(R.string.epg_iptv_guide_hint),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(gridFocus)
                    .onFocusChanged {
                        gridFocused = it.isFocused
                        if (it.isFocused) onRetreatTo(if (categoriesOpen) categoryFocus else menuFocus)
                    }
                    .onPreviewKeyEvent { event ->
                        iptvGridKey(
                            event = event,
                            onMove = { guide.grid.moveRows(it) },
                            onTime = { guide.grid.moveTime(it) },
                            onOk = {
                                val (entry, cell) = guide.grid.focused() ?: return@iptvGridKey
                                if (cell.startSec > System.currentTimeMillis() / 1000) return@iptvGridKey
                                val item = guide.itemOf(entry) ?: return@iptvGridKey
                                if (model.playFromGuide(item)) onPlay()
                            },
                            onCategories = {
                                if (categoriesOpen) {
                                    runCatching { categoryFocus.requestFocus() }
                                } else {
                                    categoriesOpen = true
                                    categoryOpenToken += 1
                                }
                            },
                        )
                    }
                    .focusable(),
            )
        }
    }
}

private val IptvMiniHeight = 158.dp
private const val IPTV_PAGE_ROWS = 6

/** Keys on the IPTV grid; Back is left to the caller. */
private fun iptvGridKey(
    event: KeyEvent,
    onMove: (Int) -> Unit,
    onTime: (Int) -> Boolean,
    onOk: () -> Unit,
    onCategories: () -> Unit,
): Boolean {
    val key = event.key
    if (key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter) {
        // On release, like the buttons: a held OK does not start and then repeat.
        if (event.type == KeyEventType.KeyUp) onOk()
        return true
    }
    if (event.type != KeyEventType.KeyDown) return false
    when (key) {
        Key.DirectionUp -> onMove(-1)
        Key.DirectionDown -> onMove(1)
        Key.ChannelUp, Key.PageUp -> onMove(-IPTV_PAGE_ROWS)
        Key.ChannelDown, Key.PageDown -> onMove(IPTV_PAGE_ROWS)
        Key.DirectionRight -> onTime(1)
        Key.DirectionLeft -> {
            if (onTime(-1)) return true
            if (event.nativeKeyEvent.repeatCount > 0) return true
            onCategories()
        }
        else -> return false
    }
    return true
}
