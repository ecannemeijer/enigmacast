package com.enigmacast.domain.osd

import java.util.zip.CRC32
import kotlin.math.abs

enum class GrabFormat(val wire: String) {
    PNG("png"),
    JPG("jpg"),
}

enum class GrabMode(val wire: String) {
    OSD("osd"),
    ALL("all"),
    VIDEO("video"),
}

enum class OsdPollState {
    HIDDEN,
    ACTIVE_FAST,
    ACTIVE_SLOW,
    IDLE,
}

enum class OsdScaleMode {
    /** OSD framebuffer and the video surface share the same aspect, so stretch to the surface. */
    FILL_BOUNDS,
    /** Aspects differ. Scale uniformly and center so the GUI is not distorted. */
    FIT,
}

data class OsdConfig(
    val fastBurstMs: List<Long> = listOf(0, 50, 100, 175, 275, 400, 600, 900),
    val unchangedToSlowMs: Long = 750,
    val unchangedToIdleMs: Long = 4_000,
    val slowPollMs: Long = 400,
    val idlePollMs: Long = 2_000,
    val hiddenPollMs: Long = 2_000,
    val fastPolling: Boolean = true,
)

data class GrabbedImage(
    val bytes: ByteArray,
    val contentType: String?,
    val httpStatus: Int,
    val durationMs: Long,
)

data class OsdAnalysis(
    val hash: Long,
    val width: Int,
    val height: Int,
    val isEmpty: Boolean,
)

data class OsdUpdate(
    val hash: Long,
    val width: Int,
    val height: Int,
    val isEmpty: Boolean,
    val durationMs: Long,
    val state: OsdPollState,
)

fun interface OsdAnalyzer {
    fun analyze(bytes: ByteArray): OsdAnalysis
}

object OsdHasher {
    fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }
}

object PngInspector {
    private val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < signature.size) return false
        return signature.indices.all { bytes[it] == signature[it] }
    }

    fun isJpeg(bytes: ByteArray): Boolean {
        return bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
    }

    /** PNG color types 4 and 6 carry an alpha channel. Offset 25 is the IHDR color type. */
    fun hasAlphaChannel(bytes: ByteArray): Boolean {
        if (!isPng(bytes) || bytes.size < 26) return false
        val colorType = bytes[25].toInt() and 0xFF
        return colorType == 4 || colorType == 6
    }
}

/**
 * The Enigma2 OSD is the GUI framebuffer (often 1280x720 or 1920x1080), not the
 * active video content rectangle. A 4:3 broadcast is letterboxed inside that
 * framebuffer, while menus still use the full framebuffer. The overlay therefore
 * tracks the surface, which is the same coordinate system the receiver GUI uses.
 * Fill the surface only when both aspects match. Otherwise fit without stretching.
 */
object OsdScale {
    fun choose(osdWidth: Int, osdHeight: Int, viewWidth: Int, viewHeight: Int): OsdScaleMode {
        if (osdWidth <= 0 || osdHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return OsdScaleMode.FIT
        }
        val osdAspect = osdWidth.toFloat() / osdHeight.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val delta = abs(osdAspect - viewAspect) / viewAspect
        return if (delta < 0.02f) OsdScaleMode.FILL_BOUNDS else OsdScaleMode.FIT
    }
}

class OsdStateMachine(
    private val config: OsdConfig,
    private val now: () -> Long,
) {
    var state: OsdPollState = OsdPollState.IDLE
        private set

    private var lastChangeAt: Long = now()
    private var lastRemoteAt: Long = 0
    private var burstIndex: Int = -1

    fun onRemoteInput() {
        if (!config.fastPolling) return
        state = OsdPollState.ACTIVE_FAST
        val time = now()
        lastRemoteAt = time
        lastChangeAt = time
        burstIndex = 0
    }

    fun onSample(changed: Boolean, empty: Boolean) {
        val time = now()
        if (empty) {
            state = OsdPollState.HIDDEN
            burstIndex = -1
            return
        }
        if (changed) {
            lastChangeAt = time
            state = OsdPollState.ACTIVE_FAST
            return
        }
        val quiet = time - lastChangeAt
        state = when {
            quiet >= config.unchangedToIdleMs -> OsdPollState.IDLE
            quiet >= config.unchangedToSlowMs -> OsdPollState.ACTIVE_SLOW
            state == OsdPollState.HIDDEN -> OsdPollState.ACTIVE_FAST
            else -> state
        }
    }

    fun nextDelayMs(): Long {
        if (state == OsdPollState.ACTIVE_FAST && burstIndex in config.fastBurstMs.indices) {
            val target = config.fastBurstMs[burstIndex]
            burstIndex += 1
            val elapsed = now() - lastRemoteAt
            return (target - elapsed).coerceAtLeast(0)
        }
        return when (state) {
            OsdPollState.ACTIVE_FAST -> config.slowPollMs
            OsdPollState.ACTIVE_SLOW -> config.slowPollMs
            OsdPollState.IDLE -> config.idlePollMs
            OsdPollState.HIDDEN -> config.hiddenPollMs
        }
    }
}
