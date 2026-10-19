package com.enigmacast.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.enigmacast.domain.model.OsdSkinId
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType
import com.enigmacast.ui.theme.OsdLayout
import com.enigmacast.ui.theme.Skin
import com.enigmacast.ui.theme.Skins
import com.enigmacast.ui.theme.edgeLeft
import com.enigmacast.ui.theme.skinShape

/**
 * Settings › Appearance › GUI skins: one tile per skin with a small preview of its colours
 * and banner layout. OK applies the skin at once; left/right walks the tiles.
 */
@Composable
fun SkinChooser(selected: OsdSkinId, onSelect: (OsdSkinId) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        // Equal tiles (seven) that share the content width, so all of them are always on screen.
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Skins.all.forEachIndexed { index, skin ->
            SkinTile(
                skin,
                selected = skin.id == selected,
                onClick = { onSelect(skin.id) },
                modifier = Modifier.weight(1f).then(if (index == 0) Modifier.edgeLeft() else Modifier),
            )
        }
    }
}

@Composable
private fun SkinTile(skin: Skin, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = skinShape(10.dp)
    Column(
        modifier
            .scale(if (focused) 1.04f else 1f)
            .background(if (focused) EnigmaColors.Focus else if (selected) EnigmaColors.Selected else EnigmaColors.Raised, shape)
            .border(2.dp, if (focused) EnigmaColors.OnFocus else if (selected) EnigmaColors.Accent else EnigmaColors.CardBorder, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
    ) {
        SkinThumbnail(skin, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(skin.id.label),
                color = if (focused) EnigmaColors.OnFocus else EnigmaColors.Text,
                fontSize = 13.sp,
                fontWeight = EnigmaType.Strong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = if (focused) EnigmaColors.OnFocus else EnigmaColors.Accent, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** A miniature screen in [skin]'s own colours (not the active skin's), with its banner layout. */
@Composable
internal fun SkinThumbnail(skin: Skin, modifier: Modifier = Modifier) {
    val p = skin.palette
    val shape = skinShape(8.dp, skin)
    Box(modifier.clip(shape).background(p.canvas)) {
        // Glass skins: their photo, decoded small by Coil off the main thread.
        skin.glass?.let { glass ->
            coil3.compose.AsyncImage(
                model = glass.backdrop,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        // A menu card with one focused row.
        Column(Modifier.padding(6.dp).width(56.dp).background(p.card, skinShape(4.dp, skin)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.fillMaxWidth().height(6.dp).background(p.focus, skinShape(2.dp, skin)))
            Box(Modifier.fillMaxWidth(0.8f).height(4.dp).background(p.muted.copy(alpha = 0.5f)))
            Box(Modifier.fillMaxWidth(0.6f).height(4.dp).background(p.muted.copy(alpha = 0.5f)))
        }
        Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(10.dp).background(p.accent, CircleShape))
        val bar = Modifier.align(Alignment.BottomCenter)
        when (skin.osd) {
            OsdLayout.PLI_BAR -> Box(
                bar.fillMaxWidth().height(26.dp)
                    .background(Brush.verticalGradient(listOf(p.osdFill.copy(alpha = 0f), p.osdFill.copy(alpha = 0.82f), p.osdFill.copy(alpha = 0.82f)))),
            ) {
                // Yellow clock left, then name, the bronze progress bar and two event lines.
                Box(Modifier.padding(start = 5.dp, top = 9.dp).width(12.dp).height(4.dp).background(p.highlight))
                Box(Modifier.padding(start = 22.dp, top = 9.dp).width(36.dp).height(4.dp).background(p.text))
                Box(Modifier.padding(start = 22.dp, top = 15.dp).width(80.dp).height(2.dp).background(androidx.compose.ui.graphics.Color(0xFF4C3811)))
                Box(Modifier.padding(start = 22.dp, top = 19.dp).width(60.dp).height(2.dp).background(p.muted))
            }
            OsdLayout.ORBIT -> Box(
                bar.padding(bottom = 5.dp).fillMaxWidth(0.78f).height(20.dp)
                    .background(Brush.linearGradient(listOf(p.osdFill.copy(alpha = 0.8f), p.osdFillEnd.copy(alpha = 0.8f))), CircleShape)
                    .border(1.dp, p.osdEdge, CircleShape),
            ) {
                Box(Modifier.padding(start = 3.dp, top = 3.dp).size(14.dp).border(1.dp, p.highlight, CircleShape))
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth(0.5f).height(1.dp).background(p.highlight))
            }
            OsdLayout.WAVE -> Box(
                bar.fillMaxWidth().height(24.dp)
                    .background(Brush.verticalGradient(listOf(p.osdFill.copy(alpha = 0f), p.osdFill.copy(alpha = 0.85f), p.osdFillEnd.copy(alpha = 0.9f)))),
            ) {
                Box(Modifier.padding(start = 26.dp, top = 15.dp).width(70.dp).height(2.dp).background(Brush.horizontalGradient(listOf(p.accent, p.highlight))))
            }
            OsdLayout.FLAT -> Row(bar.padding(4.dp).fillMaxWidth().height(20.dp).background(p.osdFill)) {
                Box(Modifier.width(18.dp).height(20.dp).background(p.accent))
                Box(Modifier.padding(start = 5.dp, top = 12.dp).width(70.dp).height(2.dp).background(p.accent))
            }
            else -> Box(
                bar.padding(5.dp).fillMaxWidth().height(20.dp)
                    .background(
                        if (skin.osd == OsdLayout.GLASS) Brush.linearGradient(listOf(p.osdFill, p.osdFillEnd)) else Brush.linearGradient(listOf(p.osdFill, p.osdFill)),
                        skinShape(6.dp, skin),
                    )
                    .border(1.dp, if (skin.osd == OsdLayout.MINIMAL) p.osdFill else p.osdEdge, skinShape(6.dp, skin)),
            ) {
                Box(Modifier.padding(start = 6.dp, top = 12.dp).width(70.dp).height(3.dp).background(Brush.horizontalGradient(listOf(p.accent, p.highlight))))
            }
        }
    }
}
