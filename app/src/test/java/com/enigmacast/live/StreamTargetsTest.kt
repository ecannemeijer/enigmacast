package com.enigmacast.live

import com.enigmacast.data.openwebif.OpenWebifEndpoints
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.network.ReceiverRoute
import com.enigmacast.domain.network.ReceiverRouting
import com.enigmacast.domain.network.StreamLevel
import com.enigmacast.domain.service.ServiceStreams
import com.enigmacast.util.EnigmaLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamTargetsTest {
    private val endpoints = OpenWebifEndpoints()
    private val rtl4 = "1:0:19:1B6C:C88:3:EB0000:0:0:0:"
    private val encoded = "1%3A0%3A19%3A1B6C%3AC88%3A3%3AEB0000%3A0%3A0%3A0%3A"

    /** Enrico's setup: OpenWebif on port 80, stream port 8001, a password moved to the Keystore. */
    private val lan = ReceiverConfig(id = "box", name = "SF8008", host = "192.168.1.209", username = "root", password = "secret")

    @Test
    fun theLanDefaultConfigStreamsTheOriginalOnPort8001() {
        val effective = ReceiverRouting.effective(lan, ReceiverRoute.HOME)
        assertEquals("http://192.168.1.209:8001/$encoded", StreamTargets.url(endpoints, effective, rtl4, StreamLevel.DIRECT))
    }

    @Test
    fun theStreamUrlNeverCarriesCredentials() {
        val url = StreamTargets.url(endpoints, lan, rtl4, StreamLevel.DIRECT)
        assertFalse(url.contains("root") || url.contains("secret"))
        assertEquals(okhttp3.Credentials.basic("root", "secret"), endpoints.streamAuthorization(lan))
    }

    @Test
    fun transcodedStreamsUseTheTranscodingPortAndOneQuestionMark() {
        assertEquals(
            "http://192.168.1.209:8002/$encoded?bitrate=1200000&width=1024&height=576",
            StreamTargets.url(endpoints, lan, rtl4, StreamLevel.MEDIUM),
        )
        assertEquals(
            "http://192.168.1.209:8001/$encoded?bitrate=2500000&width=1280&height=720",
            StreamTargets.url(endpoints, lan.copy(transcodingPort = 8001), rtl4, StreamLevel.HIGH),
        )
    }

    @Test
    fun theRemoteProfileUsesItsOwnTranscodingPort() {
        val config = lan.copy(remoteHost = "box.example.org", remoteTranscodingPort = 18002, accessMode = com.enigmacast.domain.network.AccessMode.REMOTE)
        val effective = ReceiverRouting.effective(config, ReceiverRoute.REMOTE)
        assertEquals(
            "http://box.example.org:18002/$encoded?bitrate=600000&width=640&height=360",
            StreamTargets.url(endpoints, effective, rtl4, StreamLevel.LOW),
        )
    }

    @Test
    fun loggedUrlsHideCredentials() {
        assertEquals("http://***@box:8001/ref", EnigmaLog.sanitizeUrl("http://root:secret@box:8001/ref"))
        assertEquals("http://box/api/x?a=1&password=***", EnigmaLog.sanitizeUrl("http://box/api/x?a=1&password=secret"))
    }
    @Test
    fun anInternetReferenceIsDetectedBeforeAnyReceiverUrlIsBuilt() {
        val ref = "4097:0:1:0:0:0:0:0:0:0:http%3a//cdn.example.com/live.ts"
        assertEquals("http://cdn.example.com/live.ts", ServiceStreams.internetUrl(ref))
        // StreamTargets.target short-circuits on this; calling url() alone would wrongly hit the receiver.
        val wrong = StreamTargets.url(endpoints, lan, ref, StreamLevel.MEDIUM)
        assertTrue(wrong.startsWith("http://192.168.1.209:8002/"))
    }

}
