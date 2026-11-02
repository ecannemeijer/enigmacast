package com.enigmacast.companionbridge

import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.ReceiverConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverShareTest {
    @Test
    fun sharesTheActiveReceiverWithItsLogin() {
        val a = ReceiverConfig(id = "a", name = "Woonkamer", host = "10.0.0.2")
        val b = ReceiverConfig(id = "b", name = "Zolder", host = "10.0.0.3", httpPort = 8080, username = "root", password = "x")
        val json = ReceiverShare.json(AppSettings(receivers = listOf(a, b), activeReceiverId = "b"))
        assertEquals("b", json["activeId"]!!.jsonPrimitive.content)
        val list = json["receivers"] as JsonArray
        assertEquals(2, list.size)
        val second = list[1] as JsonObject
        assertEquals("10.0.0.3", second["host"]!!.jsonPrimitive.content)
        assertEquals("8080", second["httpPort"]!!.jsonPrimitive.content)
        assertEquals("root", second["username"]!!.jsonPrimitive.content)
        assertTrue(second.containsKey("password"))
    }

    @Test
    fun skipsReceiversWithoutHost() {
        val json = ReceiverShare.json(AppSettings(receivers = listOf(ReceiverConfig(id = "x", host = ""))))
        assertEquals(0, (json["receivers"] as JsonArray).size)
    }

    @Test
    fun protocolAdvertisesTheReceiverState() {
        assertEquals("state.receiver", CompanionProtocol.TYPE_STATE_RECEIVER)
        assertEquals("cmd.receiver", CompanionProtocol.TYPE_CMD_RECEIVER)
    }
}

class CompanionSearchProtocolTest {
    @Test
    fun featuresCarryTheTorrentsFlag() {
        assertEquals("true", CompanionSearchHost.featuresJson(true)["torrents"]!!.jsonPrimitive.content)
        assertEquals("false", CompanionSearchHost.featuresJson(false)["torrents"]!!.jsonPrimitive.content)
    }

    @Test
    fun searchTypesMatchTheCompanion() {
        assertEquals("cmd.search", CompanionProtocol.TYPE_CMD_SEARCH)
        assertEquals("cmd.search.open", CompanionProtocol.TYPE_CMD_SEARCH_OPEN)
        assertEquals("state.search", CompanionProtocol.TYPE_STATE_SEARCH)
        assertEquals("state.features", CompanionProtocol.TYPE_STATE_FEATURES)
    }
}
