package com.enigmacast.ui.components

import com.enigmacast.ui.theme.skinShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.enigmacast.ui.animation.LocalMotionSpec
import com.enigmacast.ui.animation.focusTween
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.EnigmaType

private val PanelShape get() = skinShape(16.dp)
private val CardShape get() = skinShape(14.dp)

@Composable
fun Modifier.enigmaGlass(corner: Dp = 16.dp): Modifier {
    val spec = LocalMotionSpec.current
    val shape = RoundedCornerShape(corner)
    return this
        .then(
            if (spec.shadows) {
                Modifier.shadow(12.dp, shape, ambientColor = EnigmaColors.Accent.copy(alpha = 0.18f), spotColor = EnigmaColors.Cyan.copy(alpha = 0.16f))
            } else {
                Modifier
            },
        )
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xD91A2740), Color(0xCC080D1C))))
        .border(1.dp, EnigmaColors.GlassEdge, shape)
}

@Composable
fun EnigmaGlassPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.enigmaGlass().padding(contentPadding), content = content)
}

@Composable
fun EnigmaGlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val spec = LocalMotionSpec.current
    val scale by animateFloatAsState(
        targetValue = if (focused && spec.animate) 1.08f else 1f,
        animationSpec = spec.focusTween(),
        label = "glassButton",
    )
    val shape = skinShape(12.dp)
    Text(
        text = text,
        color = if (focused) EnigmaColors.OnFocus else EnigmaColors.Text,
        fontSize = EnigmaType.Body,
        fontWeight = EnigmaType.Strong,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (focused) EnigmaColors.Focus else EnigmaColors.Raised)
            .border(1.dp, if (focused && spec.glow) EnigmaColors.Cyan else EnigmaColors.CardBorder, shape)
            .onKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (confirm && event.type == KeyEventType.KeyUp) onClick()
                confirm
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    )
}

@Composable
fun EnigmaFocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val spec = LocalMotionSpec.current
    val scale by animateFloatAsState(
        targetValue = if (focused && spec.animate) spec.cardScale else 1f,
        animationSpec = tween(spec.cardMs),
        label = "focusCard",
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (focused && spec.shadows) {
                    Modifier.shadow(16.dp, CardShape, ambientColor = EnigmaColors.Accent.copy(alpha = 0.35f), spotColor = EnigmaColors.Cyan.copy(alpha = 0.28f))
                } else {
                    Modifier
                },
            )
            .clip(CardShape)
            .background(if (focused) EnigmaColors.Selected else EnigmaColors.Card)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) EnigmaColors.Cyan else EnigmaColors.CardBorder,
                shape = CardShape,
            )
            .onKeyEvent { event ->
                val confirm = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (confirm && event.type == KeyEventType.KeyUp) onClick()
                confirm
            }
            .focusable(interactionSource = interaction)
            .padding(12.dp),
        content = { content(focused) },
    )
}

@Composable
fun EnigmaAnimatedIcon(
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val spec = LocalMotionSpec.current
    val scale by animateFloatAsState(
        targetValue = if (selected && spec.animate) 1.12f else 1f,
        animationSpec = tween(spec.focusMs),
        label = "iconScale",
    )
    Icon(
        icon,
        contentDescription = contentDescription,
        modifier = modifier
            .size(18.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    )
}

@Composable
fun EnigmaProgramProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    emphasized: Boolean = true,
) {
    val spec = LocalMotionSpec.current
    val target = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (spec.animate) spec.focusMs else 0),
        label = "programProgress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(skinShape(2.dp))
            .background(EnigmaColors.Raised),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(4.dp)
                .background(
                    if (emphasized) {
                        Brush.horizontalGradient(listOf(EnigmaColors.Accent, EnigmaColors.Cyan))
                    } else {
                        Brush.horizontalGradient(listOf(EnigmaColors.Muted, EnigmaColors.Muted))
                    },
                ),
        )
    }
}

@Composable
fun EnigmaChannelCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    EnigmaFocusCard(onClick = onClick, modifier = modifier) {
        Column {
            Text(
                title,
                color = EnigmaColors.Text,
                fontSize = 15.sp,
                fontWeight = EnigmaType.Strong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = EnigmaColors.Muted,
                    fontSize = EnigmaType.Label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (progress != null) {
                EnigmaProgramProgress(progress, Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
fun EnigmaLoadingIndicator(modifier: Modifier = Modifier) {
    val spec = LocalMotionSpec.current
    if (!spec.animate) {
        Box(
            modifier
                .size(16.dp)
                .clip(skinShape(8.dp))
                .background(EnigmaColors.Accent),
        )
        return
    }
    val angle by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "loadingSpin",
    )
    Box(
        modifier
            .size(18.dp)
            .graphicsLayer { rotationZ = angle }
            .border(2.dp, Brush.sweepGradient(listOf(Color.Transparent, EnigmaColors.Cyan, EnigmaColors.Accent)), skinShape(9.dp)),
    )
}
