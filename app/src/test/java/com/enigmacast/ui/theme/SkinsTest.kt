package com.enigmacast.ui.theme

import com.enigmacast.domain.model.OsdSkinId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
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
    fun glassSkinsHaveTheirOwnBannersAndBackdrops() {
        val glass = Skins.all.filter { it.glass != null }
        assertEquals(2, glass.size)
        assertEquals(OsdLayout.ORBIT, Skins.GlassEarth.osd)
        assertEquals(OsdLayout.WAVE, Skins.GlassWave.osd)
        // Every banner layout is used by exactly one skin now.
        assertEquals(Skins.all.size, Skins.all.map { it.osd }.toSet().size)
        assertEquals(OsdLayout.entries.toSet(), Skins.all.map { it.osd }.toSet())
        assertEquals(2, glass.map { it.glass!!.backdrop }.toSet().size)
        assertEquals(setOf(Skins.Inter), glass.map { it.fontFamily }.toSet())
        assertEquals(null, Skins.EnigmaCast.glass)
        assertEquals(Skins.IbmPlexSans, Skins.EnigmaCast.fontFamily)
    }

    @Test
    fun openPliStaysOpenPliLikeWithoutACustomFont() {
        val p = Skins.OpenPli.palette
        // Navy PLi-HD style: glossy blue selection, pale focus text, square corners.
        // Yellow is reserved for the colour-key hints, not the skin accents.
        assertEquals(androidx.compose.ui.graphics.Color(0xFF0A1428), p.canvas)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF0F4FA), p.onFocus)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF0F4FA), p.text)
        assertEquals(0f, Skins.OpenPli.cornerScale)
        assertEquals(6, p.focusBrush?.size)
        assertEquals(null, Skins.OpenPli.fontFamily)
        assertEquals(null, Skins.Metrix.fontFamily)
        val nav = SkinNav.of(Skins.OpenPli)
        assertEquals(0.dp, nav.endRadius)
        assertEquals(1f, nav.focusIconScale)
        assertEquals(1f, nav.focusLabelScale)
        assertEquals(6, nav.cursorFill.size)
    }

    @Test
    fun newSkinsKeepTextReadable() {
        fun lum(c: androidx.compose.ui.graphics.Color) = c.luminance()
        fun ratio(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
            val x = lum(a); val y = lum(b)
            return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
        }
        for (skin in listOf(Skins.Lumen, Skins.CinemaBlack, Skins.Arena, Skins.Starbridge)) {
            val p = skin.palette
            assert(ratio(p.text, p.canvas) >= 7f) { "${skin.id} text" }
            assert(ratio(p.muted, p.card) >= 4.5f) { "${skin.id} muted" }
            assert(ratio(p.onFocus, p.focus) >= 4.5f) { "${skin.id} focus" }
            assert(ratio(p.onAccent, p.accent) >= 3f) { "${skin.id} accent" }
        }
    }

    @Test
    fun everySkinHasNavChromeFromItsPalette() {
        for (skin in Skins.all) {
            val nav = SkinNav.of(skin)
            assert(nav.gradient.size >= 2) { "${skin.id} gradient" }
            assert(nav.cursorFill.isNotEmpty()) { "${skin.id} cursor" }
            assert(nav.selectedBarWidth > 0.dp) { "${skin.id} selected bar" }
            assert(nav.focusIconScale >= 1f && nav.focusIconScale <= 1.12f) { "${skin.id} icon scale" }
            assert(nav.focusLabelScale >= 1f && nav.focusLabelScale <= 1.10f) { "${skin.id} label scale" }
        }
        assertEquals(0.dp, SkinNav.of(Skins.Metrix).endRadius)
        assertEquals(1f, SkinNav.of(Skins.Metrix).focusIconScale)
    }

    @Test
    fun bundledFontsAreAssignedWithoutReuseAcrossFamilies() {
        assertEquals(Skins.IbmPlexSans, Skins.Midnight.fontFamily)
        assertEquals(Skins.IbmPlexSans, Skins.SlateCalm.fontFamily)
        assertEquals(Skins.IbmPlexSans, Skins.CinemaBlack.fontFamily)
        assertEquals(Skins.PlusJakartaSans, Skins.Aurora.fontFamily)
        assertEquals(Skins.PlusJakartaSans, Skins.Lumen.fontFamily)
        assertEquals(Skins.Barlow, Skins.Arena.fontFamily)
        assertEquals(Skins.Exo2, Skins.Starbridge.fontFamily)
        assertEquals(Skins.Inter, Skins.GlassEarth.fontFamily)
        // OpenPLi and Metrix keep the system font so they stay close to the STB originals.
        assertEquals(setOf(Skins.OpenPli, Skins.Metrix), Skins.all.filter { it.fontFamily == null }.toSet())
    }
}
