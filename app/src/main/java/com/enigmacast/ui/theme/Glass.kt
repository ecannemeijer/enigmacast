package com.enigmacast.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The glass skins' photo, decoded once off the main thread: [sharp] for page backgrounds and
 * [soft], a small blurred copy that frosted panels show through. Released when a flat skin is
 * chosen, so the flat skins keep their memory.
 */
object GlassBackdrop {
    var sharp: ImageBitmap? by mutableStateOf(null)
        private set
    var soft: ImageBitmap? by mutableStateOf(null)
        private set
    private var loaded = 0

    suspend fun load(context: Context, res: Int) {
        if (res == loaded && sharp != null) return
        val (big, small) = withContext(Dispatchers.Default) {
            val options = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeResource(context.resources, res, options) ?: return@withContext null
            // Uploads the texture now, not on the first frame that draws it.
            bitmap.prepareToDraw()
            bitmap to blurred(bitmap)
        } ?: return
        loaded = res
        sharp = big.asImageBitmap()
        soft = small.asImageBitmap()
    }

    fun release() {
        loaded = 0
        sharp = null
        soft = null
    }

    /**
     * The real blur of the backdrop, computed once: shrink to [SOFT_WIDTH] px and box-blur three
     * times (≈ a Gaussian). Drawn scaled up with bilinear filtering it is a smooth frost on every
     * API level, at no per-frame cost (a RenderEffect per panel would blur again every frame).
     */
    private fun blurred(source: Bitmap): Bitmap {
        val h = SOFT_WIDTH * source.height / source.width
        val small = Bitmap.createScaledBitmap(source, SOFT_WIDTH, h, true)
        val pixels = IntArray(SOFT_WIDTH * h)
        small.getPixels(pixels, 0, SOFT_WIDTH, 0, 0, SOFT_WIDTH, h)
        val out = GlassBlur.boxBlur(pixels, SOFT_WIDTH, h, radius = 3, passes = 3)
        return Bitmap.createBitmap(out, SOFT_WIDTH, h, Bitmap.Config.ARGB_8888).also { it.prepareToDraw() }
    }

    private const val SOFT_WIDTH = 240
}

/** Separable box blur on ARGB pixels; pure, so it is unit tested. */
object GlassBlur {
    fun boxBlur(src: IntArray, width: Int, height: Int, radius: Int, passes: Int): IntArray {
        val a = src.copyOf()
        val b = IntArray(a.size)
        repeat(passes) {
            pass(a, b, width, height, radius, horizontal = true)
            pass(b, a, width, height, radius, horizontal = false)
        }
        return a
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val window = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + c] else src[c * w + line]
            }
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = at(i)
                sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (i in 0 until len) {
                val idx = if (horizontal) line * w + i else i * w + line
                dst[idx] = ((sa / window) shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
                val add = at(i + r + 1)
                val sub = at(i - r)
                sa += (add ushr 24) - (sub ushr 24)
                sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sb += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    /**
     * The part of an image shown "cover" over a [screen] that lies under a box at [pos] of [box]
     * size, in image pixels (offset, size), clamped to the image.
     */
    fun sourceRect(image: IntSize, screen: IntSize, pos: Offset, box: IntSize): Pair<IntOffset, IntSize> {
        if (image.width <= 0 || screen.width <= 0 || screen.height <= 0) return IntOffset.Zero to IntSize.Zero
        val scale = maxOf(screen.width / image.width.toFloat(), screen.height / image.height.toFloat())
        val ox = (screen.width - image.width * scale) / 2f
        val oy = (screen.height - image.height * scale) / 2f
        val x = ((pos.x - ox) / scale).toInt().coerceIn(0, image.width - 1)
        val y = ((pos.y - oy) / scale).toInt().coerceIn(0, image.height - 1)
        val w = (box.width / scale).toInt().coerceIn(1, image.width - x)
        val h = (box.height / scale).toInt().coerceIn(1, image.height - y)
        return IntOffset(x, y) to IntSize(w, h)
    }
}

/** Where on the screen a node is; written on layout, read only while drawing (no recomposition). */
private class ScreenBox {
    var pos by mutableStateOf(Offset.Zero)
}

private fun DrawScope.drawScreenPart(image: ImageBitmap, screen: IntSize, pos: Offset) {
    val (offset, part) = GlassBlur.sourceRect(IntSize(image.width, image.height), screen, pos, IntSize(size.width.toInt(), size.height.toInt()))
    if (part.width <= 0) return
    drawImage(image, offset, part, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
}

/**
 * The page background of the active skin: the canvas colour ([fallback] if given), or for a glass
 * skin the photo, anchored to the screen so stacked screens line up, with the skin's dim on top.
 */
fun Modifier.skinCanvas(fallback: Color? = null): Modifier = composed {
    val glass = ActiveSkin.current.glass
    if (glass == null) return@composed background(fallback ?: EnigmaColors.Canvas)
    val box = remember { ScreenBox() }
    val metrics = LocalContext.current.resources.displayMetrics
    val screen = IntSize(metrics.widthPixels, metrics.heightPixels)
    val canvas = EnigmaColors.Canvas
    onGloballyPositioned { box.pos = it.positionOnScreen() }
        .drawBehind {
            drawRect(canvas)
            GlassBackdrop.sharp?.let { drawScreenPart(it, screen, box.pos) }
            drawRect(glass.dim)
        }
}

/** A pane inside a page that already drew [skinCanvas]: its canvas colour, nothing on glass. */
fun Modifier.skinPane(): Modifier = composed {
    if (ActiveSkin.current.glass == null) background(EnigmaColors.Canvas) else this
}

/**
 * A surface: [color] with the usual hairline on flat skins (exactly the old look); frosted glass
 * on glass skins: the blurred photo under this box, tinted, a soft top highlight and a white
 * hairline. [focused] glass is brighter, with an accent edge and glow. Only for surfaces over the
 * app background; over video use [glassSheen] (the photo is not behind the player).
 */
fun Modifier.skinSurface(color: Color, shape: Shape, focused: Boolean = false, border: Boolean = true): Modifier = composed {
    val glass = ActiveSkin.current.glass
    if (glass == null) {
        val base = background(color, shape)
        return@composed if (border) base.border(1.dp, EnigmaColors.CardBorder, shape) else base
    }
    val box = remember { ScreenBox() }
    val metrics = LocalContext.current.resources.displayMetrics
    val screen = IntSize(metrics.widthPixels, metrics.heightPixels)
    val tint = if (focused) glass.focusTint else glass.tint
    val canvas = EnigmaColors.Canvas
    (if (focused) shadow(16.dp, shape, clip = false, ambientColor = glass.glow, spotColor = glass.glow) else this)
        .onGloballyPositioned { box.pos = it.positionOnScreen() }
        .drawBehind {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
            clipPath(path) {
                drawRect(canvas)
                GlassBackdrop.soft?.let { drawScreenPart(it, screen, box.pos) }
                drawRect(tint)
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (focused) 0.16f else 0.09f), 0.55f to Color.Transparent))
            }
        }
        .border(if (focused) 2.dp else 1.dp, if (focused) glass.glow.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.18f), shape)
}

/**
 * Glass without the photo, for panels over video or over other content: a soft top highlight and
 * a white hairline on top of the panel's own translucent fill. Nothing on flat skins.
 */
fun Modifier.glassSheen(shape: Shape): Modifier = composed {
    if (ActiveSkin.current.glass == null) return@composed this
    drawBehind {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
        clipPath(path) { drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.5f to Color.Transparent)) }
    }.border(1.dp, Color.White.copy(alpha = 0.20f), shape)
}
