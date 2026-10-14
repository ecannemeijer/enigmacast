package com.enigmacast.ui.remote

import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.skinShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.ui.recording.RecordRed
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.requestWhenReady
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SCRUB_STEP_MS = 10_000L
private const val SKIP_STEP_MS = 30_000L
private const val COMMIT_MS = 500L

/**
 * Playback bar for a recording. Left and right on the line move the place at once;
 * half a second after the key is released the file plays from there.
 */
@Composable
fun RecordingBar(
    ui: RemoteDisplayUiState,
    onSeek: (Long) -> Unit,
    onTogglePause: () -> Unit,
    onOptions: () -> Unit,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
) = RecordingBar(
    title = ui.fileTitle.ifBlank { stringResource(R.string.nav_my_items) },
    positionMs = ui.playerState.positionMs,
    durationMs = ui.playerState.durationMs,
    playing = ui.playerState.playing,
    onSeek = onSeek,
    onTogglePause = onTogglePause,
    onOptions = onOptions,
    onTouch = onTouch,
    modifier = modifier,
)

/** The same bar for any file the app plays itself (recordings, IPTV films and episodes). */
@Composable
fun RecordingBar(
    title: String,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePause: () -> Unit,
    onOptions: () -> Unit,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = durationMs
    var shown by remember { mutableLongStateOf(positionMs) }
    var scrubbing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var commit by remember { mutableStateOf<Job?>(null) }
    val lineFocus = remember { FocusRequester() }
    val pauseFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { lineFocus.requestWhenReady() }
    LaunchedEffect(positionMs, scrubbing) {
        if (!scrubbing) shown = positionMs
    }

    fun endMs(): Long = duration.takeIf { it > 0 } ?: (shown + SKIP_STEP_MS).coerceAtLeast(SKIP_STEP_MS)

    fun nudge(delta: Long) {
        onTouch()
        commit?.cancel()
        scrubbing = true
        shown = (shown + delta).coerceIn(0, endMs())
    }

    fun release() {
        val target = shown
        commit?.cancel()
        commit = scope.launch {
            delay(COMMIT_MS)
            onSeek(target)
            scrubbing = false
        }
    }

    val fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000), Color(0xF0000000))))
            .padding(start = 48.dp, end = 48.dp, top = 36.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = EnigmaType.Strong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ProgressLine(
            fraction = fraction,
            onNudge = ::nudge,
            onRelease = ::release,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(lineFocus)
                .focusProperties { down = pauseFocus },
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(clock(shown), color = Color.White, fontSize = 13.sp)
            Text(
                if (duration > 0) "−${clock((duration - shown).coerceAtLeast(0))}" else clock(0),
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onKeyEvent { event ->
                    if (event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown) {
                        onOptions()
                        true
                    } else {
                        false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton("−30", -SKIP_STEP_MS, ::nudge, ::release)
            TransportButton(
                label = if (playing) "❚❚" else "▶",
                stepMs = 0,
                onNudge = { _: Long -> },
                onRelease = {},
                onClick = onTogglePause,
                modifier = Modifier.focusRequester(pauseFocus),
                large = true,
            )
            TransportButton("+30", SKIP_STEP_MS, ::nudge, ::release)
        }
    }
}

@Composable
private fun ProgressLine(
    fraction: Float,
    onNudge: (Long) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BoxWithConstraints(
        modifier = modifier
            .height(22.dp)
            .onKeyEvent { event ->
                val step = SeekSteps.scrub(event.nativeKeyEvent.repeatCount)
                val delta = when (event.key) {
                    Key.DirectionLeft -> -step
                    Key.DirectionRight -> step
                    else -> return@onKeyEvent false
                }
                when (event.type) {
                    KeyEventType.KeyDown -> onNudge(delta)
                    KeyEventType.KeyUp -> onRelease()
                    else -> Unit
                }
                true
            }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.CenterStart,
    ) {
        val played = maxWidth * fraction.coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.35f), skinShape(2.dp)),
        )
        if (played > 0.dp) {
            Box(
                Modifier
                    .width(played)
                    .height(4.dp)
                    .background(RecordRed, skinShape(2.dp)),
            )
        }
        Box(
            Modifier
                .offset(x = (played - 7.dp).coerceAtLeast(0.dp))
                .size(if (focused) 16.dp else 12.dp)
                .background(RecordRed, CircleShape)
                .border(2.dp, Color.White, CircleShape),
        )
    }
}

@Composable
private fun TransportButton(
    label: String,
    stepMs: Long,
    onNudge: (Long) -> Unit,
    onRelease: () -> Unit,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val diameter = if (large) 56.dp else 48.dp
    Box(
        modifier = modifier
            .size(diameter)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.18f), CircleShape)
            .onKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (!confirm) return@onKeyEvent false
                if (stepMs != 0L && event.type == KeyEventType.KeyDown) onNudge(stepMs)
                if (event.type == KeyEventType.KeyUp) {
                    if (stepMs != 0L) onRelease() else onClick()
                }
                true
            }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (focused) Color.Black else Color.White,
            fontSize = if (large) 18.sp else 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** Holding Left/Right on the line goes faster: 10 s steps, then 30 s, then 60 s. */
object SeekSteps {
    fun scrub(repeatCount: Int): Long = when {
        repeatCount >= 20 -> 60_000L
        repeatCount >= 8 -> 30_000L
        else -> SCRUB_STEP_MS
    }
}
