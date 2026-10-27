package com.enigmacast.playback

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.enigmacast.domain.model.AutoFrameRateMode
import com.enigmacast.domain.player.FrameRateMatch
import com.enigmacast.domain.player.FrameRateMatch.hz
import com.enigmacast.util.EnigmaLog

/**
 * Automatic frame rate: while a video fills the screen, the display switches to the refresh rate
 * that suits it (window preferredDisplayModeId, API 23+). Same resolution only; when the TV
 * offers no such mode nothing happens. The original mode comes back when the fullscreen player
 * goes away (after a short debounce, so a zap or screen change does not flicker), the app pauses,
 * or the setting is turned off. Mini players and the picture-in-picture inset never report.
 *
 * Main thread only. Players report through [report]; [com.enigmacast.MainActivity] attaches.
 */
object AutoFrameRate {
    private const val SETTLE_MS = 600L
    private const val RESTORE_MS = 1_500L

    private data class Report(val fullscreen: Boolean, val fps: Float, val dvb: Boolean, val order: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val reports = LinkedHashMap<Any, Report>()
    private var order = 0L
    private var activity: Activity? = null
    private var resumed = false

    var mode: AutoFrameRateMode = AutoFrameRateMode.OFF
        set(value) {
            if (field == value) return
            field = value
            EnigmaLog.afr("mode $value")
            schedule(0L)
        }

    /** Mode id before our first switch (to restore), and its resolution (to keep). */
    private var original: FrameRateMatch.Mode? = null
    private var originalPreference = 0
    private var switched = false
    /** Fullscreen session: [START] decides once per session; [ALWAYS] follows [lastTarget]. */
    private var sessionDecided = false
    private var lastTarget: Float? = null

    private val evaluate = Runnable { evaluate() }
    private val restoreLater = Runnable { if (reports.values.none { it.fullscreen }) restore("fullscreen ended") }

    fun attach(activity: Activity) {
        this.activity = activity
    }

    fun detach(activity: Activity) {
        if (this.activity !== activity) return
        restore("activity gone")
        this.activity = null
        resumed = false
    }

    fun onResume() {
        resumed = true
        schedule(SETTLE_MS)
    }

    /** Leaving the app or going to picture-in-picture: back to the original mode right away. */
    fun onPause() {
        resumed = false
        handler.removeCallbacks(evaluate)
        restore("paused")
    }

    /** A player surface: whether it fills the screen and its frame rate (<= 0 unknown, then [dvb] means 50 Hz). */
    fun report(owner: Any, fullscreen: Boolean, fps: Float, dvb: Boolean) {
        val old = reports[owner]
        if (old != null && old.fullscreen == fullscreen && old.fps == fps && old.dvb == dvb) return
        reports[owner] = Report(fullscreen, fps, dvb, if (old?.fullscreen == true && fullscreen) old.order else ++order)
        schedule(SETTLE_MS)
    }

    fun leave(owner: Any) {
        if (reports.remove(owner) != null) schedule(SETTLE_MS)
    }

    private fun schedule(delay: Long) {
        handler.removeCallbacks(evaluate)
        handler.postDelayed(evaluate, delay)
    }

    private fun evaluate() {
        val activity = activity
        val current = reports.values.filter { it.fullscreen }.maxByOrNull { it.order }
        when {
            activity == null || !resumed -> return restore("not visible")
            mode == AutoFrameRateMode.OFF -> return restore("off")
            activity.isInPictureInPictureMode -> return restore("picture-in-picture")
            current == null -> {
                // No fullscreen player: wait a little, a zap or screen change may bring one back.
                handler.removeCallbacks(restoreLater)
                if (switched || sessionDecided) handler.postDelayed(restoreLater, RESTORE_MS)
                return
            }
        }
        handler.removeCallbacks(restoreLater)
        val target = FrameRateMatch.target(current!!.fps, current.dvb) ?: return
        if (mode == AutoFrameRateMode.START && sessionDecided) return
        if (mode == AutoFrameRateMode.ALWAYS && lastTarget == target) return
        sessionDecided = true
        lastTarget = target
        apply(activity!!, target, current.fps)
    }

    private fun apply(activity: Activity, target: Float, fps: Float) {
        val display = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) activity.display else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
        }.getOrNull() ?: return
        val modes = display.supportedModes.map(::toMode)
        val now = toMode(display.mode)
        val base = original ?: now
        val pick = FrameRateMatch.pick(modes, now, base, target)
        if (pick == null) {
            val why = if (FrameRateMatch.fits(now.hz, target)) "already fits" else "no ${base.width}x${base.height} mode for it"
            EnigmaLog.afr("video ${hz(fps)} fps -> ${hz(target)} Hz: stay at ${hz(now.hz)} Hz ($why)")
            return
        }
        val window = activity.window
        val attrs = window.attributes
        if (!switched) {
            original = now
            originalPreference = attrs.preferredDisplayModeId
        }
        EnigmaLog.afr("video ${hz(fps)} fps -> mode ${pick.id} ${pick.width}x${pick.height} @ ${hz(pick.hz)} Hz (was ${hz(now.hz)} Hz)")
        attrs.preferredDisplayModeId = pick.id
        window.attributes = attrs
        switched = true
    }

    private fun restore(reason: String) {
        handler.removeCallbacks(restoreLater)
        sessionDecided = false
        lastTarget = null
        if (!switched) return
        switched = false
        val activity = activity
        if (activity != null) {
            val attrs = activity.window.attributes
            attrs.preferredDisplayModeId = originalPreference
            activity.window.attributes = attrs
        }
        EnigmaLog.afr("restore ${original?.let { "${it.width}x${it.height} @ ${hz(it.hz)} Hz" } ?: "display default"} ($reason)")
        original = null
    }

    private fun toMode(mode: Display.Mode) =
        FrameRateMatch.Mode(mode.modeId, mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
}
