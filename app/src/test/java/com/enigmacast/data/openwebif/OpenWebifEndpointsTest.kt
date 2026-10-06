package com.enigmacast.data.openwebif

import com.enigmacast.domain.model.PowerAction
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.model.SleepTimerAction
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
    fun mapsPowerActionsToOpenWebifStates() {
        val expected = mapOf(
            PowerAction.TOGGLE_STANDBY to 0,
            PowerAction.DEEP_STANDBY to 1,
            PowerAction.REBOOT to 2,
            PowerAction.RESTART_GUI to 3,
            PowerAction.WAKE_UP to 4,
            PowerAction.STANDBY to 5,
        )
        expected.forEach { (action, state) ->
            assertEquals(
                "http://192.168.1.100/api/powerstate?newstate=$state",
                endpoints.powerState(config, action),
            )
        }
    }

    @Test
    fun buildsSleepTimerUrls() {
        assertEquals("http://192.168.1.100/api/sleeptimer?cmd=get", endpoints.sleepTimer(config))
        assertEquals(
            "http://192.168.1.100/api/sleeptimer?cmd=set&time=60&action=standby&enabled=True",
            endpoints.setSleepTimer(config, 60, SleepTimerAction.STANDBY, enabled = true),
        )
        assertEquals(
            "http://192.168.1.100/api/sleeptimer?cmd=set&time=0&action=shutdown&enabled=False",
            endpoints.setSleepTimer(config, -5, SleepTimerAction.SHUTDOWN, enabled = false),
        )
        assertEquals(
            "http://192.168.1.100/api/sleeptimer?cmd=set&time=999&action=standby&enabled=True",
            endpoints.setSleepTimer(config, 5_000, SleepTimerAction.STANDBY, enabled = true),
        )
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

    @Test
    fun streamUrlUsesTheStreamPortAndNeverTheWebPort() {
        val custom = config.copy(httpPort = 8080, useHttps = true, streamingPort = 17999)
        assertEquals(
            "http://192.168.1.100:17999/1%3A0%3A19%3A4651%3A3FC%3A1%3AC00000%3A0%3A0%3A0%3A",
            endpoints.stream(custom, "1:0:19:4651:3FC:1:C00000:0:0:0:"),
        )
    }

    @Test
    fun streamAuthorizationIsSentOnlyWithAUserName() {
        assertEquals(null, endpoints.streamAuthorization(config))
        assertEquals("Basic cm9vdDpnZWhlaW0=", endpoints.streamAuthorization(config.copy(username = "root", password = "geheim")))
    }

    @Test
    fun servicesAskForPiconsAndRootListGivesStartPositions() {
        assertEquals(
            "http://192.168.1.100/api/getservices?sRef=1%3A7%3A1&picon=1",
            endpoints.services(config, "1:7:1"),
        )
        assertEquals("http://192.168.1.100/api/getservices", endpoints.bouquetStartPositions(config))
        assertEquals("http://192.168.1.100/picon/1_0_1.png", endpoints.picon(config, "/picon/1_0_1.png"))
    }

    @Test
    fun nowEpgIsOneRequestPerBouquet() {
        assertEquals(
            "http://192.168.1.100/api/epgnow?bRef=1%3A7%3A1%3A0%3A0%3A0%3A0%3A0%3A0%3A0%3AFROM%20BOUQUET%20%22userbouquet.favourites.tv%22%20ORDER%20BY%20bouquet",
            endpoints.epgNow(config, "1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.favourites.tv\" ORDER BY bouquet"),
        )
    }

    @Test
    fun buildsBouquetGuideAndRecordUrls() {
        assertEquals(
            "http://192.168.1.100/api/epgmulti?bRef=1%3A7%3A1%3A0%3A0%3A0%3A0%3A0%3A0%3A0%3AFROM%20BOUQUET%20%22userbouquet.favourites.tv%22%20ORDER%20BY%20bouquet&time=1700000000&endTime=180",
            endpoints.epgMulti(config, "1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.favourites.tv\" ORDER BY bouquet", 1_700_000_000L, 180),
        )
        assertEquals(
            "http://192.168.1.100/api/timeraddbyeventid?sRef=1%3A0%3A19%3A1%3A0%3A0%3A0%3A0%3A0%3A0%3A&eventid=4711",
            endpoints.timerAddByEventId(config, "1:0:19:1:0:0:0:0:0:0:", 4711),
        )
    }
}
