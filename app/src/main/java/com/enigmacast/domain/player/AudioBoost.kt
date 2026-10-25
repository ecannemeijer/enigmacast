package com.enigmacast.domain.player

import com.enigmacast.domain.tuner.NowEvents
import kotlin.math.pow

/**
 * Live TV audio boost for channels that are quieter than the rest: Off and five steps of
 * [STEP_DB] dB each (+3 … +15 dB). The level is kept per channel (see [satelliteKey] and
 * [iptvKey]) and synced to the sync server.
 */
object AudioBoost {
    const val MAX = 5
    const val STEP_DB = 3

    fun clamp(level: Int): Int = level.coerceIn(0, MAX)

    /** One press of the quick bar button: Off → 1 → … → 5 → Off. */
    fun next(level: Int): Int = if (clamp(level) >= MAX) 0 else clamp(level) + 1

    fun db(level: Int): Int = clamp(level) * STEP_DB

    /** Linear amplitude factor of [level] (1.0 when Off). */
    fun gain(level: Int): Float = 10.0.pow(db(level) / 20.0).toFloat()

    /** Satellite / own tuner: the normalised service reference, so every receiver and device agrees. */
    fun satelliteKey(reference: String?): String {
        val ref = reference?.trim().orEmpty()
        if (ref.isBlank()) return ""
        return "e2:" + NowEvents.key(ref)
    }

    /**
     * IPTV live: the provider (multi-provider id, else the playlist account key) plus the
     * stream id, so the same stream number at two providers keeps its own level.
     */
    fun iptvKey(provider: String, streamId: String): String {
        if (provider.isBlank() || streamId.isBlank()) return ""
        return "iptv:$provider:$streamId"
    }
}
