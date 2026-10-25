package com.enigmacast.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.enigmacast.R
import com.enigmacast.domain.player.VodTitleCard
import com.enigmacast.domain.player.VodTitleInfo
import kotlinx.coroutines.delay

private val TitleShadow = Shadow(Color(0xAA000000), Offset(0f, 2f), 6f)

/** Dark corner behind the title block: strongest at the top left, gone by the middle of the screen. */
private val Corner = listOf(Color(0xD9000000), Color(0x8C000000), Color(0x33000000), Color.Transparent)

/**
 * The Netflix-style title block at the top left of the film/episode player: TMDB logo (or the
 * name as text), "S1:E3  Episode name" and the plot (max 3 lines) on a subtle dark corner.
 * Shown with the VOD OSD (pause, seeking, any key) and for a few seconds when [startKey]
 * changes (a new film or episode starts). Not focusable: D-pad focus stays in the OSD.
 */
@Composable
fun VodTitleOverlay(
    controller: VodOsdController,
    info: VodTitleInfo?,
    startKey: Any?,
    modifier: Modifier = Modifier,
) {
    var peek by remember(startKey) { mutableStateOf(startKey != null) }
    LaunchedEffect(startKey) {
        if (startKey != null) { delay(VodTitleCard.START_PEEK_MS); peek = false }
    }
    // Once the OSD itself has been up, the block hides with it.
    LaunchedEffect(controller.visible) { if (controller.visible) peek = false }
    AnimatedVisibility(
        visible = info != null && (controller.visible || peek),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        if (info != null) VodTitleBlock(info)
    }
}

@Composable
fun VodTitleBlock(info: VodTitleInfo, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth(0.66f)
            .drawBehind {
                drawRect(Brush.radialGradient(Corner, center = Offset.Zero, radius = size.width * 1.05f))
            },
    ) {
        Column(Modifier.padding(start = 56.dp, top = 44.dp, end = 32.dp, bottom = 64.dp).widthIn(max = 640.dp)) {
            var logoFailed by remember(info.logoUrl) { mutableStateOf(false) }
            val logo = info.logoUrl
            if (logo != null && !logoFailed) {
                AsyncImage(
                    model = logo,
                    contentDescription = info.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    onError = { logoFailed = true },
                    modifier = Modifier.height(104.dp).widthIn(max = 440.dp),
                )
            } else {
                Text(
                    info.title,
                    style = TextStyle(shadow = TitleShadow),
                    color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (info.isEpisode) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.osd_episode_code, info.season ?: 0, info.episode ?: 0),
                        style = TextStyle(shadow = TitleShadow),
                        color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    if (info.episodeName.isNotBlank()) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            info.episodeName,
                            style = TextStyle(shadow = TitleShadow),
                            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (info.overview.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    info.overview,
                    style = TextStyle(shadow = TitleShadow, lineHeight = 22.sp),
                    color = Color(0xE6FFFFFF), fontSize = 15.sp,
                    maxLines = VodTitleCard.OVERVIEW_LINES, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(max = 72.dp),
                )
            }
        }
    }
}
