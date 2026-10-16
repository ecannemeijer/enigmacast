package com.enigmacast.ui.connection

import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.skinShape
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.enigmacast.data.discovery.AboutProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.data.discovery.DiscoveredReceiver
import com.enigmacast.ui.theme.DialogButton
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.NavigationSoundsForWindow
import kotlinx.coroutines.delay

/**
 * Shows the AutoDetect dialog for [viewModel] and gives focus back to the AutoDetect button
 * ([returnFocus]) after it closes. [onChosen] runs when the user picks a receiver, before the
 * dialog goes away. Used by Settings › Receiver and the connection screen.
 */
@Composable
fun AutodetectDialogHost(
    viewModel: ConnectionViewModel,
    returnFocus: FocusRequester,
    onChosen: (DiscoveredReceiver) -> Unit = {},
) {
    val state = viewModel.autodetectState
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state is AutodetectState.Hidden) {
        if (state !is AutodetectState.Hidden) {
            wasOpen = true
        } else if (wasOpen) {
            wasOpen = false
            // Let the dialog window go away first so the main window has focus again.
            delay(120)
            runCatching { returnFocus.requestFocus() }
        }
    }
    AutodetectDialog(
        state = state,
        isSelected = viewModel::isSelected,
        onChoose = { receiver ->
            viewModel.chooseDetected(receiver)
            onChosen(receiver)
        },
        onRetry = viewModel::startAutodetect,
        onClose = viewModel::closeAutodetect,
    )
}

@Composable
private fun AutodetectDialog(
    state: AutodetectState,
    isSelected: (DiscoveredReceiver) -> Boolean,
    onChoose: (DiscoveredReceiver) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    if (state is AutodetectState.Hidden) return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NavigationSoundsForWindow()
        val primary = remember { FocusRequester() }
        // Each state has its own first button / row; move focus there when the state changes.
        LaunchedEffect(state) {
            delay(30)
            runCatching { primary.requestFocus() }
        }
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 480.dp)
                .background(EnigmaColors.Selected, skinShape(14.dp))
                .border(1.dp, EnigmaColors.CardBorder, skinShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.autodetect_title), color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = EnigmaType.Strong)
            when (state) {
                AutodetectState.Searching -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = EnigmaColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.autodetect_searching), color = EnigmaColors.Text, fontSize = 15.sp)
                            Text(stringResource(R.string.autodetect_searching_hint), color = EnigmaColors.Muted, fontSize = 13.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                        DialogButton(stringResource(R.string.cancel), onClose, destructive = false, enabled = true, modifier = Modifier.focusRequester(primary))
                    }
                }
                is AutodetectState.Results -> {
                    Text(stringResource(R.string.autodetect_results_hint), color = EnigmaColors.Muted, fontSize = 13.sp)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    ) {
                        state.receivers.forEachIndexed { index, receiver ->
                            ReceiverRow(
                                receiver = receiver,
                                inUse = isSelected(receiver),
                                onClick = { onChoose(receiver) },
                                modifier = if (index == 0) Modifier.focusRequester(primary) else Modifier,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                        DialogButton(stringResource(R.string.autodetect_search_again), onRetry, destructive = false, enabled = true)
                        DialogButton(stringResource(R.string.autodetect_close), onClose, destructive = false, enabled = true)
                    }
                }
                is AutodetectState.NothingFound -> {
                    Text(stringResource(R.string.autodetect_none), color = EnigmaColors.Text, fontSize = 15.sp)
                    Text(
                        stringResource(R.string.autodetect_none_detail, state.addresses, state.subnets),
                        color = EnigmaColors.Muted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    RetryClose(primary, onRetry, onClose)
                }
                AutodetectState.NoNetwork -> {
                    Text(stringResource(R.string.autodetect_no_network), color = EnigmaColors.Text, fontSize = 14.sp, lineHeight = 19.sp)
                    RetryClose(primary, onRetry, onClose)
                }
                AutodetectState.AlreadyAdded -> {
                    Text(stringResource(R.string.autodetect_already), color = EnigmaColors.Text, fontSize = 15.sp)
                    Text(
                        stringResource(R.string.autodetect_already_detail),
                        color = EnigmaColors.Muted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    RetryClose(primary, onRetry, onClose)
                }
                AutodetectState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun RetryClose(primary: FocusRequester, onRetry: () -> Unit, onClose: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
        DialogButton(stringResource(R.string.autodetect_try_again), onRetry, destructive = false, enabled = true, modifier = Modifier.focusRequester(primary))
        DialogButton(stringResource(R.string.autodetect_close), onClose, destructive = false, enabled = true)
    }
}

@Composable
private fun ReceiverRow(receiver: DiscoveredReceiver, inUse: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var preview by remember(receiver.address) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(receiver.address, receiver.loginRequired) {
        if (receiver.loginRequired) return@LaunchedEffect
        val bytes = AboutProbe().preview(receiver) ?: return@LaunchedEffect
        preview = withContext(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ButtonDefaults.shape(shape = skinShape(10.dp)),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        scale = ButtonDefaults.scale(scale = 1f, focusedScale = 1.08f),
        colors = ButtonDefaults.colors(
            containerColor = EnigmaColors.Raised,
            contentColor = EnigmaColors.Text,
            focusedContainerColor = EnigmaColors.Accent,
            focusedContentColor = EnigmaColors.OnAccent,
            pressedContainerColor = EnigmaColors.Accent,
            pressedContentColor = EnigmaColors.OnAccent,
        ),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val title = receiver.model.ifBlank { receiver.address }
            Text((if (inUse) "✓ " else "") + title, fontSize = 15.sp, fontWeight = EnigmaType.Strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = buildList {
                add(receiver.address)
                if (receiver.image.isNotBlank()) add(receiver.image)
                if (receiver.loginRequired) add(stringResource(R.string.autodetect_login_required))
                if (inUse) add(stringResource(R.string.autodetect_in_use))
            }
            Text(details.joinToString("  ·  "), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        preview?.let { bitmap ->
            val ratio = (bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)).coerceIn(1.2f, 1.78f)
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .height(36.dp)
                    .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                    .clip(skinShape(4.dp)),
            )
        }
    }
}
