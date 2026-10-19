package com.enigmacast.ui.remote

import com.enigmacast.ui.theme.skinSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.tv.material3.Text
import android.view.TextureView
import android.widget.FrameLayout
import com.enigmacast.R
import com.enigmacast.playback.PipChoice
import com.enigmacast.playback.PipEntry
import com.enigmacast.playback.PipSource
import com.enigmacast.ui.theme.CompactListRow
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.skinShape
import kotlinx.coroutines.launch

/** The small picture. Always a TextureView: a second SurfaceView garbles the large one on these sticks. */
@Composable
fun PipInset(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.border(2.dp, EnigmaColors.Accent, skinShape(8.dp)),
        factory = { context ->
            val video = TextureView(context).apply { isFocusable = false }
            FrameLayout(context).apply {
                addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                tag = video
                player.setVideoTextureView(video)
            }
        },
        update = { layout ->
            val video = layout.tag as TextureView
            player.setVideoTextureView(video)
        },
        onRelease = { layout ->
            val video = layout.tag as TextureView
            player.clearVideoTextureView(video)
        },
    )
}

/** Sound follows the large picture. The inset stays muted until it is swapped to the front. */
@Composable
fun PipAudio(main: Player, pip: com.enigmacast.playback.PipController) {
    val active by pip.active.collectAsStateWithLifecycle()
    val swapped by pip.swapped.collectAsStateWithLifecycle()
    LaunchedEffect(active, swapped) {
        main.volume = if (active && swapped) 0f else 1f
        if (active) pip.player().volume = if (swapped) 1f else 0f
    }
}

@Composable
fun PipChannelList(
    names: List<String>,
    onChoose: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        PipDialog(stringResource(R.string.pip_step_channel)) {
            if (names.isEmpty()) {
                Text(stringResource(R.string.pip_none), color = EnigmaColors.Text, fontSize = 14.sp)
            } else {
                PipRows(names) { onChoose(it) }
            }
        }
    }
}

private enum class PipStep { SOURCE, CATEGORY, CHANNEL }

/**
 * One dialog at a time: the source, then the bouquet or category, then the channel.
 * Back returns to the previous step. The chosen channel starts in the inset.
 */
@Composable
fun PipPicker(
    loadCategories: suspend (PipSource) -> List<PipChoice>,
    loadChannels: suspend (PipSource, String) -> List<PipEntry>,
    onStart: suspend (List<PipEntry>, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var step by remember { mutableStateOf(PipStep.SOURCE) }
    var source by remember { mutableStateOf<PipSource?>(null) }
    var categories by remember { mutableStateOf<List<PipChoice>>(emptyList()) }
    var channels by remember { mutableStateOf<List<PipEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    fun back() {
        when (step) {
            PipStep.CHANNEL -> step = PipStep.CATEGORY
            PipStep.CATEGORY -> step = PipStep.SOURCE
            PipStep.SOURCE -> onDismiss()
        }
    }
    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        when (step) {
            PipStep.SOURCE -> PipDialog(stringResource(R.string.pip_step_source)) {
                listOf(PipSource.SAT to R.string.pip_sat, PipSource.IPTV to R.string.pip_iptv).forEachIndexed { index, (kind, label) ->
                    val focus = remember { FocusRequester() }
                    if (index == 0) LaunchedEffect(Unit) { focus.requestWhenReady() }
                    CompactListRow(
                        onClick = {
                            source = kind
                            step = PipStep.CATEGORY
                            scope.launch {
                                loading = true
                                categories = loadCategories(kind)
                                loading = false
                            }
                        },
                        modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                    ) { focused ->
                        Text(
                            stringResource(label),
                            color = if (focused) EnigmaColors.OnFocus else EnigmaColors.Text,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
            PipStep.CATEGORY -> PipDialog(stringResource(R.string.pip_step_category)) {
                when {
                    loading -> Text(stringResource(R.string.load_channels), color = EnigmaColors.Text, fontSize = 14.sp)
                    categories.isEmpty() -> Text(stringResource(R.string.pip_none), color = EnigmaColors.Text, fontSize = 14.sp)
                    else -> PipRows(categories.map { it.name }) { index ->
                        val picked = source ?: return@PipRows
                        val category = categories.getOrNull(index) ?: return@PipRows
                        step = PipStep.CHANNEL
                        scope.launch {
                            loading = true
                            channels = loadChannels(picked, category.id)
                            loading = false
                        }
                    }
                }
            }
            PipStep.CHANNEL -> PipDialog(stringResource(R.string.pip_step_channel)) {
                when {
                    loading -> Text(stringResource(R.string.load_channels), color = EnigmaColors.Text, fontSize = 14.sp)
                    channels.isEmpty() -> Text(stringResource(R.string.pip_none), color = EnigmaColors.Text, fontSize = 14.sp)
                    else -> PipRows(channels.map { it.name }) { index ->
                        scope.launch {
                            onStart(channels, index)
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PipDialog(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(min = 320.dp, max = 520.dp)
            .heightIn(max = 560.dp)
            .skinSurface(EnigmaColors.Selected, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = EnigmaType.Strong)
        content()
    }
}

@Composable
private fun PipRows(labels: List<String>, onChoose: (Int) -> Unit) {
    val first = remember(labels) { FocusRequester() }
    LaunchedEffect(labels) { if (labels.isNotEmpty()) first.requestWhenReady() }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(labels) { index, label ->
            CompactListRow(
                onClick = { onChoose(index) },
                modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
            ) { focused ->
                Text(
                    label,
                    color = if (focused) EnigmaColors.OnFocus else EnigmaColors.Text,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
