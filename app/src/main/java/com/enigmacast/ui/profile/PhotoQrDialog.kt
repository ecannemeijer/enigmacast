package com.enigmacast.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.profile.photo.QrCode
import com.enigmacast.ui.soccer.trapFocus
import com.enigmacast.ui.theme.DialogButton
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.requestWhenReady
import com.enigmacast.ui.theme.skinShape
import kotlinx.coroutines.delay

/** QR code for the phone, the URL as text, a countdown, and Close (focused). Back closes too. */
@Composable
fun PhotoQrDialog(state: PhotoQrState, onRetry: () -> Unit, onClose: () -> Unit) {
    val close = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(state::class) {
            if (state is PhotoQrState.Expired) retry.requestWhenReady() else close.requestWhenReady()
            if (state is PhotoQrState.Done) { delay(2_000); onClose() }
        }
        Column(
            Modifier.width(720.dp)
                .background(EnigmaColors.Selected, skinShape(12.dp))
                .border(1.dp, EnigmaColors.CardBorder, skinShape(12.dp))
                .padding(20.dp)
                .trapFocus(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.profile_photo_qr_title), color = EnigmaColors.Text, fontSize = 20.sp, fontWeight = EnigmaType.Strong)
            when (state) {
                PhotoQrState.Starting -> Text(stringResource(R.string.profile_photo_qr_starting), color = EnigmaColors.Muted, fontSize = 15.sp)
                PhotoQrState.NoNetwork -> Text(stringResource(R.string.profile_photo_qr_no_network), color = EnigmaColors.Text, fontSize = 15.sp)
                PhotoQrState.Expired -> Text(stringResource(R.string.profile_photo_qr_expired), color = EnigmaColors.Text, fontSize = 15.sp)
                PhotoQrState.Done -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).background(Color(0xFF16A34A), skinShape(24.dp)), contentAlignment = Alignment.Center) {
                        Text("✓", color = Color.White, fontSize = 28.sp)
                    }
                    Text(stringResource(R.string.profile_photo_qr_done), color = EnigmaColors.Text, fontSize = 18.sp, fontWeight = EnigmaType.Strong)
                }
                is PhotoQrState.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    QrImage(state.qr, Modifier.size(260.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.profile_photo_qr_steps), color = EnigmaColors.Text, fontSize = 15.sp)
                        Text(stringResource(R.string.profile_photo_qr_same_network), color = EnigmaColors.Muted, fontSize = 13.sp)
                        Text(stringResource(R.string.profile_photo_qr_url), color = EnigmaColors.Muted, fontSize = 12.sp)
                        Text(state.url, color = EnigmaColors.Cyan, fontSize = 13.sp)
                        Countdown(state.expiresAt)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state is PhotoQrState.Expired || state is PhotoQrState.NoNetwork) {
                    DialogButton(stringResource(R.string.profile_photo_qr_new), onRetry, destructive = false, enabled = true, compact = true, modifier = Modifier.focusRequester(retry))
                }
                DialogButton(stringResource(R.string.close), onClose, destructive = false, enabled = true, compact = true, modifier = Modifier.focusRequester(close))
            }
        }
    }
}

@Composable
private fun Countdown(expiresAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val left = ((expiresAt - now).coerceAtLeast(0) / 1000).toInt()
    Text(stringResource(R.string.profile_photo_qr_expires, left / 60, left % 60), color = EnigmaColors.Muted, fontSize = 13.sp)
}

/** Black modules on white with a 4-module quiet zone. */
@Composable
internal fun QrImage(qr: QrCode, modifier: Modifier) {
    Canvas(modifier.background(Color.White, skinShape(8.dp))) {
        val cells = qr.size + 8
        val cell = minOf(size.width, size.height) / cells
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr[x, y]) drawRect(Color.Black, Offset((x + 4) * cell, (y + 4) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
