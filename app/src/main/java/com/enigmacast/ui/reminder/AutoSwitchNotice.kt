package com.enigmacast.ui.reminder

import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.skinShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.domain.reminder.ReminderNotice
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaIcons
import kotlinx.coroutines.delay

/**
 * The AutoSwitch countdown, drawn over every screen. It never takes focus: the screen behind
 * keeps working, only OK ("Switch now") and Back ("Cancel") go to the notice while it shows
 * (MainActivity routes them).
 */
@Composable
fun AutoSwitchNotice(notice: ReminderNotice, modifier: Modifier = Modifier) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(notice.reminder.key, notice.deadlineSec) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(TICK_MS)
        }
    }
    val reminder = notice.reminder
    val seconds = ((notice.deadlineSec * 1000 - nowMs + 999) / 1000).coerceAtLeast(0).toInt()
    val begun = nowMs / 1000 >= reminder.beginEpochSec
    Column(
        modifier = modifier
            .widthIn(min = 360.dp, max = 520.dp)
            .background(EnigmaColors.Scrim, skinShape(16.dp))
            .border(1.dp, EnigmaColors.Accent.copy(alpha = 0.6f), skinShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(EnigmaIcons.Timer, contentDescription = null, tint = EnigmaColors.Accent, modifier = Modifier.size(18.dp))
            Text(
                "  " + stringResource(R.string.autoswitch_notice_title),
                color = EnigmaColors.Accent,
                fontSize = 14.sp,
                fontWeight = EnigmaType.Strong,
            )
        }
        Text(
            stringResource(
                if (begun) R.string.autoswitch_notice_started else R.string.autoswitch_notice,
                reminder.title,
                reminder.channelName,
                seconds,
            ),
            color = EnigmaColors.Text,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (notice.alsoDue.isNotEmpty()) {
            Text(
                stringResource(R.string.autoswitch_also_due, notice.alsoDue.joinToString { "${it.title} (${it.channelName})" }),
                color = EnigmaColors.Muted,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 2.dp)) {
            KeyHint(stringResource(R.string.autoswitch_key_ok), stringResource(R.string.autoswitch_switch_now))
            KeyHint(stringResource(R.string.autoswitch_key_back), stringResource(R.string.cancel))
        }
    }
}

/** "[OK] Switch now": the key on a small chip, then what it does. */
@Composable
private fun KeyHint(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            key,
            color = EnigmaColors.OnAccent,
            fontSize = 12.sp,
            fontWeight = EnigmaType.Strong,
            modifier = Modifier
                .background(EnigmaColors.Accent, skinShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Text("  $action", color = EnigmaColors.Text, fontSize = 14.sp)
    }
}

/** A short line under the notice's place, e.g. when the receiver would not zap. */
@Composable
fun AutoSwitchMessage(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        color = EnigmaColors.Text,
        fontSize = 15.sp,
        modifier = modifier
            .widthIn(max = 520.dp)
            .background(EnigmaColors.Scrim, skinShape(12.dp))
            .border(1.dp, EnigmaColors.Danger, skinShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private const val TICK_MS = 200L
