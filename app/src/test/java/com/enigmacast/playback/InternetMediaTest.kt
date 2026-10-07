package com.enigmacast.playback

import androidx.media3.common.MimeTypes
import com.enigmacast.domain.tuner.StreamFailure
import com.enigmacast.domain.tuner.StreamHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InternetMediaTest {
    @Test
    fun theContainerFollowsTheUrl() {
        assertEquals(MimeTypes.APPLICATION_M3U8, InternetMedia.mimeTypeFor("https://m1b2.worldcast.tv/dancetelevisionone/2/dancetelevisionone.m3u8"))
        assertEquals(MimeTypes.APPLICATION_M3U8, InternetMedia.mimeTypeFor("https://h/live/INDEX.M3U8?token=x"))
        assertEquals(MimeTypes.APPLICATION_M3U8, InternetMedia.mimeTypeFor("http://panel:8080/get.php?user=a&type=m3u8&x=1"))
        assertEquals(MimeTypes.APPLICATION_MPD, InternetMedia.mimeTypeFor("https://h/manifest.mpd"))
        assertEquals(MimeTypes.VIDEO_MP2T, InternetMedia.mimeTypeFor("http://h:8000/live/1.ts"))
        assertNull(InternetMedia.mimeTypeFor("http://h:8000/live/user/pass/123"))
        assertNull(InternetMedia.mimeTypeFor("http://radio.example/stream"))
    }

    @Test
    fun anUnknownFormatThatCannotBeParsedIsTriedAsHlsOnceAtNoCost() {
        assertEquals(InternetRetryAction.TryHls, InternetRetry.next(StreamFailure.PARSE, 0, null, hlsTried = false, autoReconnect = true))
        // Already HLS, or HLS was tried: count normal tries.
        assertEquals(InternetRetryAction.Retry(1_000), InternetRetry.next(StreamFailure.PARSE, 0, MimeTypes.APPLICATION_M3U8, hlsTried = true, autoReconnect = true))
    }

    @Test
    fun retriesStopAfterThreeWithTheRightMessage() {
        assertEquals(InternetRetryAction.Retry(1_000), InternetRetry.next(StreamFailure.CONNECT, 0, null, hlsTried = false, autoReconnect = true))
        assertEquals(InternetRetryAction.Retry(2_000), InternetRetry.next(StreamFailure.CONNECT, 1, null, hlsTried = false, autoReconnect = true))
        assertEquals(InternetRetryAction.Retry(4_000), InternetRetry.next(StreamFailure.CONNECT, 2, null, hlsTried = false, autoReconnect = true))
        assertEquals(
            InternetRetryAction.GiveUp(StreamHealth.INTERNET_FAILED),
            InternetRetry.next(StreamFailure.CONNECT, 3, null, hlsTried = false, autoReconnect = true),
        )
        assertEquals(
            InternetRetryAction.GiveUp(StreamHealth.INTERNET_UNSUPPORTED),
            InternetRetry.next(StreamFailure.PARSE, 3, MimeTypes.APPLICATION_M3U8, hlsTried = true, autoReconnect = true),
        )
    }

    @Test
    fun withoutAutoReconnectTheFirstFailureIsFinal() {
        assertEquals(
            InternetRetryAction.GiveUp(StreamHealth.INTERNET_FAILED),
            InternetRetry.next(StreamFailure.HTTP_OTHER, 0, MimeTypes.APPLICATION_M3U8, hlsTried = false, autoReconnect = false),
        )
    }
}
