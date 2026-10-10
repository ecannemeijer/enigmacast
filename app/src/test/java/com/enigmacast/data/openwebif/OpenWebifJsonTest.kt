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
    fun epgSearchKeepsTheChannelName() {
        val body = """
            {"events":[{
              "id": 9,
              "sref": "1:0:19:1:0:0:0:0:0:0:",
              "sname": "Ziggo Sport",
              "title": "Formula 1",
              "shortdesc": "Race",
              "longdesc": "",
              "begin_timestamp": 1700000000,
              "duration_sec": 7200
            }]}
        """.trimIndent()
        val found = OpenWebifJson.parseEpgSearch(body).single()
        assertEquals("Formula 1", found.event.title)
        assertEquals("Ziggo Sport", found.channelName)
        assertEquals("1:0:19:1:0:0:0:0:0:0:", found.event.reference)
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

    @Test
    fun parsesTunersStreamsStandbyAndRunningRecordings() {
        val about = """
            {"info":{
              "tuners":[
                {"name":"Tuner A","type":"BCM45208 (DVB-S2)","rec":"19.2°E / RTL 4","live":""},
                {"name":"Tuner B","type":"BCM45208 (DVB-S2)","rec":"","live":"","stream":"SBS6"}
              ],
              "streams":[
                {"ref":"1:0:19:1B1D:3F6:1:C00000:0:0:0:","name":"SBS6","eventname":"Nieuws","ip":"::ffff:192.168.1.22","type":"S","tuner_number":1}
              ]
            }}
        """.trimIndent()
        val status = """{"inStandby":"true","currservice_serviceref":"1:0:19:4651:3FC:1:C00000:0:0:0:"}"""
        val timers = """
            {"timers":[
              {"serviceref":"1:0:19:7D5:3F0:1:C00000:0:0:0:","state":2,"justplay":0},
              {"serviceref":"1:0:19:1:1:1:C00000:0:0:0:","state":0,"justplay":0},
              {"serviceref":"1:0:19:2:2:2:C00000:0:0:0:","state":2,"justplay":1}
            ]}
        """.trimIndent()
        val snapshot = OpenWebifJson.parseTunerSnapshot(about, status, timers)
        assertEquals(2, snapshot.tuners.size)
        assertEquals("19.2°E / RTL 4", snapshot.tuners[0].recording)
        assertEquals("SBS6", snapshot.tuners[1].stream)
        assertEquals(1, snapshot.streams.single().tunerNumber)
        assertEquals("::ffff:192.168.1.22", snapshot.streams.single().address)
        assertEquals(true, snapshot.inStandby)
        assertEquals("", snapshot.liveReference)
        assertEquals(listOf("1:0:19:7D5:3F0:1:C00000:0:0:0:"), snapshot.recordingReferences)
    }

    @Test
    fun parsesChannelPositionsPiconsAndBouquetStarts() {
        val services = """
            {"services":[
              {"pos":0,"servicename":"Nieuws","servicereference":"1:64:0:0:0:0:0:0:0:0::Nieuws","picon":"/images/default_picon.png"},
              {"pos":1,"servicename":"NPO 1","servicereference":"1:0:19:4651:3FC:1:C00000:0:0:0:","picon":"/picon/1_0_19_4651_3FC_1_C00000_0_0_0.png"}
            ]}
        """.trimIndent()
        val parsed = OpenWebifJson.parseServices(services)
        assertEquals("", parsed[0].picon)
        assertEquals(1, parsed[1].number)
        assertEquals("/picon/1_0_19_4651_3FC_1_C00000_0_0_0.png", parsed[1].picon)
        val root = """
            {"services":[
              {"servicename":"Favourites (TV)","servicereference":"1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.favourites.tv\" ORDER BY bouquet","startpos":0},
              {"servicename":"Sport","servicereference":"1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.sport.tv\" ORDER BY bouquet","startpos":120}
            ]}
        """.trimIndent()
        val starts = OpenWebifJson.parseBouquetStartPositions(root)
        assertEquals(120, starts["1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.sport.tv\" ORDER BY bouquet"])
    }

    @Test
    fun parsesNowEventsForABouquetAndSkipsChannelsWithoutEpg() {
        val body = """
            {"events":[
              {"id":1,"begin_timestamp":1000,"duration_sec":1800,"title":"NOS Journaal &amp; Weer","shortdesc":"","longdesc":"","sref":"1:0:19:4651:3FC:1:C00000:0:0:0:","sname":"NPO 1","now_timestamp":1200,"remaining":1600},
              {"id":null,"begin_timestamp":0,"duration_sec":0,"title":"N/A","shortdesc":"","longdesc":"","sref":"1:0:19:4652:3FC:1:C00000:0:0:0:","sname":"NPO 2"}
            ],"result":true}
        """.trimIndent()
        val events = OpenWebifJson.parseNowEvents(body)
        assertEquals(1, events.size)
        assertEquals("NOS Journaal & Weer", events[0].title)
        assertEquals(2800L, events[0].endEpochSec)
        assertEquals(0, OpenWebifJson.parseNowEvents("not json").size)
        val withAlternatives = """
            {"events":[
              {"id":1,"begin_timestamp":1000,"duration_sec":1800,"title":"Journaal","sref":"1:0:1:4651:3FC:1:C00000:0:0:0:",
               "asrefs":["1:0:19:99:3FC:1:C00000:0:0:0:","1:0:22:100:3FC:1:C00000:0:0:0:"]}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("1:0:19:99:3FC:1:C00000:0:0:0:", "1:0:22:100:3FC:1:C00000:0:0:0:"),
            OpenWebifJson.parseNowEvents(withAlternatives).single().alternatives,
        )
    }

    @Test
    fun parsesGuideEventsWithIdsAndReferences() {
        val body = """
            {"events":[
              {"id":101,"sref":"1:0:19:A:0:0:0:0:0:0:","title":"Nieuws &amp; Sport","shortdesc":"Kort","longdesc":"Lang verhaal","begin_timestamp":1000,"duration_sec":1800},
              {"id":102,"title":"Zonder sref","shortdesc":"Alleen kort","longdesc":"","begin_timestamp":2800,"duration_sec":600},
              {"id":103,"sref":"1:0:19:A:0:0:0:0:0:0:","title":"N/A","begin_timestamp":3400,"duration_sec":60},
              {"id":104,"sref":"1:0:19:A:0:0:0:0:0:0:","title":"Geen duur","begin_timestamp":3400,"duration_sec":0}
            ]}
        """.trimIndent()
        val events = OpenWebifJson.parseGuideEvents(body, fallbackReference = "1:0:1:B:0:0:0:0:0:0:")
        assertEquals(2, events.size)
        assertEquals(101, events[0].eventId)
        assertEquals("Nieuws & Sport", events[0].title)
        assertEquals("Lang verhaal", events[0].description)
        assertEquals(2800L, events[0].endEpochSec)
        assertEquals("1:0:1:B:0:0:0:0:0:0:", events[1].reference)
        assertEquals("Alleen kort", events[1].description)
        assertEquals(0, OpenWebifJson.parseGuideEvents("<html>").size)
    }

    @Test
    fun parsesTimerResults() {
        val added = OpenWebifJson.parseTimerResult("""{"result": true, "message": "Timer 'Journaal' added"}""")
        assertTrue(added.success)
        assertEquals("Timer 'Journaal' added", added.message)
        val conflict = OpenWebifJson.parseTimerResult("""{"result": false, "message": "Conflicting Timer(s) detected!"}""")
        assertFalse(conflict.success)
        assertFalse(OpenWebifJson.parseTimerResult("oops").success)
    }

    @Test
    fun readsTheTranscodingFlagFromAbout() {
        val grabs = GrabSupport(png = false, jpg = false, osd = false, all = false, authenticationRequired = false)
        val yes = OpenWebifJson.parseAbout("""{"info":{"model":"solo4k","imagedistro":"openpli","transcoding":true}}""", false, false, grabs)
        assertTrue(yes.transcoding)
        assertEquals("openpli", yes.imageDistro)
        assertFalse(OpenWebifJson.parseAbout("""{"info":{"model":"x","transcoding":false}}""", false, false, grabs).transcoding)
        assertFalse(OpenWebifJson.parseAbout("""{"info":{"model":"x"}}""", false, false, grabs).transcoding)
    }
}
