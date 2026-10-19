package com.enigmacast.ui.soccer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportArtTest {
    private val regions = listOf("NL", "ENG", "DE", "ES", "IT", "FR", "PT", "BR", "EU", "WORLD")

    @Test
    fun everyRegionButWorldHasAFlag() {
        regions.filter { it != "WORLD" }.forEach { assertNotNull(it, SportArt.flag(it)) }
        assertNull(SportArt.flag("WORLD"))
        assertEquals(9, regions.mapNotNull { SportArt.flag(it) }.toSet().size)
    }

    @Test
    fun everyRegionHasAPhotoAndUnknownFallsBack() {
        regions.forEach { SportArt.photo(it) }
        assertEquals(SportArt.photo("WORLD"), SportArt.photo("XX"))
    }

    /** Every bundled Sport asset must be credited in NOTICE.md. */
    @Test
    fun noticeCreditsEveryBundledAsset() {
        val res = listOf("src/main/res/drawable-nodpi", "app/src/main/res/drawable-nodpi").map(::File).first { it.isDirectory }
        val notice = listOf("../NOTICE.md", "NOTICE.md").map(::File).first { it.isFile }.readText()
        val assets = res.listFiles().orEmpty().map { it.nameWithoutExtension }.filter { it.startsWith("sport_") }
        assertEquals(13, assets.size)
        assets.filter { it.startsWith("sport_bg_") }.forEach { assertTrue(it, notice.contains(it)) }
        assertTrue(notice.contains("sport_flag_") && notice.contains("Copyright (c) 2013 Panayiotis Lipiridis"))
    }
}
