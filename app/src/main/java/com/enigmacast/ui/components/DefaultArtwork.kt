package com.enigmacast.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.R

/** Colour pairs for the default artwork; a title always gets the same one. */
internal object ArtworkPalette {
    private val pairs = listOf(
        0xFF1B3A5C to 0xFF0A1424,
        0xFF4A2160 to 0xFF140A1E,
        0xFF145049 to 0xFF07161A,
        0xFF5C2A1B to 0xFF1A0C08,
        0xFF2A3A6E to 0xFF0B0F22,
        0xFF5A1F3A to 0xFF1A0811,
    )

    fun indexFor(title: String): Int = Math.floorMod(title.trim().lowercase().hashCode(), pairs.size)

    fun colors(title: String): Pair<Color, Color> = pairs[indexFor(title)].let { Color(it.first) to Color(it.second) }
}

/**
 * Poster stand-in for items without artwork: a gradient (colour chosen from the title), the
 * EnigmaCast mark, and the title over the lower part. Fills whatever box it is given; with
 * [showTitle] false (tiny thumbnails) only the gradient and mark are drawn.
 */
@Composable
fun DefaultArtwork(title: String, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val (top, bottom) = remember(title) { ArtworkPalette.colors(title) }
    BoxWithConstraints(modifier.background(Brush.verticalGradient(listOf(top, bottom)))) {
        val small = maxWidth < 70.dp
        Image(
            painter = painterResource(R.drawable.ic_enigmacast_mark),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(if (showTitle) Alignment.TopCenter else Alignment.Center)
                .padding(top = if (showTitle) maxHeight * 0.14f else 0.dp)
                .size(minOf(maxWidth, maxHeight) * if (showTitle) 0.42f else 0.55f)
                .alpha(0.85f),
        )
        if (showTitle && title.isNotBlank()) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 4.dp, vertical = if (small) 3.dp else 6.dp),
            ) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = if (small) 9.sp else 12.sp,
                    lineHeight = if (small) 11.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
