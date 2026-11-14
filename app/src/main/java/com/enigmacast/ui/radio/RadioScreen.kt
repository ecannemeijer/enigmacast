package com.enigmacast.ui.radio

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.enigmacast.R
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.domain.radio.RadioCategory
import com.enigmacast.domain.radio.RadioStation
import kotlinx.coroutines.delay

private val RADIO_CARD_WIDTH = 108.dp
private val RADIO_CARD_ART = 48.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadioScreen(
    onBack: () -> Unit = {},
    contentFocus: androidx.compose.ui.focus.FocusRequester? = null,
    viewModel: RadioViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val isTv = true
    val isTouch = false
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var fullscreen by remember { mutableStateOf(false) }
    var selectedStation by remember { mutableStateOf<RadioStation?>(null) }
    var countryMenuOpen by remember { mutableStateOf(false) }
    var genreMenuOpen by remember { mutableStateOf(false) }
    var searchEditing by remember { mutableStateOf(false) }
    var radioHasFocus by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    val anchors = remember { RadioFocusAnchors() }

    val overlayOpen = fullscreen || countryMenuOpen || genreMenuOpen || selectedStation != null || searchEditing

    fun play(station: RadioStation) = viewModel.playStation(station)
    fun openFullscreen(station: RadioStation) {
        viewModel.playStation(station)
        fullscreen = true
    }
    fun toggleFavorite(station: RadioStation) {
        val adding = !station.isFavorite
        viewModel.toggleFavorite(station)
        Toast.makeText(
            context,
            context.getString(
                if (adding) R.string.radio_added_to_favorites else R.string.radio_removed_from_favorites
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun handleRadioBack(): Boolean {
        when {
            imeVisible || searchEditing -> {
                searchEditing = false
                imeSeen = false
                keyboard?.hide()
            }
            countryMenuOpen -> countryMenuOpen = false
            genreMenuOpen -> genreMenuOpen = false
            selectedStation != null -> selectedStation = null
            fullscreen -> fullscreen = false
            else -> onBack()
        }
        return true
    }

    // Only while Radio content (or an overlay) owns the focus, so Back on the left menu
    // still walks up the dashboard like every other page.
    BackHandler(enabled = radioHasFocus || overlayOpen) {
        handleRadioBack()
    }

    LaunchedEffect(imeVisible, searchEditing) {
        if (searchEditing && imeVisible) imeSeen = true
        if (searchEditing && imeSeen && !imeVisible) {
            searchEditing = false
            imeSeen = false
        }
        if (!searchEditing) imeSeen = false
    }

    LaunchedEffect(
        state.nowPlaying?.stationUuid,
        state.isPlaying,
        fullscreen,
        selectedStation,
        isTv
    ) {
        if (!isTv || fullscreen || !state.isPlaying || state.nowPlaying == null || selectedStation != null) {
            return@LaunchedEffect
        }
        delay(60_000)
        selectedStation = null
        fullscreen = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EnigmaColors.Canvas)
            .onFocusChanged { radioHasFocus = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Back && event.key != Key.Escape) return@onPreviewKeyEvent false
                val ours = radioHasFocus || overlayOpen
                if (!ours) return@onPreviewKeyEvent false
                // The system IME (its own window) gets Back first and hides itself.
                if (imeVisible) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) return@onPreviewKeyEvent true
                if (event.type == KeyEventType.KeyUp) {
                    handleRadioBack()
                    return@onPreviewKeyEvent true
                }
                false
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            RadioTopBar(
                query = state.query,
                isTouch = isTouch,
                contentFocus = contentFocus,
                anchors = anchors,
                editing = searchEditing,
                onEditingChange = { searchEditing = it },
                onBack = { handleRadioBack() },
                onQueryChange = viewModel::onQueryChange,
                onRetry = viewModel::retryBrowse
            )
            RadioFilterRow(
                state = state,
                anchors = anchors,
                countryMenuOpen = countryMenuOpen,
                onCountryMenuOpenChange = {
                    countryMenuOpen = it
                    if (it) genreMenuOpen = false
                },
                genreMenuOpen = genreMenuOpen,
                onGenreMenuOpenChange = {
                    genreMenuOpen = it
                    if (it) countryMenuOpen = false
                },
                onCountrySelected = viewModel::onCountrySelected,
                onTagSelected = viewModel::onTagSelected,
                onToggleCountryPinned = viewModel::toggleCountryPinned
            )
            if (state.favorites.isNotEmpty()) {
                RadioFavoritesRow(
                    stations = state.favorites,
                    nowPlayingId = state.nowPlaying?.stationUuid,
                    onSelect = { selectedStation = it }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isBrowseLoading && !state.isFilterActive -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = EnigmaColors.Focus
                        )
                    }
                    state.error == "browse_failed" && !state.isFilterActive -> {
                        RadioMessage(
                            text = stringResource(id = R.string.radio_browse_error),
                            onRetry = viewModel::retryBrowse,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    state.isFilterActive -> {
                        RadioSearchGrid(
                            state = state,
                            onSelect = { selectedStation = it }
                        )
                    }
                    else -> {
                        RadioBrowseShelves(
                            state = state,
                            onSelect = { selectedStation = it }
                        )
                    }
                }
            }
            RadioNowPlayingBar(
                state = state,
                anchors = anchors,
                onTogglePlayPause = viewModel::togglePlayPause,
                onToggleFavorite = { station -> toggleFavorite(station) },
                onPlayPrevious = { viewModel.playAdjacent(-1) },
                onPlayNext = { viewModel.playAdjacent(1) },
                onOpenFullscreen = { station -> openFullscreen(station) }
            )
        }

        if (fullscreen) {
            state.nowPlaying?.let { station ->
                // A window-level dialog covers the whole activity, so the left menu is gone.
                Dialog(
                    onDismissRequest = { fullscreen = false },
                    properties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                        dismissOnBackPress = true,
                        dismissOnClickOutside = false,
                    ),
                ) {
                    RadioFullscreenPlayer(
                        station = station,
                        state = state,
                        onBack = { fullscreen = false },
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onToggleFavorite = { toggleFavorite(station) },
                        onPlayPrevious = { viewModel.playAdjacent(-1) },
                        onPlayNext = { viewModel.playAdjacent(1) },
                        onCycleSleepTimer = viewModel::cycleSleepTimer
                    )
                }
            }
        }

        selectedStation?.let { station ->
            RadioActionsDialog(
                station = station,
                onDismiss = { selectedStation = null },
                onPlay = {
                    play(station)
                    selectedStation = null
                },
                onToggleFavorite = {
                    toggleFavorite(station)
                    selectedStation = null
                },
                onOpenFullscreen = {
                    openFullscreen(station)
                    selectedStation = null
                }
            )
        }
    }
}

