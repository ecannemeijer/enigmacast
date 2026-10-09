package com.enigmacast.data.discovery

import com.enigmacast.domain.model.ReceiverConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class KnownReceiverFilterTest {
    @Test
    fun savedHostIsLeftOutOfTheResults() {
        val living = DiscoveredReceiver(host = "192.168.1.10", httpPort = 80, model = "Living")
        val bed = DiscoveredReceiver(host = "192.168.1.20", httpPort = 80, model = "Bedroom")
        val saved = listOf(ReceiverConfig(id = "living", name = "Living room", host = "192.168.1.10"))
        assertEquals(listOf(bed), listOf(living, bed).withoutSaved(saved))
    }

    @Test
    fun hostMatchIgnoresCaseAndSpaces() {
        val found = DiscoveredReceiver(host = "Box.lan", httpPort = 80)
        val saved = listOf(ReceiverConfig(id = "a", host = "  box.lan  "))
        assertEquals(emptyList<DiscoveredReceiver>(), listOf(found).withoutSaved(saved))
    }

    @Test
    fun nothingSavedKeepsEveryResult() {
        val found = DiscoveredReceiver(host = "192.168.1.10", httpPort = 80)
        assertEquals(listOf(found), listOf(found).withoutSaved(emptyList()))
    }
}
