package com.enigmacast.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.ui.theme.EnigmaColors

/**
 * Sidebar lockup. The mark is a vector with no fill behind it, so the glass
 * drawer shows through. [expanded] reveals the wordmark.
 */
@Composable
fun EnigmaCastLogo(
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 4.dp, end = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_enigmacast_mark),
            contentDescription = if (expanded) null else "EnigmaCast",
            tint = Color.Unspecified,
            modifier = Modifier.size(if (expanded) 36.dp else 28.dp),
        )
        if (expanded) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Enigma",
                color = EnigmaColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
            )
            Text(
                text = "Cast",
                color = EnigmaColors.Cyan,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
            )
        }
    }
}
