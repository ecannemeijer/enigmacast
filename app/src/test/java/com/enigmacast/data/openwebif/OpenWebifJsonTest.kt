package com.enigmacast.data.openwebif

import com.enigmacast.domain.model.SleepTimerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWebifJsonTest {
    @Test
    fun parsesCurrentChannelAndUpcomingEpg() {
        val current = """
            {"info":{"name":"NPO3 HD","ref":"1:0:19:1:0:0:0:0:0:0:"}}
        """.trimIndent()
        val epg = """
            {
              "events": [
                {
                  "title": "Woezel &amp; Pip",
                  "shortdesc": "",
                  "longdesc": "Buurpip &#x27;s avontuur",
                  "begin": "09:30",
                  "end": "09:35",
                  "begin_timestamp": 1000,
                  "duration_sec": 500,
                  "now_timestamp": 1400,
                  "sname": "NPO3 HD"
                },
                {
                  "title": "Nijntje",
                  "shortdesc": "De taart",
                  "longdesc": "Vader Pluis bakt.",
                  "begin": "09:35",
                  "end": "09:40",
                  "begin_timestamp": 1500,
                  "duration_sec": 300,
                  "now_timestamp": 1400,
                  "sname": "NPO3 HD"
                },
                {
                  "title": "Afgelopen",
                  "shortdesc": "",
                  "longdesc": "",
                  "begin": "09:00",
                  "end": "09:20",
                  "begin_timestamp": 100,
                  "duration_sec": 200,
                  "now_timestamp": 1400,
                  "sname": "NPO3 HD"
                }
              ]
            }
        """.trimIndent()
        val guide = OpenWebifJson.parseGuide(current, epg)
        assertEquals("NPO3 HD", guide.channelName)
        assertEquals("1:0:19:1:0:0:0:0:0:0:", guide.serviceReference)
        assertEquals(2, guide.events.size)
        val now = guide.currentEvent
        assertEquals("Woezel & Pip", now?.title)
        assertEquals("Buurpip 's avontuur", now?.plot)
        assertTrue(now?.current == true)
        assertEquals("09:35", guide.events[1].beginLabel)
    }

    @Test
    fun bouquetEntriesCanListTheReferenceBeforeTheName() {
        val body = """
            {"bouquets":[["1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.fav.tv\" ORDER BY bouquet","Nederland"]]}
        """.trimIndent()
        val parsed = OpenWebifJson.parseBouquets(body)
        assertEquals("Nederland", parsed.single().name)
        assertEquals(
            "1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.fav.tv\" ORDER BY bouquet",
            parsed.single().reference,
        )
    }

    @Test
    fun readsBouquetReferenceFromCurrentService() {
        val current = """
            {"info":{"name":"NPO3 HD","ref":"1:0:19:1:0:0:0:0:0:0:","bqref":"1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"fav\" ORDER BY bouquet"}}
        """.trimIndent()
        assertEquals(
            "1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"fav\" ORDER BY bouquet",
            OpenWebifJson.bouquetReference(current),
        )
    }

    @Test
    fun readsStandbyFlagFromPowerState() {
        assertTrue(OpenWebifJson.parsePower("""{"result": true, "instandby": true}""").inStandby)
        assertFalse(OpenWebifJson.parsePower("""{"result": true, "instandby": false}""").inStandby)
    }

    @Test
    fun powerCommandReplyCountsAsSuccess() {
        assertTrue(OpenWebifJson.isSuccess("""{"result": true, "instandby": false}"""))
        assertFalse(OpenWebifJson.isSuccess("""{"result": false}"""))
    }

    @Test
    fun parsesReceiverDetailsFromAbout() {
        val about = """
            {
              "info": {
                "brand": "Vu+",
                "model": "Uno 4K SE",
                "friendlyimagedistro": "OpenATV",
                "imagever": "7.5",
                "enigmaver": "2025-01-10",
                "webifver": "OWIF 2.2.0",
                "kernelver": "4.1.20",
                "friendlychipsettext": "Broadcom 7252S",
                "uptime": "3d 4:05",
                "tuners": [
                  {"name": "Tuner A", "type": "Vuplus DVB-S NIM(45208 FBC) (DVB-S2)", "rec": "", "live": "", "stream": ""},
                  {"name": "Tuner B", "type": "", "rec": "", "live": "", "stream": ""}
                ]
              },
              "service": {"name": "NPO1 HD"}
            }
        """.trimIndent()
        val details = OpenWebifJson.parseReceiverDetails(about)
        assertEquals("Vu+", details.brand)
        assertEquals("Uno 4K SE", details.model)
        assertEquals("OpenATV", details.imageDistro)
        assertEquals("7.5", details.imageVersion)
        assertEquals("OWIF 2.2.0", details.webifVersion)
        assertEquals("4.1.20", details.kernelVersion)
        assertEquals("Broadcom 7252S", details.chipset)
        assertEquals("3d 4:05", details.uptime)
        assertEquals(
            listOf("Tuner A: Vuplus DVB-S NIM(45208 FBC) (DVB-S2)", "Tuner B"),
            details.tuners,
        )
    }

    @Test
    fun parsesSleepTimerWithStringMinutes() {
        val status = OpenWebifJson.parseSleepTimer(
            """{"enabled": true, "minutes": "60", "action": "shutdown", "message": "Sleeptimer is enabled"}""",
        )
        assertTrue(status.enabled)
        assertEquals(60, status.minutes)
        assertEquals(SleepTimerAction.SHUTDOWN, status.action)
    }

    @Test
    fun parsesDisabledSleepTimer() {
        val status = OpenWebifJson.parseSleepTimer(
            """{"enabled": false, "minutes": 0, "action": "standby", "message": "Sleeptimer has been disabled"}""",
        )
        assertFalse(status.enabled)
        assertEquals(SleepTimerAction.STANDBY, status.action)
    }

    @Test
    fun sleepTimerErrorsThrow() {
        val standby = assertThrows(OpenWebifException::class.java) {
            OpenWebifJson.parseSleepTimer(
                """{"enabled": false, "minutes": 0, "action": "standby", "message": "ERROR: Cannot set SleepTimer while device is in Standby-Mode"}""",
            )
        }
        assertEquals("Cannot set SleepTimer while device is in Standby-Mode", standby.message)
        assertThrows(OpenWebifException::class.java) {
            OpenWebifJson.parseSleepTimer("""{"result": false, "message": "SleepTimer error"}""")
        }
        assertThrows(OpenWebifException::class.java) {
            OpenWebifJson.parseSleepTimer("null")
        }
    }
}