@Composable
private fun RadioTopBar(
    query: String,
    isTouch: Boolean,
    contentFocus: FocusRequester? = null,
    anchors: RadioFocusAnchors,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // On TV the Back key and Left-to-menu already leave the page, so the
        // arrow is clickable for touch but skipped in the D-pad order.
        RadioIconButton(
            onClick = onBack,
            modifier = if (isTouch) Modifier else Modifier.focusProperties { canFocus = false },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(id = R.string.back),
                tint = EnigmaColors.Text,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(Icons.Default.Radio, contentDescription = null, tint = EnigmaColors.Cyan, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(id = R.string.menu_radio),
            color = EnigmaColors.Text,
            fontSize = if (isTouch) 18.sp else 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(16.dp))
        RadioSearchField(
            query = query,
            onQueryChange = onQueryChange,
            editing = editing,
            onEditingChange = onEditingChange,
            contentFocus = contentFocus,
            modifier = Modifier
                .weight(1f)
                .focusProperties {
                    right = anchors.refresh
                    down = anchors.country
                },
        )
        Spacer(modifier = Modifier.width(8.dp))
        RadioIconButton(
            onClick = onRetry,
            modifier = Modifier
                .focusRequester(anchors.refresh)
                .focusProperties {
                    left = contentFocus ?: FocusRequester.Default
                    down = anchors.genre
                },
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(id = R.string.radio_refresh),
                tint = EnigmaColors.Muted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun RadioFavoritesRow(
    stations: List<RadioStation>,
    nowPlayingId: String?,
    onSelect: (RadioStation) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            text = stringResource(id = R.string.radio_favorites),
            color = EnigmaColors.Text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer()
                .focusProperties {
                    onExit = {
                        if (requestedFocusDirection == FocusDirection.Right) {
                            cancelFocusChange()
                        }
                    }
                }
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(stations, key = { _, it -> it.stationUuid }) { index, station ->
                RadioFavoriteChip(
                    station = station,
                    selected = station.stationUuid == nowPlayingId,
                    onSelect = { onSelect(station) },
                    modifier = Modifier.focusProperties {
                        if (index == stations.lastIndex) right = FocusRequester.Cancel
                    },
                )
            }
        }
    }
}

@Composable
private fun RadioFavoriteChip(
    station: RadioStation,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .clip(radioShape(16.dp))
            .background(if (selected) EnigmaColors.Focus.copy(alpha = 0.28f) else EnigmaColors.Card)
            .border(
                width = if (focused || selected) 2.dp else 1.dp,
                color = when {
                    focused -> EnigmaColors.Focus
                    selected -> EnigmaColors.Focus.copy(alpha = 0.8f)
                    else -> EnigmaColors.CardBorder
                },
                shape = radioShape(16.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioFavicon(url = station.favicon, size = 22.dp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = station.name,
            color = EnigmaColors.Text,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(100.dp)
        )
    }
}

@Composable
private fun RadioFilterRow(
    state: RadioUiState,
    anchors: RadioFocusAnchors,
    countryMenuOpen: Boolean,
    onCountryMenuOpenChange: (Boolean) -> Unit,
    genreMenuOpen: Boolean,
    onGenreMenuOpenChange: (Boolean) -> Unit,
    onCountrySelected: (RadioCategory?) -> Unit,
    onTagSelected: (RadioCategory?) -> Unit,
    onToggleCountryPinned: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioFilterDropdown(
            modifier = Modifier.weight(1f),
            fieldFocus = anchors.country,
            rightFocus = anchors.pin,
            label = stringResource(id = R.string.radio_country),
            allLabel = stringResource(id = R.string.radio_all_countries),
            selected = state.selectedCountry,
            options = state.countries,
            expanded = countryMenuOpen,
            onExpandedChange = onCountryMenuOpenChange,
            onSelected = onCountrySelected
        )
        var pinFocused by remember { mutableStateOf(false) }
        val pinEnabled = state.selectedCountry != null || state.countryPinned
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    when {
                        pinFocused -> EnigmaColors.Focus
                        state.countryPinned -> EnigmaColors.Cyan.copy(alpha = 0.22f)
                        else -> EnigmaColors.Card
                    }
                )
                .border(
                    BorderStroke(
                        width = if (pinFocused) 2.dp else 1.dp,
                        color = when {
                            pinFocused -> EnigmaColors.OnFocus
                            state.countryPinned -> EnigmaColors.Cyan
                            else -> EnigmaColors.CardBorder
                        }
                    ),
                    shape = CircleShape
                )
                .focusRequester(anchors.pin)
                .focusProperties {
                    left = anchors.country
                    right = anchors.genre
                }
                .onFocusChanged { pinFocused = it.isFocused }
                .focusable()
                .clickable(
                    enabled = pinEnabled,
                    onClick = onToggleCountryPinned
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (state.countryPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = stringResource(
                    id = if (state.countryPinned) R.string.radio_unpin_country else R.string.radio_pin_country
                ),
                tint = when {
                    pinFocused -> EnigmaColors.OnFocus
                    state.countryPinned -> EnigmaColors.Cyan
                    pinEnabled -> EnigmaColors.Text
                    else -> EnigmaColors.Muted
                }
            )
        }
        RadioFilterDropdown(
            modifier = Modifier.weight(1f),
            fieldFocus = anchors.genre,
            leftFocus = anchors.pin,
            label = stringResource(id = R.string.radio_genre),
            allLabel = stringResource(id = R.string.radio_all_genres),
            selected = state.selectedTag,
            options = state.tags,
            expanded = genreMenuOpen,
            onExpandedChange = onGenreMenuOpenChange,
            onSelected = onTagSelected
        )
    }
}

