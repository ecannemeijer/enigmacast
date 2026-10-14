package com.enigmacast.domain.xtream

import com.enigmacast.data.xtream.IptvPlaylistLogin
import com.enigmacast.domain.model.IptvPlaylistKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uPlaylistTest {
    private fun parse(text: String) = mutableListOf<M3uPlaylist.Entry>().also { out -> M3uPlaylist.parse(text.lineSequence()) { out += it } }

    @Test fun readsGroupLogoNameAndSection() {
        val list = parse(
            """
            ﻿#EXTM3U
            #EXTINF:-1 tvg-id="npo1" tvg-logo="http://logo/npo1.png" group-title="NL | Algemeen",NPO 1, HD
            http://example.invalid/live/u/p/1.ts
            #EXTINF:-1 group-title="Films",A Movie
            http://example.invalid/movie/u/p/7.mkv
            #EXTINF:0,No Group
            #EXTGRP:Radio
            http://example.invalid/radio.mp3
            #EXTINF:-1 group-title="Series",Show S01E01
            http://example.invalid/series/u/p/9.mp4
            """.trimIndent(),
        )
        assertEquals(4, list.size)
        assertEquals("NPO 1, HD", list[0].name)
        assertEquals("NL | Algemeen", list[0].group)
        assertEquals("http://logo/npo1.png", list[0].logo)
        assertEquals(XtreamSection.LIVE, list[0].section)
        assertEquals(XtreamSection.MOVIES, list[1].section)
        assertEquals("Radio", list[2].group)
        assertEquals("No Group", list[2].name)
        assertEquals(XtreamSection.SERIES, list[3].section)
        assertEquals(4, list[3].number)
    }

    @Test fun urlWithoutInfoIsSkipped() {
        assertTrue(parse("#EXTM3U\nhttp://x.invalid/a.ts").isEmpty())
    }

    @Test fun xtreamItemFromStreamObject() {
        val o = Json.parseToJsonElement("""{"stream_id":12,"name":"Ch","stream_icon":"i","category_id":"3","num":5}""").jsonObject
        val item = XtreamJson.item(o, XtreamSection.LIVE)!!
        assertEquals("12", item.id); assertEquals("3", item.categoryId); assertEquals("Ch", item.name)
        assertEquals("3", XtreamJson.category(Json.parseToJsonElement("""{"category_id":"3","category_name":"News"}""").jsonObject)!!.id)
    }

    @Test fun loginNeverInToStringAndBareHostGetsHttp() {
        val login = IptvPlaylistLogin(server = "tv.example.invalid:8080", username = "u", password = "secretpw")
        assertFalse(login.toString().contains("secretpw"))
        val acc = login.xtreamAccount()!!
        assertEquals("http://tv.example.invalid:8080", acc.server)
        assertTrue(login.configured(IptvPlaylistKind.XTREAM))
        assertFalse(IptvPlaylistLogin(m3uUrl = "ftp://x").configured(IptvPlaylistKind.M3U))
        assertNull(IptvPlaylistLogin().xtreamAccount())
    }
}
