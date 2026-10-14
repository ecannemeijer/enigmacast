package com.enigmacast.ui.recording

import com.enigmacast.ui.theme.skinShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.domain.service.BouquetChannels

val RecordRed = Color(0xFFE50914)

fun isRecording(references: Set<String>, service: String): Boolean {
    return references.any { BouquetChannels.sameService(it, service) }
}

/** A red dot on a channel row that is being recorded. */
@Composable
fun RecordingDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(8.dp)
            .background(RecordRed, CircleShape),
    )
}

/** Red REC chip with the channel name, for the corner of the picture. */
@Composable
fun RecordingChip(channel: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color(0xCC000000), skinShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RecordingDot()
        Text("REC", color = RecordRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (channel.isNotBlank()) {
            Text(
                channel,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
