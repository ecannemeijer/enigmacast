package com.enigmacast.ui.animation

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import androidx.compose.ui.MotionDurationScale
import com.enigmacast.domain.device.FastModePolicy

/**
 * The window's animation clock. Every Compose animation (focus scroll, menu, fades, animate*AsState)
 * reads this scale when it starts; 0 makes it jump to its end at once. Fast mode sets 0.
 */
object FastMotion : MotionDurationScale {
    @Volatile var fast: Boolean = false
    @Volatile private var system: Float = 1f

    /** Android's animator duration scale, as last read. */
    val systemScale: Float get() = system

    override val scaleFactor: Float get() = if (fast) 0f else system

    fun readSystem(context: Context) {
        system = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
    }

    fun isLowEnd(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return FastModePolicy.isLowEnd(
            lowRamDevice = am?.isLowRamDevice == true,
            memoryClassMb = am?.memoryClass ?: 0,
            cores = Runtime.getRuntime().availableProcessors(),
        )
    }
}
