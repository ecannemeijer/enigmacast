package com.enigmacast.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * 0..1, once per [periodMs], straight from the frame clock. Unlike infinite transitions and
 * Material's indeterminate indicators this ignores the animation duration scale, so loading
 * indicators keep moving in Fast mode (scale 0) and with system animations switched off.
 */
@Composable
fun rememberLoadingPhase(periodMs: Long): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMs) {
        val start = withFrameNanos { it }
        val period = periodMs * 1_000_000L
        while (true) {
            withFrameNanos { now -> phase.floatValue = ((now - start) % period).toFloat() / period }
        }
    }
    return phase
}

/** Indeterminate round spinner that always turns, also in Fast mode. */
@Composable
fun EnigmaSpinner(color: Color, modifier: Modifier = Modifier, strokeWidth: Dp = 3.dp) {
    val spin = rememberLoadingPhase(1_000L)
    val grow = rememberLoadingPhase(1_600L)
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val sweep = 40f + 230f * (0.5f + 0.5f * sin(grow.value * 2 * Math.PI).toFloat())
        val inset = stroke / 2
        drawArc(
            color = color,
            startAngle = spin.value * 360f - 90f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/** Indeterminate progress bar that always moves, also in Fast mode. */
@Composable
fun EnigmaIndeterminateBar(modifier: Modifier = Modifier, color: Color, trackColor: Color) {
    val phase = rememberLoadingPhase(1_400L)
    Canvas(modifier) {
        drawRect(trackColor)
        val w = size.width * 0.35f
        val x = -w + (size.width + w) * phase.value
        drawRect(color, topLeft = Offset(x.coerceAtLeast(0f), 0f), size = Size((x + w).coerceAtMost(size.width) - x.coerceAtLeast(0f), size.height))
    }
}
