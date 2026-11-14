package com.enigmacast.ui.radio

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.enigmacast.R
import com.enigmacast.domain.radio.RadioCategory
import com.enigmacast.domain.radio.RadioStation
import kotlinx.coroutines.delay

internal val RADIO_BG = Color(0xFF07090F)
internal val RADIO_SURFACE = Color(0xFF181C27)
internal val RADIO_ACCENT = Color(0xFF2979FF)
internal val RADIO_TEXT = Color(0xFFF0F2F8)
internal val RADIO_MUTED = Color(0xFF7A8499)
internal val RADIO_GOLD = Color(0xFFFFB300)
private val RADIO_CARD_WIDTH = 108.dp
private val RADIO_CARD_ART = 48.dp

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
    var fullscreen by remember { mutableStateOf(false) }
    var selectedStation by remember { mutableStateOf<RadioStation?>(null) }

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

    BackHandler(enabled = fullscreen) {
        fullscreen = false
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

    Box(modifier = Modifier.fillMaxSize().background(RADIO_BG)) {
        Column(modifier = Modifier.fillMaxSize()) {
            RadioTopBar(
                query = state.query,
                isTouch = isTouch,
                contentFocus = contentFocus,
                onBack = onBack,
                onQueryChange = viewModel::onQueryChange,
                onRetry = viewModel::retryBrowse
            )
            RadioFilterRow(
                state = state,
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
                            color = RADIO_ACCENT
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
                onTogglePlayPause = viewModel::togglePlayPause,
                onToggleFavorite = { station -> toggleFavorite(station) },
                onOpenFullscreen = { station -> openFullscreen(station) }
            )
        }

        if (fullscreen) {
            state.nowPlaying?.let { station ->
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
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioIconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(id = R.string.back),
                tint = RADIO_TEXT,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(Icons.Default.Radio, contentDescription = null, tint = RADIO_GOLD, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(id = R.string.menu_radio),
            color = RADIO_TEXT,
            fontSize = if (isTouch) 18.sp else 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).height(52.dp).then(contentFocus?.let { Modifier.focusRequester(it) } ?: Modifier),
            singleLine = true,
            placeholder = {
                Text(stringResource(id = R.string.radio_search_hint), color = RADIO_MUTED, fontSize = 14.sp)
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = RADIO_MUTED)
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = RADIO_TEXT,
                unfocusedTextColor = RADIO_TEXT,
                focusedBorderColor = RADIO_ACCENT,
                unfocusedBorderColor = RADIO_MUTED.copy(alpha = 0.4f),
                cursorColor = RADIO_ACCENT,
                focusedContainerColor = RADIO_SURFACE,
                unfocusedContainerColor = RADIO_SURFACE
            ),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        RadioIconButton(onClick = onRetry) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(id = R.string.radio_refresh),
                tint = RADIO_MUTED,
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
            color = RADIO_TEXT,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(stations, key = { it.stationUuid }) { station ->
                RadioFavoriteChip(
                    station = station,
                    selected = station.stationUuid == nowPlayingId,
                    onSelect = { onSelect(station) }
                )
            }
        }
    }
}

