package com.enigmacast.playback

import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * MPEG-TS extractor flags used when starting a live Enigma2 stream.
 *
 * Fast start still detects H.264 access units without AUD markers so the first
 * real IDR is found sooner. It does **not** treat non-IDR I-frames as keyframes:
 * on Amlogic (and several other STB SoCs) decoding from those yields a green or
 * noisy picture until the next IDR, while audio already plays.
 */
object TsZapFlags {
    fun forLive(fastStart: Boolean): Int =
        if (fastStart) DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS else 0
}
