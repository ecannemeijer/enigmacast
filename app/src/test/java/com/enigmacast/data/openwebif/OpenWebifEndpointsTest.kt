package com.enigmacast.data.openwebif

import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.osd.GrabFormat
import com.enigmacast.domain.osd.GrabMode
import com.enigmacast.domain.remote.EnigmaKey
import com.enigmacast.domain.remote.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenWebifEndpointsTest {
    private val endpoints = OpenWebifEndpoints()
    private val config = ReceiverConfig(
        host = "192.168.1.100",
        httpPort = 80,
        streamingPort = 8001,
    )

    @Test
    fun encodesServiceReferenceInStreamUrl() {
        val reference = "1:0:19:1:2:3:4:0:0:0:"
        assertEquals(
            "http://192.168.1.100:8001/1%3A0%3A19%3A1%3A2%3A3%3A4%3A0%3A0%3A0%3A",
            endpoints.stream(config, reference),
        )
    }

    @Test
    fun buildsRemoteAndGrabUrls() {
        assertEquals(
            "http://192.168.1.100/api/remotecontrol?command=352",
            endpoints.remoteControl(config, EnigmaKey.OK, KeyAction.SHORT),
        )
        assertEquals(
            "http://192.168.1.100/api/remotecontrol?command=358&type=long",
            endpoints.remoteControl(config, EnigmaKey.INFO, KeyAction.LONG),
        )
        assertEquals(
            "http://192.168.1.100/grab?format=png&mode=osd",
            endpoints.grab(config, GrabFormat.PNG, GrabMode.OSD),
        )
        assertEquals(
            "http://192.168.1.100/grab?format=jpg&mode=all",
            endpoints.grab(config, GrabFormat.JPG, GrabMode.ALL),
        )
        assertEquals(
            "http://192.168.1.100/api/zap?sRef=1%3A0%3A19%3A1%3A2%3A3%3A4%3A0%3A0%3A0%3A",
            endpoints.zap(config, "1:0:19:1:2:3:4:0:0:0:"),
        )
        assertEquals("http://192.168.1.100/api/powerstate", endpoints.powerState(config))
        assertEquals("http://192.168.1.100/api/powerstate?newstate=4", endpoints.powerState(config, 4))
    }

    @Test
    fun keepsNonDefaultHttpsPort() {
        val secure = config.copy(useHttps = true, httpPort = 8443)
        assertEquals("https://192.168.1.100:8443/api/about", endpoints.about(secure))
        assertEquals(
            "http://192.168.1.100:8001/1%3A0%3A1%3A0%3A0%3A0%3A0%3A0%3A0%3A0%3A",
            endpoints.stream(secure, "1:0:1:0:0:0:0:0:0:0:"),
        )
    }
}
