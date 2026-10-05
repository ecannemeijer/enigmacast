package com.enigmacast.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.BuildConfig
import com.enigmacast.ui.theme.TvButton
import com.enigmacast.update.RemoteRelease

@Composable
fun AppUpdateDialog(
    release: RemoteRelease,
    progress: Int?,
    message: String?,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .background(Color(0xFF161C27))
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Update available", color = Color.White, fontSize = 32.sp)
            Text(
                "Installed ${BuildConfig.VERSION_NAME}  →  ${release.versionName.ifBlank { release.versionCode.toString() }}",
                color = Color(0xFFE8B84A),
                fontSize = 20.sp,
            )
            if (release.notes.isNotBlank()) {
                Text(release.notes, color = Color.White, fontSize = 18.sp)
            }
            if (progress != null) {
                Text("Downloading $progress%", color = Color.White, fontSize = 18.sp)
            }
            if (!message.isNullOrBlank()) {
                Text(message, color = Color(0xFFFFB4A8), fontSize = 18.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton("Update", onUpdate, enabled = progress == null)
                TvButton("Cancel", onCancel, enabled = progress == null)
            }
        }
    }
}
