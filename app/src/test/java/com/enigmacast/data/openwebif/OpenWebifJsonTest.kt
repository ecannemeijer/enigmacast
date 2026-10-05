package com.enigmacast.data.openwebif

import org.junit.Assert.assertEquals
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
}
