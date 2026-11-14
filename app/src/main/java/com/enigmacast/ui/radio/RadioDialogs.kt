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
import com.enigmacast.ui.theme.EnigmaColors
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal object RadioDialogColors {
    val ContainerSolid get() = EnigmaColors.Card
    val Title get() = EnigmaColors.Text
    val Body get() = EnigmaColors.Muted
    val Accent get() = EnigmaColors.Focus
}

internal object RadioDialogDefaults {
    val MenuMaxWidth = 420.dp
    val Shape get() = radioShape(16.dp)
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
            .radioFocusBackground(focused, radioShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = radioOnFocus(focused), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            color = radioOnFocus(focused),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