@Composable
private fun RadioSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    editing: Boolean,
    onEditingChange: (Boolean) -> Unit,
    contentFocus: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val inputFocus = remember { FocusRequester() }
    var shellFocused by remember { mutableStateOf(false) }
    var wasEditing by remember { mutableStateOf(false) }
    val highlighted = shellFocused || editing
    val shape = radioShape(12.dp)

    LaunchedEffect(editing) {
        if (editing) {
            wasEditing = true
            runCatching { inputFocus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
            // The inner text field leaves the tree with the IME; put focus back
            // on the browse shell so Back does not drop into the side menu.
            if (wasEditing) {
                wasEditing = false
                runCatching { contentFocus?.requestFocus() }
            }
        }
    }

    Box(
        modifier = modifier
            .height(52.dp)
            .then(contentFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { shellFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                    if (!editing) {
                        if (event.type == KeyEventType.KeyUp) onEditingChange(true)
                        return@onPreviewKeyEvent true
                    }
                }
                if ((event.key == Key.Back || event.key == Key.Escape) && editing) {
                    // Let the Radio page handler stop editing; don't leave it to the text field.
                    return@onPreviewKeyEvent false
                }
                false
            }
            .focusable()
            .clip(shape)
            .background(EnigmaColors.Card, shape)
            .border(
                width = if (highlighted) 2.dp else 1.dp,
                color = if (highlighted) EnigmaColors.Focus else EnigmaColors.Muted.copy(alpha = 0.4f),
                shape = shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onEditingChange(true) },
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Search, contentDescription = null, tint = EnigmaColors.Muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            if (editing) {
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = EnigmaColors.Text, fontSize = 14.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(EnigmaColors.Focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onEditingChange(false) }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(inputFocus),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(stringResource(id = R.string.radio_search_hint), color = EnigmaColors.Muted, fontSize = 14.sp)
                        }
                        inner()
                    },
                )
            } else {
                Text(
                    text = query.ifBlank { stringResource(id = R.string.radio_search_hint) },
                    color = if (query.isBlank()) EnigmaColors.Muted else EnigmaColors.Text,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RadioFilterDropdown(
    modifier: Modifier,
    fieldFocus: FocusRequester? = null,
    leftFocus: FocusRequester? = null,
    rightFocus: FocusRequester? = null,
    label: String,
    allLabel: String,
    selected: RadioCategory?,
    options: List<RadioCategory>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (RadioCategory?) -> Unit
) {
    val entries = remember(options, allLabel) { listOf<RadioCategory?>(null) + options }
    val selectedIndex = entries.indexOfFirst { option ->
        if (selected == null) option == null
        else option != null && (
            (!selected.code.isNullOrBlank() && option.code.equals(selected.code, ignoreCase = true)) ||
                option.name.equals(selected.name, ignoreCase = true)
        )
    }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val selectedFocus = remember { FocusRequester() }
    val internalFieldFocus = remember { FocusRequester() }
    val resolvedFieldFocus = fieldFocus ?: internalFieldFocus
    val density = LocalDensity.current
    var fieldWidthPx by remember { mutableStateOf(0) }
    var fieldHeightPx by remember { mutableStateOf(0) }
    var fieldFocused by remember { mutableStateOf(false) }
    val highlighted = fieldFocused || expanded
    val shape = radioShape(10.dp)

    LaunchedEffect(expanded, selectedIndex) {
        if (!expanded) return@LaunchedEffect
        listState.scrollToItem(selectedIndex)
        runCatching { selectedFocus.requestFocus() }
    }

    Box(
        modifier = modifier.onGloballyPositioned { coords ->
            fieldWidthPx = coords.size.width
            fieldHeightPx = coords.size.height
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(resolvedFieldFocus)
                .focusProperties {
                    leftFocus?.let { left = it }
                    rightFocus?.let { right = it }
                }
                .onFocusChanged { fieldFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                        if (!expanded) {
                            if (event.type == KeyEventType.KeyUp) onExpandedChange(true)
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
                .focusable()
                .clip(shape)
                .background(EnigmaColors.Card, shape)
                .border(
                    width = if (highlighted) 2.dp else 1.dp,
                    color = if (highlighted) EnigmaColors.Focus else EnigmaColors.Muted.copy(alpha = 0.4f),
                    shape = shape,
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onExpandedChange(!expanded) },
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(label, color = EnigmaColors.Muted, fontSize = 11.sp)
            Text(
                text = selected?.name ?: allLabel,
                color = EnigmaColors.Text,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, fieldHeightPx + with(density) { 4.dp.roundToPx() }),
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                val menuWidth = if (fieldWidthPx > 0) with(density) { fieldWidthPx.toDp() } else 360.dp
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .width(menuWidth)
                        .heightIn(max = 420.dp)
                        .clip(shape)
                        .background(EnigmaColors.Card)
                        .border(1.dp, EnigmaColors.Focus.copy(alpha = 0.5f), shape)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Back || event.key == Key.Escape) {
                                if (event.type == KeyEventType.KeyUp) {
                                    onExpandedChange(false)
                                    runCatching { resolvedFieldFocus.requestFocus() }
                                }
                                return@onPreviewKeyEvent true
                            }
                            false
                        }
                ) {
                    itemsIndexed(entries, key = { index, option -> option?.code ?: option?.name ?: "all-$index" }) { index, option ->
                        val isCurrent = index == selectedIndex
                        RadioDropdownOption(
                            title = option?.name ?: allLabel,
                            subtitle = option?.takeIf { it.stationCount > 0 }?.let { "${it.stationCount}" },
                            selected = isCurrent,
                            focusRequester = if (isCurrent) selectedFocus else null,
                            onClick = {
                                onSelected(option)
                                onExpandedChange(false)
                                runCatching { resolvedFieldFocus.requestFocus() }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioDropdownOption(
    title: String,
    subtitle: String?,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .then(
                when {
                    focused -> Modifier.radioFocusBackground(true, radioShape(0.dp))
                    selected -> Modifier.background(EnigmaColors.Selected)
                    else -> Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = radioOnFocus(focused),
            fontSize = 15.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (subtitle != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = subtitle,
                color = radioOnFocus(focused, EnigmaColors.Muted),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun RadioBrowseShelves(

    state: RadioUiState,
    onSelect: (RadioStation) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        if (state.recents.isNotEmpty()) {
            item {
                RadioStationShelf(
                    title = stringResource(id = R.string.radio_recent),
                    stations = state.recents,
                    onSelect = onSelect
                )
            }
        }
        if (state.popular.isNotEmpty()) {
            item {
                RadioStationShelf(
                    title = stringResource(id = R.string.radio_popular),
                    stations = state.popular,
                    onSelect = onSelect
                )
            }
        }
        if (state.topVoted.isNotEmpty()) {
            item {
                RadioStationShelf(
                    title = stringResource(id = R.string.radio_top_voted),
                    stations = state.topVoted,
                    onSelect = onSelect
                )
            }
        }
        if (state.local.isNotEmpty()) {
            item {
                RadioStationShelf(
                    title = stringResource(id = R.string.radio_local),
                    stations = state.local,
                    onSelect = onSelect
                )
            }
        }
    }
}

@Composable
private fun RadioStationShelf(
    title: String,
    stations: List<RadioStation>,
    onSelect: (RadioStation) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            text = title,
            color = EnigmaColors.Text,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer()
                .focusProperties {
                    onExit = {
                        if (requestedFocusDirection == FocusDirection.Right) {
                            cancelFocusChange()
                        }
                    }
                }
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(stations, key = { _, it -> it.stationUuid }) { index, station ->
                RadioStationCard(
                    station = station,
                    onSelect = { onSelect(station) },
                    modifier = Modifier.focusProperties {
                        if (index == stations.lastIndex) right = FocusRequester.Cancel
                    },
                )
            }
        }
    }
}

@Composable
private fun RadioSearchGrid(
    state: RadioUiState,
    onSelect: (RadioStation) -> Unit
) {
    when {
        state.isSearching -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = EnigmaColors.Focus)
            }
        }
        state.searchResults.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(id = R.string.radio_no_results), color = EnigmaColors.Muted)
            }
        }
        else -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = RADIO_CARD_WIDTH),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRestorer()
                    .focusProperties {
                        onExit = {
                            if (requestedFocusDirection == FocusDirection.Right) {
                                cancelFocusChange()
                            }
                        }
                    }
                    .focusGroup()
            ) {
                items(state.searchResults, key = { it.stationUuid }) { station ->
                    RadioStationCard(
                        station = station,
                        onSelect = { onSelect(station) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RadioStationCard(
    station: RadioStation,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .width(RADIO_CARD_WIDTH)
            .clip(radioShape(10.dp))
            .background(EnigmaColors.Card)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) EnigmaColors.Focus else EnigmaColors.CardBorder,
                shape = radioShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onSelect)
            .padding(6.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
            RadioFavicon(
                url = station.favicon,
                size = RADIO_CARD_ART,
                modifier = Modifier.align(Alignment.Center)
            )
            if (station.isFavorite) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = EnigmaColors.Cyan,
                    modifier = Modifier
                        .padding(2.dp)
                        .size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = station.name,
            color = EnigmaColors.Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = station.metaLine.ifBlank { station.tagList.firstOrNull().orEmpty() },
            color = EnigmaColors.Muted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RadioNowPlayingBar(
    state: RadioUiState,
    anchors: RadioFocusAnchors,
    onTogglePlayPause: () -> Unit,
    onToggleFavorite: (RadioStation) -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onOpenFullscreen: (RadioStation) -> Unit
) {
    val station = state.nowPlaying ?: return
    var infoFocused by remember { mutableStateOf(false) }
    val infoShape = radioShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(EnigmaColors.Card)
            .border(width = 1.dp, color = EnigmaColors.CardBorder)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (!state.playbackError.isNullOrBlank()) {
            Text(
                text = stringResource(id = R.string.radio_stream_error),
                color = EnigmaColors.Danger,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer(anchors.play)
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(infoShape)
                    .border(
                        width = if (infoFocused) 2.dp else 0.dp,
                        color = if (infoFocused) EnigmaColors.Focus else Color.Transparent,
                        shape = infoShape
                    )
                    .onFocusChanged { infoFocused = it.isFocused }
                    .clickable { onOpenFullscreen(station) }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioFavicon(url = station.favicon, size = 44.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = station.name,
                        color = EnigmaColors.Text,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.nowPlayingTrackLine
                            ?: station.metaLine.ifBlank { stringResource(id = R.string.radio_now_playing) },
                        color = if (state.nowPlayingTrackLine != null) EnigmaColors.Cyan else EnigmaColors.Muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Default.Fullscreen,
                    contentDescription = stringResource(id = R.string.radio_fullscreen),
                    tint = if (infoFocused) EnigmaColors.Text else EnigmaColors.Muted,
                    modifier = Modifier.size(18.dp)
                )
            }
            RadioIconButton(onClick = onPlayPrevious) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = stringResource(id = R.string.radio_previous_station),
                    tint = EnigmaColors.Text,
                    modifier = Modifier.size(22.dp)
                )
            }
            RadioIconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier.focusRequester(anchors.play),
            ) {
                if (state.isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = EnigmaColors.Text)
                } else {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            id = if (state.isPlaying) R.string.radio_pause else R.string.radio_play
                        ),
                        tint = EnigmaColors.Text
                    )
                }
            }
            RadioIconButton(onClick = onPlayNext) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = stringResource(id = R.string.radio_next_station),
                    tint = EnigmaColors.Text,
                    modifier = Modifier.size(22.dp)
                )
            }
            RadioIconButton(onClick = { onToggleFavorite(station) }) {
                Icon(
                    imageVector = if (station.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = stringResource(
                        id = if (station.isFavorite) {
                            R.string.radio_remove_favorite
                        } else {
                            R.string.radio_add_favorite
                        }
                    ),
                    tint = if (station.isFavorite) EnigmaColors.Cyan else EnigmaColors.Muted
                )
            }
        }
    }
}

