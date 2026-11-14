package com.enigmacast.ui.radio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import com.enigmacast.R
import com.enigmacast.domain.radio.RadioStation
import com.enigmacast.ui.radio.RadioUiState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val CHROME_HIDE_MS = 5_000L
private const val PIXEL_SHIFT_MS = 70_000
private const val PIXEL_SHIFT_AMPLITUDE_DP = 14f

@Composable
fun RadioFullscreenPlayer(
    station: RadioStation,
    state: RadioUiState,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onCycleSleepTimer: () -> Unit
) {
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var chromeVisible by remember { mutableStateOf(true) }
    var interactionNonce by remember { mutableIntStateOf(0) }

    fun revealChrome() {
        interactionNonce++
    }

    LaunchedEffect(station.stationUuid) {
        revealChrome()
    }
    LaunchedEffect(interactionNonce) {
        chromeVisible = true
        delay(CHROME_HIDE_MS)
        chromeVisible = false
    }
    LaunchedEffect(chromeVisible, station.stationUuid) {
        if (chromeVisible) {
            runCatching { playFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    val shift = rememberInfiniteTransition(label = "radioPixelShift")
    val shiftProgress by shift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PIXEL_SHIFT_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shiftProgress"
    )
    val shiftX = (sin(shiftProgress * 2.0 * PI).toFloat() * PIXEL_SHIFT_AMPLITUDE_DP).dp
    val shiftY = (cos(shiftProgress * 2.0 * PI * 0.73).toFloat() * (PIXEL_SHIFT_AMPLITUDE_DP - 2f)).dp

    val clockText by produceState(initialValue = formatRadioClock()) {
        while (true) {
            value = formatRadioClock()
            delay(1_000)
        }
    }
    val sleepRemainingMs by produceState(
        initialValue = 0L,
        key1 = state.sleepTimerEndsAtMs
    ) {
        val endsAt = state.sleepTimerEndsAtMs
        if (endsAt <= 0L) {
            value = 0L
            return@produceState
        }
        while (true) {
            val remaining = (endsAt - System.currentTimeMillis()).coerceAtLeast(0L)
            value = remaining
            if (remaining == 0L) break
            delay(1_000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RADIO_BG)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.Back || event.key == Key.Escape) return@onPreviewKeyEvent false
                revealChrome()
                if (chromeVisible) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onPlayPrevious()
                        true
                    }
                    Key.DirectionRight -> {
                        onPlayNext()
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onTogglePlayPause()
                        true
                    }
                    else -> false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { revealChrome() }
            )
    ) {
        RadioAmbientBackdrop(
            faviconUrl = station.favicon,
            isPlaying = state.isPlaying
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x9907090F),
                        0.42f to Color(0x6607090F),
                        1f to Color(0xF207090F)
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = shiftX, y = shiftY)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = 40.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = clockText,
                    color = RADIO_TEXT.copy(alpha = 0.82f),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp
                )
                if (state.sleepTimerEndsAtMs > 0L && sleepRemainingMs > 0L) {
                    Text(
                        text = stringResource(
                            id = R.string.player_sleep_timer_remaining,
                            formatRadioSleepRemaining(sleepRemainingMs),
                            "${state.sleepTimerPresetMinutes} min"
                        ),
                        color = RADIO_GOLD.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RadioKenBurnsArt(url = station.favicon, isPlaying = state.isPlaying)
                Spacer(modifier = Modifier.height(28.dp))
                RadioVisualizerBars(isPlaying = state.isPlaying)
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 36.dp, end = 36.dp, bottom = if (chromeVisible) 118.dp else 40.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.radio_now_playing).uppercase(),
                    color = RADIO_GOLD,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp
                )
                Text(
                    text = station.name,
                    color = RADIO_TEXT,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (station.metaLine.isNotBlank()) {
                    Text(
                        text = station.metaLine,
                        color = RADIO_MUTED,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                RadioNowPlayingTrack(state = state)
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(420)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                RadioFsCircleButton(
                    onClick = {
                        revealChrome()
                        onBack()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    filled = false
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(id = R.string.back),
                        tint = RADIO_TEXT
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 36.dp, vertical = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioFsCircleButton(
                        onClick = {
                            revealChrome()
                            onPlayPrevious()
                        }
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = stringResource(id = R.string.radio_previous_station),
                            tint = RADIO_TEXT,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    var playFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(if (playFocused) RADIO_GOLD else RADIO_ACCENT)
                            .focusRequester(playFocus)
                            .onFocusChanged { playFocused = it.isFocused }
                            .clickable {
                                revealChrome()
                                onTogglePlayPause()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.isBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = stringResource(
                                    id = if (state.isPlaying) R.string.radio_pause else R.string.radio_play
                                ),
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                    RadioFsCircleButton(
                        onClick = {
                            revealChrome()
                            onPlayNext()
                        }
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = stringResource(id = R.string.radio_next_station),
                            tint = RADIO_TEXT,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    RadioFsCircleButton(
                        onClick = {
                            revealChrome()
                            onToggleFavorite()
                        }
                    ) {
                        Icon(
                            imageVector = if (station.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = stringResource(
                                id = if (station.isFavorite) {
                                    R.string.radio_remove_favorite
                                } else {
                                    R.string.radio_add_favorite
                                }
                            ),
                            tint = if (station.isFavorite) RADIO_GOLD else RADIO_TEXT
                        )
                    }
                    RadioFsCircleButton(
                        onClick = {
                            revealChrome()
                            onCycleSleepTimer()
                        },
                        emphasized = state.sleepTimerEndsAtMs > 0L
                    ) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = stringResource(id = R.string.player_sleep_timer),
                            tint = if (state.sleepTimerEndsAtMs > 0L) RADIO_GOLD else RADIO_TEXT
                        )
                    }
                    if (state.sleepTimerEndsAtMs > 0L && sleepRemainingMs > 0L) {
                        Text(
                            text = stringResource(
                                id = R.string.player_sleep_timer_remaining,
                                formatRadioSleepRemaining(sleepRemainingMs),
                                "${state.sleepTimerPresetMinutes} min"
                            ),
                            color = RADIO_GOLD,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioNowPlayingTrack(state: RadioUiState) {
    AnimatedContent(
        targetState = Triple(state.streamArtist, state.streamTitle, state.nowPlayingTrackLine),
        transitionSpec = {
            fadeIn(tween(500)) togetherWith fadeOut(tween(320))
        },
        label = "radioTrackMeta"
    ) { (artist, title, trackLine) ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!artist.isNullOrBlank()) {
                Text(
                    text = artist,
                    color = RADIO_GOLD,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    color = RADIO_TEXT.copy(alpha = 0.9f),
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (artist.isNullOrBlank()) {
                Text(
                    text = trackLine
                        ?: stringResource(id = R.string.radio_waiting_track),
                    color = RADIO_MUTED,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RadioAmbientBackdrop(faviconUrl: String?, isPlaying: Boolean) {
    val kenBurns = rememberInfiniteTransition(label = "radioKenBurnsBg")
    val scale by kenBurns.animateFloat(
        initialValue = 1.08f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(28000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgScale"
    )
    val panX by kenBurns.animateFloat(
        initialValue = -36f,
        targetValue = 36f,
        animationSpec = infiniteRepeatable(
            animation = tween(34000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgPanX"
    )
    val panY by kenBurns.animateFloat(
        initialValue = -22f,
        targetValue = 22f,
        animationSpec = infiniteRepeatable(
            animation = tween(41000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgPanY"
    )
    val orbs = rememberInfiniteTransition(label = "radioOrbs")
    val t1 by orbs.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 18000 else 28000, easing = LinearEasing)
        ),
        label = "orb1"
    )
    val t2 by orbs.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 24000 else 36000, easing = LinearEasing)
        ),
        label = "orb2"
    )
    val t3 by orbs.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 21000 else 32000, easing = LinearEasing)
        ),
        label = "orb3"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = faviconUrl?.takeIf { it.isNotBlank() } ?: R.drawable.ic_radio_station_default,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = panX
                    translationY = panY
                    alpha = 0.28f
                },
            contentScale = ContentScale.Crop,
            error = painterResource(id = R.drawable.ic_radio_station_default),
            fallback = painterResource(id = R.drawable.ic_radio_station_default),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val pulse = if (isPlaying) 1f else 0.72f
            drawCircle(
                color = RADIO_GOLD.copy(alpha = 0.14f * pulse),
                radius = w * 0.34f,
                center = Offset(
                    w * (0.28f + 0.22f * sin(t1 * 2.0 * PI).toFloat()),
                    h * (0.32f + 0.18f * cos(t1 * 2.0 * PI).toFloat())
                )
            )
            drawCircle(
                color = RADIO_ACCENT.copy(alpha = 0.16f * pulse),
                radius = w * 0.3f,
                center = Offset(
                    w * (0.72f + 0.18f * cos(t2 * 2.0 * PI).toFloat()),
                    h * (0.58f + 0.2f * sin(t2 * 2.0 * PI).toFloat())
                )
            )
            drawCircle(
                color = Color(0xFF7C4DFF).copy(alpha = 0.12f * pulse),
                radius = w * 0.26f,
                center = Offset(
                    w * (0.5f + 0.24f * sin(t3 * 2.0 * PI).toFloat()),
                    h * (0.78f + 0.12f * cos(t3 * 2.0 * PI).toFloat())
                )
            )
        }
    }
}

@Composable
private fun RadioKenBurnsArt(url: String?, isPlaying: Boolean) {
    val kenBurns = rememberInfiniteTransition(label = "radioKenBurnsArt")
    val scale by kenBurns.animateFloat(
        initialValue = 1.02f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 22000 else 32000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "artScale"
    )
    val panX by kenBurns.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(26000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "artPanX"
    )
    Box(
        modifier = Modifier
            .size(280.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.06f))
    ) {
        RadioFavicon(
            url = url,
            size = 280.dp,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = panX
                }
        )
    }
}

@Composable
private fun RadioVisualizerBars(isPlaying: Boolean) {
    val transition = rememberInfiniteTransition(label = "radioVisualizer")
    val phases = listOf(0.15f, 0.42f, 0.08f, 0.61f, 0.27f, 0.74f, 0.33f)
    val durations = listOf(900, 1100, 800, 1300, 950, 1200, 1050)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(36.dp)
    ) {
        phases.forEachIndexed { index, phase ->
            val value by transition.animateFloat(
                initialValue = 0.22f,
                targetValue = if (isPlaying) 1f else 0.32f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durations[index], easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset((phase * durations[index]).toInt())
                ),
                label = "bar$index"
            )
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height((8 + value * 28).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(RADIO_GOLD.copy(alpha = 0.35f + value * 0.5f))
            )
        }
    }
}

@Composable
private fun RadioFsCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    emphasized: Boolean = false,
    content: @Composable () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                when {
                    focused -> RADIO_ACCENT
                    emphasized -> RADIO_ACCENT.copy(alpha = 0.28f)
                    filled -> RADIO_SURFACE
                    else -> Color.Transparent
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun formatRadioClock(): String =
    DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date())

private fun formatRadioSleepRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
