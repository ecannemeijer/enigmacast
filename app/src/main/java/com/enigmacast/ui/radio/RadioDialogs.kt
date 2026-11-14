package com.enigmacast.ui.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal object RadioDialogColors {
    val ContainerSolid = Color(0xFF181C27)
    val Title = Color(0xFFF0F2F8)
    val Body = Color(0xFFC5CBD8)
    val Accent = Color(0xFF2979FF)
}

internal object RadioDialogDefaults {
    val MenuMaxWidth = 420.dp
    val Shape = RoundedCornerShape(16.dp)
}

internal fun Modifier.radioDialogWidth(maxWidth: androidx.compose.ui.unit.Dp) =
    widthIn(min = 320.dp, max = maxWidth)

@Composable
internal fun RequestDialogFocus(requester: FocusRequester, key: Any?) {
    LaunchedEffect(key) {
        delay(40)
        runCatching { requester.requestFocus() }
    }
}

@Composable
internal fun RadioDialogMenuButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(if (focused) RadioDialogColors.Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (focused) Color.White else RADIO_TEXT, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            color = if (focused) Color.White else RADIO_TEXT,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
