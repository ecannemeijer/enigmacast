package com.enigmacast.domain.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStyleTest {
    @Test fun defaultIsMediumWhiteWithoutBox() {
        assertEquals(SubtitleStyle(SubtitleSize.MEDIUM, SubtitleColor.WHITE, SubtitleBackground.NONE), SubtitleStyle.DEFAULT)
    }

    @Test fun encodeDecodeRoundTrip() {
        for (size in SubtitleSize.entries) for (color in SubtitleColor.entries) for (bg in SubtitleBackground.entries) {
            val style = SubtitleStyle(size, color, bg)
            assertEquals(style, SubtitleStyle.decode(style.encode()))
        }
    }

    @Test fun damagedValueFallsBackPerPart() {
        assertEquals(SubtitleStyle.DEFAULT, SubtitleStyle.decode(null))
        assertEquals(SubtitleStyle.DEFAULT, SubtitleStyle.decode(""))
        assertEquals(SubtitleStyle(SubtitleSize.LARGE), SubtitleStyle.decode("LARGE|PINK"))
        assertEquals(SubtitleStyle(color = SubtitleColor.YELLOW, background = SubtitleBackground.BOX), SubtitleStyle.decode("HUGE|YELLOW|BOX"))
    }

    @Test fun cycleWraps() {
        val sizes = SubtitleSize.entries
        assertEquals(SubtitleSize.LARGE, SubtitleStyle.cycle(sizes, SubtitleSize.MEDIUM, 1))
        assertEquals(SubtitleSize.SMALL, SubtitleStyle.cycle(sizes, SubtitleSize.LARGE, 1))
        assertEquals(SubtitleSize.LARGE, SubtitleStyle.cycle(sizes, SubtitleSize.SMALL, -1))
    }

    @Test fun sizesGrowAndScaleWithTheVideo() {
        val h = 1080f
        val small = SubtitleStyle(SubtitleSize.SMALL).textSizePx(h)
        val medium = SubtitleStyle(SubtitleSize.MEDIUM).textSizePx(h)
        val large = SubtitleStyle(SubtitleSize.LARGE).textSizePx(h)
        assertTrue(small < medium && medium < large)
        // SMALL is about the old fixed 42 px look on a 1080p screen.
        assertEquals(43f, small, 1.5f)
        assertEquals(12f, SubtitleStyle(SubtitleSize.SMALL).textSizePx(100f), 0f)
    }

    @Test fun wrapKeepsBreaksAndLongWords() {
        val measure = { s: String -> s.length.toFloat() }
        assertEquals(listOf("one two", "three"), SubtitleStyle.wrap("one two three", 8f, measure))
        assertEquals(listOf("a", "b c"), SubtitleStyle.wrap("a\r\nb c", 20f, measure))
        assertEquals(listOf("x", "supercalifragilistic", "y"), SubtitleStyle.wrap("x supercalifragilistic y", 5f, measure))
        assertEquals(emptyList<String>(), SubtitleStyle.wrap(" \n ", 5f, measure))
    }
}
