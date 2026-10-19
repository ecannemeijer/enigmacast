package com.enigmacast.ui.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.activity.ActivityKind
import com.enigmacast.activity.ActivityRules
import com.enigmacast.activity.ActivityView
import com.enigmacast.activity.BackgroundActivityTracker
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.skinShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class ActivityOverlayViewModel @Inject constructor(val tracker: BackgroundActivityTracker) : ViewModel()

/**
 * Video players that fill (almost) the whole window. While one is on screen the status window
 * stays away, so it never covers a programme. Mini players and previews are too small to count.
 */
object FullscreenVideo {
    private val owners = MutableStateFlow(emptySet<Any>())
    val active: StateFlow<Set<Any>> = owners.asStateFlow()

    fun set(owner: Any, fullscreen: Boolean) = owners.update { if (fullscreen) it + owner else it - owner }

    /** True when [width]×[height] covers at least 80 % of the window. */
    fun covers(width: Float, height: Float, windowWidth: Int, windowHeight: Int): Boolean =
        windowWidth > 0 && windowHeight > 0 && width * height >= 0.8f * windowWidth * windowHeight
}

/** Put on a video surface: reports whether it is fullscreen, and clears that when it leaves. */
@Composable
fun Modifier.reportFullscreenVideo(): Modifier {
    val owner = remember { Any() }
    val view = LocalView.current
    DisposableEffect(owner) { onDispose { FullscreenVideo.set(owner, false) } }
    return this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        FullscreenVideo.set(owner, FullscreenVideo.covers(bounds.width, bounds.height, view.width, view.height))
    }
}

/**
 * The small status window at the top of the screen, only during the first sync after the wizard
 * ([com.enigmacast.activity.SetupSession]): what runs, with a bar that fills when the count is
 * known and moves when it is not. It never takes focus; it hides over
 * fullscreen video and fades out shortly after "Done".
 */
@Composable
fun ActivityOverlay(viewModel: ActivityOverlayViewModel, modifier: Modifier = Modifier) {
    val board by viewModel.tracker.board.collectAsState()
    val fullscreen by FullscreenVideo.active.collectAsState()
    var now by remember { mutableLongStateOf(viewModel.tracker.now()) }
    LaunchedEffect(board) {
        // Wake up exactly when a task becomes visible or Done/Failed runs out; no polling loop.
        now = viewModel.tracker.now()
        viewModel.tracker.refresh()
        while (true) {
            val next = ActivityRules.nextChangeMs(board, now) ?: break
            delay((next - now).coerceAtLeast(16))
            now = viewModel.tracker.now()
            // Ends the setup session once it is over (a new board restarts this effect).
            viewModel.tracker.refresh()
        }
    }
    val view = ActivityRules.view(board, now)
    // Keeps the last content during the fade-out, without writing state while composing.
    val last = remember { arrayOfNulls<ActivityView>(1) }
    if (view != null) last[0] = view
    AnimatedVisibility(
        visible = view != null && fullscreen.isEmpty(),
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
        last[0]?.let { ActivityCard(it) }
    }
}

@Composable
private fun kindLabel(kind: ActivityKind) = stringResource(kind.label)

@Composable
private fun ActivityCard(view: ActivityView) {
    val shape = skinShape(14.dp)
    val edge = when (view) {
        is ActivityView.Failed -> EnigmaColors.Danger
        ActivityView.Done -> EnigmaColors.Success
        is ActivityView.Running -> EnigmaColors.GlassEdge
    }
    Column(
        Modifier
            .widthIn(min = 360.dp, max = 560.dp)
            .background(EnigmaColors.Scrim, shape)
            .border(1.dp, edge, shape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            when (view) {
                is ActivityView.Running -> CircularProgressIndicator(color = EnigmaColors.Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
                ActivityView.Done -> Text("✓", color = EnigmaColors.Success, fontSize = 18.sp, fontWeight = EnigmaType.Strong)
                is ActivityView.Failed -> Text("!", color = EnigmaColors.Danger, fontSize = 18.sp, fontWeight = EnigmaType.Strong)
            }
            val text = when (view) {
                is ActivityView.Running -> {
                    val label = kindLabel(view.kind)
                    when {
                        view.total != null -> stringResource(R.string.activity_progress, label, view.done ?: 0, view.total)
                        view.done != null && view.done > 0 -> stringResource(R.string.activity_items, label, view.done)
                        else -> stringResource(R.string.activity_busy, label)
                    }
                }
                ActivityView.Done -> stringResource(R.string.activity_done)
                is ActivityView.Failed -> stringResource(R.string.activity_failed, kindLabel(view.kind))
            }
            Text(text, color = EnigmaColors.Text, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (view is ActivityView.Running) {
            val barModifier = Modifier.fillMaxWidth().height(4.dp)
            val fraction = view.fraction
            if (fraction == null) {
                LinearProgressIndicator(modifier = barModifier, color = EnigmaColors.Cyan, trackColor = EnigmaColors.Raised)
            } else {
                val animated by animateFloatAsState(fraction, label = "activity")
                LinearProgressIndicator(progress = { animated }, modifier = barModifier, color = EnigmaColors.Cyan, trackColor = EnigmaColors.Raised)
            }
            if (view.waiting > 0) {
                Text(
                    pluralStringResource(R.plurals.activity_waiting, view.waiting, view.waiting),
                    color = EnigmaColors.Muted,
                    fontSize = 14.sp,
                )
            }
        }
    }
}
