package com.enigmacast.domain.oscam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpDigestTest {
    @Test
    fun rfc2617Example() {
        val header = """Digest realm="testrealm@host.com", qop="auth", nonce="dcd98b7102dd2f0e8b11d0f600bfb0c093", opaque="5ccc069c403ebaf9f0171e9517f40e41""""
        val challenge = HttpDigest.parse(header)!!
        assertEquals("testrealm@host.com", challenge.realm)
        assertEquals("auth", challenge.qop)
        val authorization = HttpDigest.authorization(
            username = "Mufasa",
            password = "Circle Of Life",
            method = "GET",
            uri = "/dir/index.html",
            challenge = challenge,
            nc = 1,
            cnonce = "0a4f113b",
        )
        assertTrue(authorization.startsWith("Digest "))
        assertTrue(authorization.contains("""response="6629fae49393a05397450978507c4ef1""""))
        assertTrue(authorization.contains("""uri="/dir/index.html""""))
        assertTrue(authorization.contains("nc=00000001"))
    }

    @Test
    fun basicChallengeIsNotDigest() {
        assertNull(HttpDigest.parse("""Basic realm="oscam""""))
    }
}

class OscamStatusTest {
    @Test
    fun readersComeFromAnArrayAndASingleObjectAndClientsAreSkipped() {
        val array = """
            {"status":{"client":[
              {"type":"c","name":"user","protocol":"cccam","connection":{"status":"OK"}},
              {"type":"r","name":"upper","protocol":"internal","connection":{"status":"CARDOK","ip":"127.0.0.1"}},
              {"type":"p","name":"peer","protocol":"cccam","connection":{"ip":"10.0.0.2"}}
            ]}}
        """.trimIndent()
        val readers = OscamStatus.readers(array)
        assertEquals(listOf("upper", "peer"), readers.map { it.name })
        assertEquals("CARDOK", readers[0].connection)
        assertEquals("internal", readers[0].protocol)
        assertEquals("10.0.0.2", readers[1].connection)

        val single = """{"status":{"client":{"type":"r","name":"card","protocol":"mouse","connection":"OK"}}}"""
        assertEquals("OK", OscamStatus.readers(single).single().connection)
    }

    @Test
    fun logPageReadsLastIdAndLines() {
        val body = """{"lastid":12,"lines":["reader connected","",{"text":"second"}]}"""
        val page = OscamStatus.logPage(body)
        assertEquals(12L, page.lastId)
        assertEquals(listOf("reader connected", "second"), page.lines)
    }

    @Test
    fun aPageWithoutLastIdKeepsItAbsent() {
        assertNull(OscamStatus.logPage("""{"lines":[]}""").lastId)
        assertEquals(emptyList<String>(), OscamStatus.logPage("not-json").lines)
    }

    @Test
    fun oscam226LogLinesAreBase64InsideTheOscamObjectAndTheHighestIdIsNext() {
        val encoded = java.util.Base64.getEncoder().encodeToString("2026/10/11 05:45:45 found NPO3 HD".toByteArray())
        val body = """{"oscam":{"version":"2.26.07","lines":[{"id":"2319","usr":"dvbapiau","line":"$encoded"}]}}"""
        val page = OscamStatus.logPage(body)
        assertEquals(2319L, page.lastId)
        assertEquals(listOf("2026/10/11 05:45:45 found NPO3 HD"), page.lines)
    }

    @Test
    fun readerNameComesFromRnameWhenNameIsAbsent() {
        val body = """{"oscam":{"status":{"client":{"type":"r","rname_enc":"emulator","protocol":"emu","connection":{"status":"CARDOK","ip":"local"}}}}}"""
        val reader = OscamStatus.readers(body).single()
        assertEquals("emulator", reader.name)
        assertEquals("CARDOK", reader.connection)
    }

    @Test
    fun snapshotReadsVersionUptimeChannelAndTheDvbapiUser() {
        val body = """
            {"oscam":{"version":"2.26.07","uptime":"01:56:27","status":{"client":[
              {"type":"p","rname_enc":"peer","protocol":"cccam_mcs (2.0.11)","connection":{"status":"CONNECTED"},"request":{"chname":"NPO1 HD","ecmtime":"303"}},
              {"type":"c","name_enc":"dvbapiau","protocol":"dvbapi","request":{"chname":"NPO1 HD","answered":"peer","msvalue":"303"}}
            ]}}}
        """.trimIndent()
        val server = OscamStatus.snapshot(body)
        assertEquals("2.26.07", server.version)
        assertEquals("01:56:27", server.uptime)
        assertEquals("NPO1 HD", server.readers.single().channel)
        assertEquals("303", server.readers.single().ecmMs)
        assertEquals("dvbapiau", server.users.single().name)
        assertEquals("peer", server.users.single().answeredBy)
    }
}