@Composable
internal fun RadioFavicon(
    url: String?,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val shape = radioShape(10.dp)
    val hasRemoteArt = !url.isNullOrBlank()
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(EnigmaColors.Raised),
        contentAlignment = Alignment.Center
    ) {
        if (hasRemoteArt) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = painterResource(id = R.drawable.ic_radio_station_default),
                fallback = painterResource(id = R.drawable.ic_radio_station_default),
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.ic_radio_station_default),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun RadioIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .radioFocusBackground(focused, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun RadioMessage(
    text: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = EnigmaColors.Muted)
        Spacer(modifier = Modifier.height(12.dp))
        RadioIconButton(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = EnigmaColors.Text)
        }
    }
}

@Composable
private fun RadioActionsDialog(
    station: RadioStation,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenFullscreen: () -> Unit
) {
    val playFocusRequester = remember { FocusRequester() }
    RequestDialogFocus(playFocusRequester, station.stationUuid)
    AlertDialog(
        modifier = Modifier.radioDialogWidth(RadioDialogDefaults.MenuMaxWidth),
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = station.name,
                color = RadioDialogColors.Title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioDialogMenuButton(
                    text = stringResource(id = R.string.radio_play),
                    icon = Icons.Default.PlayArrow,
                    onClick = onPlay,
                    focusRequester = playFocusRequester
                )
                RadioDialogMenuButton(
                    text = stringResource(
                        id = if (station.isFavorite) {
                            R.string.radio_remove_favorite
                        } else {
                            R.string.radio_add_favorite
                        }
                    ),
                    icon = if (station.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    onClick = onToggleFavorite
                )
                RadioDialogMenuButton(
                    text = stringResource(id = R.string.radio_fullscreen),
                    icon = Icons.Default.Fullscreen,
                    onClick = onOpenFullscreen
                )
                RadioDialogMenuButton(
                    text = stringResource(id = R.string.cancel),
                    icon = Icons.Default.Close,
                    onClick = onDismiss
                )
            }
        },
        confirmButton = {},
        containerColor = RadioDialogColors.ContainerSolid,
        titleContentColor = RadioDialogColors.Title,
        textContentColor = RadioDialogColors.Body,
        shape = RadioDialogDefaults.Shape,
        tonalElevation = 0.dp
    )
}

private class RadioFocusAnchors {
    val refresh = FocusRequester()
    val country = FocusRequester()
    val pin = FocusRequester()
    val genre = FocusRequester()
    val play = FocusRequester()
}
