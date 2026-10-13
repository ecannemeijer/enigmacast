package com.enigmacast.domain.settingsimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReloadCheckTest {
    private val tv = """
        #NAME User - Bouquets (TV)
        #SERVICE 1:7:1:0:0:0:0:0:0:0:FROM BOUQUET "userbouquet.sat.tv" ORDER BY bouquet
        #SERVICE 1:7:1:0:0:0:0:0:0:0:FROM BOUQUET "userbouquet.enigmacast_iptv_news.tv" ORDER BY bouquet
    """.trimIndent()

    @Test fun expectedFromBouquetsTv() {
        assertEquals(setOf("userbouquet.sat.tv", "userbouquet.enigmacast_iptv_news.tv"), ReloadCheck.expectedFiles(mapOf("bouquets.tv" to tv.toByteArray())))
        assertNull(ReloadCheck.expectedFiles(emptyMap()))
    }

    @Test fun liveWhenJsonListsAllAndNoRemoved() {
        val json = """{"services":[{"servicereference":"1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.sat.tv\" ORDER BY bouquet"},{"servicereference":"1:7:1:0:0:0:0:0:0:0:FROM BOUQUET \"userbouquet.enigmacast_iptv_news.tv\" ORDER BY bouquet"}]}"""
        val expected = setOf("userbouquet.sat.tv", "userbouquet.enigmacast_iptv_news.tv")
        assertTrue(ReloadCheck.isLive(json, expected, listOf("userbouquet.enigmacast_iptv_old.tv")))
        assertFalse(ReloadCheck.isLive(json, expected + "userbouquet.enigmacast_iptv_kids.tv", emptyList()))
        assertFalse(ReloadCheck.isLive(json, expected, listOf("userbouquet.sat.tv")))
    }
}