@Composable
private fun RadioFavoriteChip(
    station: RadioStation,
    selected: Boolean,
    onSelect: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) RADIO_ACCENT.copy(alpha = 0.28f) else RADIO_SURFACE)
            .border(
                width = if (focused || selected) 2.dp else 1.dp,
                color = when {
                    focused -> RADIO_ACCENT
                    selected -> RADIO_ACCENT.copy(alpha = 0.8f)
                    else -> Color.White.copy(alpha = 0.08f)
                },
                shape = RoundedCornerShape(16.dp)
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
            color = RADIO_TEXT,
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
            label = stringResource(id = R.string.radio_country),
            allLabel = stringResource(id = R.string.radio_all_countries),
            selected = state.selectedCountry,
            options = state.countries,
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
                        pinFocused -> RADIO_ACCENT
                        state.countryPinned -> RADIO_GOLD.copy(alpha = 0.22f)
                        else -> RADIO_SURFACE
                    }
                )
                .border(
                    BorderStroke(
                        width = if (pinFocused) 2.dp else 1.dp,
                        color = when {
                            pinFocused -> Color.White
                            state.countryPinned -> RADIO_GOLD
                            else -> Color.White.copy(alpha = 0.14f)
                        }
                    ),
                    shape = CircleShape
                )
                .onFocusChanged { pinFocused = it.isFocused }
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
                    pinFocused -> Color.White
                    state.countryPinned -> RADIO_GOLD
                    pinEnabled -> RADIO_TEXT
                    else -> RADIO_MUTED
                }
            )
        }
        RadioFilterDropdown(
            modifier = Modifier.weight(1f),
            label = stringResource(id = R.string.radio_genre),
            allLabel = stringResource(id = R.string.radio_all_genres),
            selected = state.selectedTag,
            options = state.tags,
            onSelected = onTagSelected
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioFilterDropdown(
    modifier: Modifier,
    label: String,
    allLabel: String,
    selected: RadioCategory?,
    options: List<RadioCategory>,
    onSelected: (RadioCategory?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.name ?: allLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, color = RADIO_MUTED, fontSize = 11.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = RADIO_TEXT,
                unfocusedTextColor = RADIO_TEXT,
                focusedBorderColor = RADIO_ACCENT,
                unfocusedBorderColor = RADIO_MUTED.copy(alpha = 0.4f),
                focusedContainerColor = RADIO_SURFACE,
                unfocusedContainerColor = RADIO_SURFACE,
                focusedLabelColor = RADIO_MUTED,
                unfocusedLabelColor = RADIO_MUTED
            ),
            shape = RoundedCornerShape(10.dp),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(RADIO_SURFACE)
        ) {
            DropdownMenuItem(
                text = { Text(allLabel, color = RADIO_TEXT) },
                onClick = {
                    onSelected(null)
                    expanded = false
                }
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name, color = RADIO_TEXT, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
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
            color = RADIO_TEXT,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(stations, key = { it.stationUuid }) { station ->
                RadioStationCard(
                    station = station,
                    onSelect = { onSelect(station) }
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
                CircularProgressIndicator(color = RADIO_ACCENT)
            }
        }
        state.searchResults.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(id = R.string.radio_no_results), color = RADIO_MUTED)
            }
        }
        else -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = RADIO_CARD_WIDTH),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
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
    onSelect: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(RADIO_CARD_WIDTH)
            .clip(RoundedCornerShape(10.dp))
            .background(RADIO_SURFACE)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) RADIO_ACCENT else Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(10.dp)
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
                    tint = RADIO_GOLD,
                    modifier = Modifier
                        .padding(2.dp)
                        .size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = station.name,
            color = RADIO_TEXT,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = station.metaLine.ifBlank { station.tagList.firstOrNull().orEmpty() },
            color = RADIO_MUTED,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RadioNowPlayingBar(
    state: RadioUiState,
    onTogglePlayPause: () -> Unit,
    onToggleFavorite: (RadioStation) -> Unit,
    onOpenFullscreen: (RadioStation) -> Unit
) {
    val station = state.nowPlaying ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RADIO_SURFACE)
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.06f))
            .clickable { onOpenFullscreen(station) }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (!state.playbackError.isNullOrBlank()) {
            Text(
                text = stringResource(id = R.string.radio_stream_error),
                color = Color(0xFFFF8A80),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioFavicon(url = station.favicon, size = 44.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.name,
                    color = RADIO_TEXT,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.nowPlayingTrackLine
                        ?: station.metaLine.ifBlank { stringResource(id = R.string.radio_now_playing) },
                    color = if (state.nowPlayingTrackLine != null) RADIO_GOLD else RADIO_MUTED,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { onToggleFavorite(station) }) {
                Icon(
                    imageVector = if (station.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (station.isFavorite) RADIO_GOLD else RADIO_MUTED
                )
            }
            RadioIconButton(onClick = onTogglePlayPause) {
                if (state.isBuffering) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = RADIO_TEXT)
                } else {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            id = if (state.isPlaying) R.string.radio_pause else R.string.radio_play
                        ),
                        tint = RADIO_TEXT
                    )
                }
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
    val shape = RoundedCornerShape(10.dp)
    val hasRemoteArt = !url.isNullOrBlank()
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f)),
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
            .background(if (focused) RADIO_ACCENT else Color.Transparent)
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
        Text(text, color = RADIO_MUTED)
        Spacer(modifier = Modifier.height(12.dp))
        RadioIconButton(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = RADIO_TEXT)
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
