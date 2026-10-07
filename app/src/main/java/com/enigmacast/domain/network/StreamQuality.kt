package com.enigmacast.domain.network

import kotlinx.serialization.Serializable

/** What the receiver sends: the original broadcast, a transcoded copy, or a choice made per network. */
@Serializable
enum class StreamProfile { AUTO, DIRECT, TRANSCODED }

/** Transcoder settings; bitrates fit a typical upload of a home connection. */
@Serializable
enum class TranscodePreset(val width: Int, val height: Int, val bitrateKbps: Int) {
    HIGH(1280, 720, 2_500),
    MEDIUM(1024, 576, 1_200),
    LOW(640, 360, 600),
}

/** Quality steps from best to lowest. */
enum class StreamLevel(val preset: TranscodePreset?) {
    DIRECT(null),
    HIGH(TranscodePreset.HIGH),
    MEDIUM(TranscodePreset.MEDIUM),
    LOW(TranscodePreset.LOW),
    ;

    val transcoded: Boolean get() = preset != null

    companion object {
        fun of(preset: TranscodePreset): StreamLevel = entries.first { it.preset == preset }
    }
}

/** What the choice depends on. */
data class QualityInputs(
    val profile: StreamProfile,
    val preset: TranscodePreset,
    /** From `/api/about` (`transcoding`): an encoder plus the TranscodingSetup plugin. */
    val transcodingSupported: Boolean,
    /** Remote profile or tunnel: the stream crosses the internet. At home it never does. */
    val remote: Boolean,
)

/**
 * Direct always sends the original stream. Transcoded uses the chosen preset when the
 * receiver can transcode, else falls back to direct. Auto always sends the original at home
 * (the home network has the bandwidth, and the transcoding port may not even be open), and a
 * transcoded copy over the remote profile or tunnel; only there does it step down after
 * repeated stalls and back up after a long stable stretch, never above [ceiling]. A live
 * stream arrives at its own bitrate, so throughput cannot show spare bandwidth: going up is
 * a careful, time-based try that a new stall undoes.
 */
object StreamQualityPolicy {
    const val STALL_WINDOW_MS = 60_000L
    const val STALLS_TO_STEP_DOWN = 2
    const val STABLE_TO_STEP_UP_MS = 10 * 60_000L

    fun adaptive(inputs: QualityInputs): Boolean =
        inputs.profile == StreamProfile.AUTO && inputs.transcodingSupported && inputs.remote

    fun ceiling(inputs: QualityInputs): StreamLevel = when {
        !inputs.transcodingSupported -> StreamLevel.DIRECT
        inputs.profile == StreamProfile.DIRECT -> StreamLevel.DIRECT
        inputs.profile == StreamProfile.TRANSCODED -> StreamLevel.of(inputs.preset)
        inputs.remote -> StreamLevel.HIGH
        else -> StreamLevel.DIRECT
    }

    /** The first level: the ceiling, except Auto away from home starts at the middle preset (or the last good one). */
    fun initial(inputs: QualityInputs, lastGood: StreamLevel? = null): StreamLevel {
        val ceiling = ceiling(inputs)
        if (!adaptive(inputs)) return ceiling
        return atMost(lastGood ?: StreamLevel.MEDIUM, ceiling)
    }

    /** A lower level after [STALLS_TO_STEP_DOWN] stalls within [STALL_WINDOW_MS], else null. */
    fun afterStalls(inputs: QualityInputs, level: StreamLevel, stallTimesMs: List<Long>, nowMs: Long): StreamLevel? {
        if (!adaptive(inputs)) return null
        val recent = stallTimesMs.count { nowMs - it in 0..STALL_WINDOW_MS }
        if (recent < STALLS_TO_STEP_DOWN) return null
        return StreamLevel.entries.getOrNull(level.ordinal + 1)
    }

    /** One level up after [STABLE_TO_STEP_UP_MS] without stalls, when below the ceiling; else null. */
    fun afterStable(inputs: QualityInputs, level: StreamLevel, stableForMs: Long): StreamLevel? {
        if (!adaptive(inputs) || stableForMs < STABLE_TO_STEP_UP_MS) return null
        val ceiling = ceiling(inputs)
        if (level.ordinal <= ceiling.ordinal) return null
        return StreamLevel.entries[level.ordinal - 1]
    }

    /** [level], but not better than [ceiling]. */
    fun atMost(level: StreamLevel, ceiling: StreamLevel): StreamLevel =
        if (level.ordinal < ceiling.ordinal) ceiling else level
}

/**
 * Transcoded stream URLs as OpenWebif writes them in its `device=phone` playlists: the
 * service reference on the transcoding port with `bitrate` (bit/s), `width` and `height`.
 * OpenPLi-style images separate the parameters with `&`, other images with `?`.
 */
object TranscodeUrls {
    private val ampersandImages = setOf("openpli", "satdreamgr", "openvision")

    fun separator(imageDistro: String): Char = if (imageDistro.trim().lowercase() in ampersandImages) '&' else '?'

    fun build(host: String, port: Int, encodedReference: String, preset: TranscodePreset, separator: Char): String =
        "http://$host:$port/$encodedReference?bitrate=${preset.bitrateKbps * 1000}" +
            "${separator}width=${preset.width}${separator}height=${preset.height}"
}
