package com.enigmacast.ui.update

import com.enigmacast.ui.theme.NavigationSoundsForWindow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.enigmacast.BuildConfig
import com.enigmacast.R
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.update.RemoteRelease
import com.enigmacast.update.reinstallText
import com.enigmacast.util.UiText
import com.enigmacast.util.asString
import com.enigmacast.util.isNotEmpty
import kotlinx.coroutines.launch

/**
 * "Update available" prompt. It is a real dialog window: D-pad focus cannot leave it, and
 * focus requests from the screen behind it (the dashboard's "Stream" item, channel list
 * focus retries) cannot take the keys. Update has focus by default; while the download
 * runs, Cancel has it. Back cancels (and stops a running download).
 */
@Composable
fun AppUpdateDialog(
    release: RemoteRelease,
    progress: Int?,
    message: UiText?,
    installing: Boolean = false,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        NavigationSoundsForWindow()
        val downloading = progress != null || installing
        val updateFocus = remember { FocusRequester() }
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(downloading) {
            (if (downloading) cancelFocus else updateFocus).requestWhenReady()
        }
        Column(
            modifier = Modifier
                .width(560.dp)
                // Fits the screen; the notes take what is left and scroll.
                .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.9f).dp)
                .background(EnigmaColors.Selected, RoundedCornerShape(16.dp))
                .border(1.dp, EnigmaColors.CardBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val reinstall = release.reinstallRequired
            Text(
                stringResource(if (reinstall) R.string.update_reinstall_title else R.string.update_available),
                color = EnigmaColors.Text,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(
                    R.string.update_versions,
                    BuildConfig.VERSION_NAME,
                    release.versionName.ifBlank { release.versionCode.toString() },
                ),
                color = EnigmaColors.Accent,
                fontSize = 14.sp,
            )
            if (reinstall) {
                val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
                Text(
                    release.reinstallText(language).ifBlank { stringResource(R.string.update_reinstall_message) },
                    color = EnigmaColors.Text,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                )
            } else if (release.notes.isNotBlank()) {
                ReleaseNotes(release.notes, Modifier.weight(1f, fill = false))
            }
            if (progress != null) {
                DownloadProgress(progress)
            }
            if (installing) {
                Text(stringResource(R.string.update_installing), color = EnigmaColors.Text, fontSize = 13.sp)
            }
            if (message != null && message.isNotEmpty()) {
                Text(message.asString(), color = Color(0xFFFFB4A8), fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (reinstall) {
                // No Update: the new version has to be installed fresh. OK only closes the dialog.
                DialogButton(
                    label = stringResource(R.string.ok),
                    onClick = onCancel,
                    enabled = true,
                    primary = true,
                    modifier = Modifier.focusRequester(updateFocus).padding(top = 4.dp),
                )
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                DialogButton(
                    label = stringResource(R.string.update_action),
                    onClick = onUpdate,
                    enabled = !downloading,
                    primary = true,
                    // Off while downloading/installing: greyed out and not focusable.
                    modifier = Modifier.focusRequester(updateFocus).focusProperties { canFocus = !downloading },
                )
                DialogButton(
                    label = stringResource(R.string.cancel),
                    onClick = onCancel,
                    enabled = true,
                    primary = false,
                    modifier = Modifier.focusRequester(cancelFocus),
                )
            }
        }
    }
}

/**
 * The changelog, at most about eight lines high. When it is longer it can take focus
 * (Up from the buttons) and Up/Down scroll it; Down at the end goes back to the buttons.
 */
@Composable
private fun ReleaseNotes(notes: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scrollable = scroll.maxValue > 0
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(EnigmaColors.Raised, RoundedCornerShape(10.dp))
            .border(1.dp, if (focused) EnigmaColors.Accent else EnigmaColors.CardBorder, RoundedCornerShape(10.dp))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> if (scroll.value < scroll.maxValue) {
                        scope.launch { scroll.animateScrollBy(NOTES_SCROLL_PX) }
                        true
                    } else {
                        false
                    }
                    Key.DirectionUp -> if (scroll.value > 0) {
                        scope.launch { scroll.animateScrollBy(-NOTES_SCROLL_PX) }
                        true
                    } else {
                        false
                    }
                    else -> false
                }
            }
            .focusable(enabled = scrollable, interactionSource = interaction)
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(notes, color = EnigmaColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun DownloadProgress(percent: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.update_downloading, percent), color = EnigmaColors.Text, fontSize = 13.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(EnigmaColors.Raised, RoundedCornerShape(2.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                    .height(4.dp)
                    .background(EnigmaColors.Accent, RoundedCornerShape(2.dp)),
            )
        }
        Text(
            stringResource(R.string.update_stream_stopped),
            color = EnigmaColors.Subtle,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun DialogButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    primary: Boolean,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        colors = ButtonDefaults.colors(
            containerColor = EnigmaColors.Raised,
            contentColor = if (primary) EnigmaColors.Accent else EnigmaColors.Text,
            disabledContainerColor = EnigmaColors.Raised.copy(alpha = 0.4f),
            disabledContentColor = EnigmaColors.Subtle,
            focusedContainerColor = EnigmaColors.Accent,
            focusedContentColor = EnigmaColors.OnAccent,
            pressedContainerColor = EnigmaColors.Accent,
            pressedContentColor = EnigmaColors.OnAccent,
        ),
    ) {
        Text(label, fontSize = 14.sp)
    }
}

private const val NOTES_SCROLL_PX = 120f
