package com.enigmacast.domain.player

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Gain plus a look-ahead-free peak limiter for interleaved float frames (range -1..1).
 *
 * The gain glides to [target] over about 30 ms, so a level change does not click. Peaks
 * that would go over [CEILING] are pulled down at once (instant attack, so no sample ever
 * clips) and the reduction is released slowly (about 300 ms), which avoids pumping.
 * At unity gain with no reduction pending the samples pass untouched.
 */
class BoostLimiter(sampleRate: Int) {
    var target: Float = 1f
    private var gain = 1f
    private var reduction = 1f
    private val glide = coefficient(sampleRate, 0.030)
    private val release = coefficient(sampleRate, 0.300)

    /** True while the limiter changes nothing (unity gain, nothing to release). */
    val idle: Boolean get() = target == 1f && gain == 1f && reduction == 1f

    /** Processes [frames] interleaved frames of [channels] samples in place. */
    fun process(samples: FloatArray, channels: Int, frames: Int = samples.size / channels.coerceAtLeast(1)) {
        if (channels <= 0) return
        if (idle) return
        var index = 0
        repeat(frames) {
            gain += (target - gain) * glide
            if (abs(gain - target) < 1e-4f) gain = target
            var peak = 0f
            for (c in 0 until channels) peak = maxOf(peak, abs(samples[index + c]))
            val wanted = peak * gain
            val needed = if (wanted > CEILING) CEILING / wanted else 1f
            reduction += (1f - reduction) * release
            if (reduction > 0.99999f) reduction = 1f
            reduction = min(reduction, needed)
            val applied = gain * reduction
            for (c in 0 until channels) {
                samples[index + c] = (samples[index + c] * applied).coerceIn(-1f, 1f)
            }
            index += channels
        }
    }

    fun reset() {
        gain = target
        reduction = 1f
    }

    companion object {
        /** -1 dBFS: room for the resampler and the TV's own processing. */
        const val CEILING = 0.89125f

        private fun coefficient(sampleRate: Int, seconds: Double): Float =
            (1.0 - exp(-1.0 / (sampleRate.coerceAtLeast(1) * seconds))).toFloat()
    }
}
