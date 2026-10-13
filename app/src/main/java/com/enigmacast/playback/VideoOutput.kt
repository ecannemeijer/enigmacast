package com.enigmacast.playback

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.enigmacast.domain.model.VideoCompatibility
import com.enigmacast.domain.model.VideoDecoderMode

/**
 * Devices whose hardware video path garbles the picture when frames go through a TextureView
 * (GPU composition). Amlogic SoCs (Xiaomi TV Stick 4K = S905Y4, Mi Box S = S905X) hand decoded
 * frames to their own video layer; through a TextureView some firmwares read that buffer with the
 * wrong stride/colour format, which shows as green/purple horizontal lines over the whole picture.
 * A SurfaceView keeps the frames on the hardware overlay, which is what TiviMate and Netflix use.
 */
object VideoQuirks {
    fun isKnownGarbler(manufacturer: String, brand: String, model: String, hardware: String, soc: String): Boolean {
        val all = listOf(manufacturer, brand, model, hardware, soc).map { it.lowercase() }
        val amlogic = all.any { it.contains("amlogic") } ||
            hardware.lowercase().let { it.startsWith("amlogic") || it.matches(Regex("^(g12[ab]|sm1|sc2|s4|t7|gxl|gxm)$")) } ||
            soc.lowercase().matches(Regex("^s9\\d\\d.*"))
        val xiaomi = manufacturer.equals("xiaomi", true) || brand.equals("xiaomi", true)
        val miStick = model.lowercase().let { it.startsWith("mitv-") || it.startsWith("mibox") || it.contains("afkr") }
        return amlogic || (xiaomi && miStick)
    }

    val thisDevice: Boolean by lazy {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL + " " + Build.SOC_MANUFACTURER else ""
        isKnownGarbler(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty(), Build.MODEL.orEmpty(), Build.HARDWARE.orEmpty(), soc)
    }
}

/** The video output the views and the player use. */
object VideoOutput {
    fun compatibilityActive(mode: VideoCompatibility, quirk: Boolean): Boolean = when (mode) {
        VideoCompatibility.ON -> true
        VideoCompatibility.OFF -> false
        VideoCompatibility.AUTO -> quirk
    }

    /** TextureView only when compatibility is switched off explicitly; SurfaceView otherwise. */
    fun useSurfaceView(mode: VideoCompatibility): Boolean = mode != VideoCompatibility.OFF

    /** Read by the video views; changing it recreates the view with the other surface type. */
    var surfaceView by mutableStateOf(true)

    /** Hardware decoders first, software last; or software only / hardware only. */
    fun order(mode: VideoDecoderMode, names: List<Pair<String, Boolean>>): List<String> = when (mode) {
        VideoDecoderMode.AUTO -> names.sortedBy { !it.second }.map { it.first }
        VideoDecoderMode.HARDWARE -> names.filter { it.second }.ifEmpty { names }.map { it.first }
        VideoDecoderMode.SOFTWARE -> names.filter { !it.second }.ifEmpty { names }.map { it.first }
    }
}
