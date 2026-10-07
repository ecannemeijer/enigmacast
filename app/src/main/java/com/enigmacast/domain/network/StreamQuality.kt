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
    /** From `/api/about` (`transcoding`): an encoder plus the TranscodingSetup plugin. Only Automatic relies on it. */
    val transcodingSupported: Boolean,
    /** Remote profile or tunnel: the stream crosses the internet. At home it never does. */
    val remote: Boolean,
    /** A transcoded start of this receiver failed in this session: everything plays Normal until the setting changes. */
    val transcoderFailed: Boolean = false,
)

/**
 * Normal (DIRECT) always sends the original stream. Transcoded is the user's explicit choice
 * and always asks the receiver's encoder at the chosen preset, at home too and without
 * trusting `/api/about` (some images do not report it); if that start fails, the session
 * falls back to Normal once ([QualitySession]). Automatic sends the original at home (the
 * home network has the bandwidth, and the transcoding port may not even be open), and a
 * transcoded copy over the remote profile or tunnel when the receiver reports transcoding;
 * only there does it step down after repeated stalls and back up after a long stable
 * stretch, never above [ceiling]. A live stream arrives at its own bitrate, so throughput
 * cannot show spare bandwidth: going up is a careful, time-based try that a new stall undoes.
 */
object StreamQualityPolicy {
    const val STALL_WINDOW_MS = 60_000L
    const val STALLS_TO_STEP_DOWN = 2
    const val STABLE_TO_STEP_UP_MS = 10 * 60_000L

    fun adaptive(inputs: QualityInputs): Boolean =
        inputs.profile == StreamProfile.AUTO && inputs.transcodingSupported && inputs.remote && !inputs.transcoderFailed

    fun ceiling(inputs: QualityInputs): StreamLevel = when {
        inputs.transcoderFailed -> StreamLevel.DIRECT
        inputs.profile == StreamProfile.DIRECT -> StreamLevel.DIRECT
        inputs.profile == StreamProfile.TRANSCODED -> StreamLevel.of(inputs.preset)
        !inputs.transcodingSupported -> StreamLevel.DIRECT
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
 * Transcoded stream URLs. Enigma2's stream server (`lib/dvb/streamserver.cpp`, OpenATV and
 * OpenPLi alike) transcodes when the request has `bitrate`, and reads `width` and `height`
 * as `&`-separated parameters; it only turns a repeated `?` into `&` for old clients. So the
 * query is always `?bitrate=<bit/s>&width=<px>&height=<px>`, never with a second `?`.
 *
 * The port: boxes with a Broadcom encoder use the TranscodingSetup plugin's port (8001 or
 * 8002); other encoder boxes transcode on the normal stream port 8001. OpenWebif knows which
 * and writes it in its phone playlist (`/web/stream.m3u?device=phone`), see [detect].
 */
object TranscodeUrls {
    const val DEFAULT_PORT = 8002

    fun query(preset: TranscodePreset): String =
        "?bitrate=${preset.bitrateKbps * 1000}&width=${preset.width}&height=${preset.height}"

    fun build(host: String, port: Int, encodedReference: String, preset: TranscodePreset): String =
        "http://$host:$port/$encodedReference${query(preset)}"

    /**
     * What OpenWebif's phone playlist says: the port of the first stream URL, and whether it
     * carries transcoding parameters (no `bitrate` means the receiver offers no transcoding).
     * Null when the playlist has no stream URL. The URL may contain the stream password; only
     * the port is kept.
     */
    fun detect(playlist: String): TranscodeDetection? {
        val line = playlist.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null
        val uri = runCatching { java.net.URI(line.substringBefore(' ')) }.getOrNull() ?: return null
        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }
        val query = uri.rawQuery.orEmpty().replace('?', '&')
        val transcodes = query.split('&').any { it.startsWith("bitrate=") }
        return TranscodeDetection(port, transcodes)
    }
}

/** The receiver's own answer to "where do you transcode?". */
data class TranscodeDetection(val port: Int, val transcodes: Boolean)
