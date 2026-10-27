package com.enigmacast.live

import com.enigmacast.domain.tuner.TunerVerdict

/** How long a zap still waits for the receiver to free the previous stream's tuner. */
object ZapRelease {
    /**
     * The release wait left after the tuner check, which already ran for [elapsedMs] since the
     * old stream was closed. No wait at all when another tuner is free besides the one this
     * device just let go of (the check leaves this device's own streams out), because the new
     * stream does not need that tuner.
     */
    fun remainingWaitMs(verdict: TunerVerdict, elapsedMs: Long, releaseMs: Long): Long {
        if (verdict is TunerVerdict.Free && verdict.free >= 2) return 0L
        return (releaseMs - elapsedMs).coerceIn(0L, releaseMs)
    }
}
