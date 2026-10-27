package com.enigmacast.domain.tuner

import com.enigmacast.data.openwebif.OpenWebifJson
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.sync.GuideCachePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceMatchTest {
    // Hot Bird 13°E: namespace 0x00820000 in the bouquet.
    private val rai1 = "1:0:19:3401:3:13E:820000:0:0:0:"
    private val rai2 = "1:0:1:3402:3:13E:820000:0:0:0:"
    private val rete4 = "1:0:1:3E8:7D0:13E:820000:0:0:0:"
    private val bouquet = listOf(
        BouquetEntry("Rai 1 HD", rai1),
        BouquetEntry("Rai 2", rai2),
        BouquetEntry("Rete 4", rete4),
    )

    private fun now(reference: String, title: String, name: String = "") =
        NowEvent(reference, title, beginEpochSec = 1_000, durationSec = 600, serviceName = name)

    @Test
    fun keyIgnoresCaseLeadingZerosAndTrailingParts() {
        assertEquals(NowEvents.key(rai1), NowEvents.key("1:0:19:3401:0003:13e:00820000:0:0:0:"))
        assertEquals(NowEvents.key(rai1), NowEvents.key("1:0:19:3401:3:13E:820000:0:0:0::Rai 1 HD"))
    }

    @Test
    fun alternativesDoNotShareOneKey() {
        val a = "1:134:1:0:0:0:0:0:0:0:FROM BOUQUET \"alternatives.rai1.tv\" ORDER BY bouquet"
        val b = "1:134:1:0:0:0:0:0:0:0:FROM BOUQUET \"alternatives.rai2.tv\" ORDER BY bouquet"
        assertNotEquals(NowEvents.key(a), NowEvents.key(b))
        assertEquals(NowEvents.key(a), NowEvents.key(a.replace("\"", "%22")))
    }

    @Test
    fun orbitalAndTransportKeys() {
        assertEquals("3401:3:13E:82", ServiceMatch.orbitalKey(rai1))
        assertEquals(ServiceMatch.orbitalKey(rai1), ServiceMatch.orbitalKey("1:0:19:3401:3:13E:8211D2:0:0:0:"))
        assertEquals("3401:3:13E", ServiceMatch.transportKey(rai1))
        assertNull(ServiceMatch.orbitalKey("4097:0:1:0:0:0:0:0:0:0:http%3a//example.invalid/x:Name"))
        assertNull(ServiceMatch.orbitalKey("1:134:1:0:0:0:0:0:0:0:FROM BOUQUET \"alternatives.x.tv\" ORDER BY bouquet"))
    }

    @Test
    fun nameKeyIgnoresCaseAccentsAndPunctuation() {
        assertEquals(ServiceMatch.nameKey("Rai 1 HD"), ServiceMatch.nameKey("RAI-1 hd"))
        assertEquals(ServiceMatch.nameKey("TV2000"), ServiceMatch.nameKey("Tv 2000"))
        assertEquals("caccia", ServiceMatch.nameKey("Càccia"))
        assertNull(ServiceMatch.nameKey(" - "))
    }

    @Test
    fun exactMatchesStayAsTheyAre() {
        val events = listOf(now(rai1.lowercase(), "TG1"), now(rai2, "TG2"))
        val matched = ServiceMatch.nowEvents(events, bouquet)
        assertEquals(events[1], matched[1])
        assertEquals(NowEvents.key(rai1), NowEvents.key(matched[0].reference))
        assertEquals("TG1", NowEvents.lookup(NowEvents.index(matched), rai1, 1_100)?.title)
    }

    @Test
    fun otherNamespaceOnTheSameSatelliteLandsOnTheChannel() {
        val events = listOf(now("1:0:19:3401:3:13E:8211D2:0:0:0:", "TG1"))
        val matched = ServiceMatch.nowEvents(events, bouquet)
        assertEquals(rai1, matched.single().reference)
        assertEquals("TG1", NowEvents.lookup(NowEvents.index(matched), rai1, 1_100)?.title)
    }

    @Test
    fun otherNamespaceAltogetherFallsBackToTheTransport() {
        val matched = ServiceMatch.nowEvents(listOf(now("1:0:1:3E8:7D0:13E:EEEE0000:0:0:0:", "Film")), bouquet)
        assertEquals(rete4, matched.single().reference)
    }

    @Test
    fun alternativeAnswersLandOnTheAlternativesEntry() {
        val alternative = "1:134:1:0:0:0:0:0:0:0:FROM BOUQUET \"alternatives.rai_3.tv\" ORDER BY bouquet"
        val list = bouquet + BouquetEntry("Rai 3", alternative)
        val resolved = now("1:0:1:3403:3:13E:820000:0:0:0:", "TGR", name = "Rai 3")
        val matched = ServiceMatch.nowEvents(listOf(resolved), list)
        assertEquals(alternative, matched.single().reference)
        assertEquals("TGR", NowEvents.lookup(NowEvents.index(matched), alternative, 1_100)?.title)
    }

    @Test
    fun nameMatchesOnlyWhenUnique() {
        val list = bouquet + BouquetEntry("Rai 2", "1:0:1:9999:3:13E:820000:0:0:0:")
        val unknown = "1:0:1:7777:1:1:1:0:0:0:"
        val byName = ServiceMatch.nowEvents(listOf(now(unknown, "Show", name = "RETE 4")), list)
        assertEquals(rete4, byName.single().reference)
        val ambiguous = ServiceMatch.nowEvents(listOf(now(unknown, "Show", name = "Rai 2")), list)
        assertEquals(unknown, ambiguous.single().reference)
    }

    @Test
    fun neverOverwritesAChannelThatMatchedExactly() {
        val events = listOf(now(rai1, "TG1"), now("1:0:19:3401:3:13E:8211D2:0:0:0:", "Other"))
        val matched = ServiceMatch.nowEvents(events, bouquet)
        assertEquals(rai1, matched[0].reference)
        assertEquals("1:0:19:3401:3:13E:8211D2:0:0:0:", matched[1].reference)
        assertEquals("TG1", NowEvents.lookup(NowEvents.index(matched), rai1, 1_100)?.title)
    }

    @Test
    fun guideEventsAreMatchedTheSameWay() {
        val events = (0 until 3).map { i ->
            GuideEvent(i, "1:0:19:3401:3:13E:8211D2:0:0:0:", "P$i", "", "", 1_000L + i * 600, 600)
        }
        val matched = ServiceMatch.guideEvents(events, bouquet)
        assertEquals(listOf(rai1, rai1, rai1), matched.map { it.reference })
    }

    @Test
    fun brokenControlCharactersDoNotBlankTheBouquet() {
        val body = "{\"events\":[{\"sref\":\"$rai1\",\"sname\":\"Rai 1 HD\",\"title\":\"TG1\u0005 edizione\u0086\"," +
            "\"begin_timestamp\":1000,\"duration_sec\":600,\"id\":7}]}"
        val events = OpenWebifJson.parseNowEvents(body)
        assertEquals(1, events.size)
        assertEquals("Rai 1 HD", events.single().serviceName)
        assertEquals(1, OpenWebifJson.parseGuideEvents(body).size)
        assertNull(OpenWebifJson.parseNowEventsOrNull("<html>500</html>"))
    }

    @Test
    fun partialCacheOfALargeBouquetAsksTheReceiver() {
        val decide = { covered: Int, total: Int -> GuideCachePolicy.decide(100, 0, 3600, 0, 86_400, covered, total) }
        assertEquals(GuideCachePolicy.Decision.NETWORK, decide(5, 60))
        assertEquals(GuideCachePolicy.Decision.CACHE, decide(40, 60))
        assertEquals(GuideCachePolicy.Decision.CACHE, decide(1, 5))
        assertEquals(GuideCachePolicy.Decision.CACHE, GuideCachePolicy.decide(100, 0, 3600, 0, 86_400))
    }
}
