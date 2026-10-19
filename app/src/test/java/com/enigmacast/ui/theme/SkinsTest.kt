package com.enigmacast.ui.theme

import com.enigmacast.domain.model.OsdSkinId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SkinsTest {
    @After fun reset() = ActiveSkin.apply(OsdSkinId.STANDARD)

    @Test
    fun everySkinIdHasExactlyOneSkin() {
        assertEquals(OsdSkinId.entries.toSet(), Skins.all.map { it.id }.toSet())
        assertEquals(OsdSkinId.entries.size, Skins.all.size)
    }

    @Test
    fun theDefaultSkinKeepsTheEnigmaCastColours() {
        ActiveSkin.apply(OsdSkinId.STANDARD)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF4188FF), EnigmaColors.Accent)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF080D1C), EnigmaColors.Canvas)
    }

    @Test
    fun applyingASkinRepaintsTheTokens() {
        ActiveSkin.apply(OsdSkinId.OPENPLI)
        assertEquals(OsdLayout.PLI_BAR, ActiveSkin.current.osd)
        assertEquals(Skins.OpenPli.palette.accent, EnigmaColors.Accent)
        assertNotEquals(Skins.EnigmaCast.palette.canvas, EnigmaColors.Canvas)
    }

    @Test
    fun cornersFollowTheSkin() {
        ActiveSkin.apply(OsdSkinId.METRIX)
        assertEquals(0f, ActiveSkin.current.cornerScale)
        ActiveSkin.apply(OsdSkinId.AURORA)
        assertEquals(1.4f, ActiveSkin.current.cornerScale)
    }

    @Test
    fun layoutsOfFlatSkinsAreDistinct() {
        val flat = Skins.all.filter { it.glass == null }
        assertEquals(flat.size, flat.map { it.osd }.toSet().size)
    }

    @Test
    fun glassSkinsShareTheGlassOsdAndCarryTheirOwnBackdrop() {
        val glass = Skins.all.filter { it.glass != null }
        assertEquals(2, glass.size)
        assertEquals(setOf(OsdLayout.GLASS), glass.map { it.osd }.toSet())
        assertEquals(2, glass.map { it.glass!!.backdrop }.toSet().size)
        assertEquals(setOf(Skins.Inter), glass.map { it.fontFamily }.toSet())
        assertEquals(null, Skins.EnigmaCast.glass)
        assertEquals(null, Skins.EnigmaCast.fontFamily)
    }

    @Test
    fun openPliUsesThePliHdSkinColours() {
        val p = Skins.OpenPli.palette
        assertEquals(androidx.compose.ui.graphics.Color(0xFF101214), p.canvas)   // noTransBG
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFCC000), p.onFocus)  // selectedFG
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF0F0F0), p.text)     // foreground
        assertEquals(androidx.compose.ui.graphics.Color(0xFFAAAAAA), p.muted)    // grey
        assertEquals(0f, Skins.OpenPli.cornerScale)
        assertEquals(6, p.focusBrush?.size)
    }
}
