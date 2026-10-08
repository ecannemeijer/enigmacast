package com.enigmacast.domain.epg

import com.enigmacast.domain.tuner.NowEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTimelineTest {
    private val slot = GuideTimeline.SLOT_SEC

    // 2023-11-14 22:00 UTC, a half-hour mark.
    private val base = 1_700_000_000L - Math.floorMod(1_700_000_000L, slot)

    private fun event(id: Int, startMin: Int, minutes: Int) = GuideEvent(
        eventId = id,
        reference = "1:0:1:A:0:0:0:0:0:0:",
        title = "P$id",
        shortDescription = "",
        plot = "",
        beginEpochSec = base + startMin * 60L,
        durationSec = minutes * 60L,
    )

    @Test
    fun fillsGapsOnHalfHourMarksAndClipsToTheWindow() {
        val events = listOf(event(2, 70, 20), event(1, -15, 45))
        val cells = GuideTimeline.cells(events, base, base + 2 * 3600)
        assertEquals(listOf(1, null, null, 2, null), cells.map { it.event?.eventId })
        assertEquals(base, cells[0].startSec)
        assertEquals(base + 30 * 60, cells[0].endSec)
        assertEquals(base + 60 * 60, cells[2].startSec)
        assertEquals(base + 70 * 60, cells[2].endSec)
        assertEquals(base + 2 * 3600, cells.last().endSec)
    }

    @Test
    fun overlappingProgrammesDoNotOverlapCells() {
        val cells = GuideTimeline.cells(listOf(event(1, 0, 60), event(2, 30, 60)), base, base + 3600 * 2)
        assertEquals(listOf(1, 2, null), cells.map { it.event?.eventId })
        assertEquals(base + 60 * 60, cells[1].startSec)
    }

    @Test
    fun rightMovesProgrammeByProgrammeAndScrollsTheWindow() {
        val now = base + 10 * 60
        val row = listOf(event(1, 0, 30), event(2, 30, 60), event(3, 90, 60), event(4, 150, 30))
        var cursor = GuideCursor(0, now, base)
        cursor = GuideTimeline.moveHorizontal(row, cursor, 1, now).cursor
        assertEquals(base + 30 * 60, cursor.focusSec)
        assertEquals(base, cursor.windowStartSec)
        cursor = GuideTimeline.moveHorizontal(row, cursor, 1, now).cursor
        assertEquals(base + 90 * 60, cursor.focusSec)
        assertEquals(base, cursor.windowStartSec)
        cursor = GuideTimeline.moveHorizontal(row, cursor, 1, now).cursor
        assertEquals(base + 150 * 60, cursor.focusSec)
        assertEquals(base + 120 * 60, cursor.windowStartSec)
    }

    @Test
    fun leftGoesBackAndReportsTheEdgeOnTheProgrammeOnAirNow() {
        val now = base + 10 * 60
        val row = listOf(event(1, 0, 30), event(2, 30, 60), event(3, 90, 60), event(4, 150, 30))
        val far = GuideCursor(0, base + 150 * 60, base + 120 * 60)
        val back = GuideTimeline.moveHorizontal(row, far, -1, now)
        assertFalse(back.atLeftEdge)
        assertEquals(base + 90 * 60, back.cursor.focusSec)
        assertEquals(base + 90 * 60, back.cursor.windowStartSec)
        val second = GuideTimeline.moveHorizontal(row, back.cursor, -1, now)
        assertEquals(base + 30 * 60, second.cursor.focusSec)
        val third = GuideTimeline.moveHorizontal(row, second.cursor, -1, now)
        assertEquals(now, third.cursor.focusSec)
        assertEquals(base, third.cursor.windowStartSec)
        assertTrue(GuideTimeline.moveHorizontal(row, third.cursor, -1, now).atLeftEdge)
    }

    @Test
    fun emptyRowsStillMoveInHalfHours() {
        val now = base + 5 * 60
        val move = GuideTimeline.moveHorizontal(emptyList(), GuideCursor(0, now, base), 1, now)
        assertEquals(base + slot, move.cursor.focusSec)
        assertTrue(GuideTimeline.moveHorizontal(emptyList(), GuideCursor(0, now, base), -1, now).atLeftEdge)
    }

    @Test
    fun verticalMovesKeepTheTimeAndStayInRange() {
        val cursor = GuideCursor(row = 1, focusSec = base + 99, windowStartSec = base)
        assertEquals(0, GuideTimeline.moveVertical(cursor, -5, 10).row)
        assertEquals(9, GuideTimeline.moveVertical(cursor, 20, 10).row)
        assertEquals(base + 99, GuideTimeline.moveVertical(cursor, 1, 10).focusSec)
    }

    @Test
    fun verticalStepWrapsAtTheEnds() {
        val top = GuideCursor(row = 0, focusSec = base, windowStartSec = base)
        val bottom = GuideCursor(row = 9, focusSec = base, windowStartSec = base)
        assertEquals(9, GuideTimeline.moveVertical(top, -1, 10).row)
        assertEquals(0, GuideTimeline.moveVertical(bottom, 1, 10).row)
        assertEquals(base, GuideTimeline.moveVertical(top, -1, 10).focusSec)
        assertEquals(0, GuideTimeline.moveVertical(top, -5, 10).row)
        assertEquals(9, GuideTimeline.moveVertical(bottom, 6, 10).row)
    }

    @Test
    fun clampKeepsTheCursorOutOfThePast() {
        val now = base + 45 * 60
        val clamped = GuideTimeline.clamp(GuideCursor(12, base, base - slot), now, 5)
        assertEquals(4, clamped.row)
        assertEquals(now, clamped.focusSec)
        assertEquals(base + slot, clamped.windowStartSec)
    }

    @Test
    fun cellAtFindsTheCellOrTheNextOne() {
        val cells = GuideTimeline.cells(listOf(event(1, 0, 30)), base, base + 3600)
        assertEquals(1, GuideTimeline.cellAt(cells, base + 60)?.event?.eventId)
        assertNull(GuideTimeline.cellAt(cells, base + 40 * 60)?.event)
    }

    @Test
    fun loadsTheBlocksAroundTheWindow() {
        val chunk = GuideTimeline.CHUNK_SEC
        val start = 10 * chunk + 2 * 3600
        assertEquals(listOf(10 * chunk, 11 * chunk, 12 * chunk), GuideTimeline.chunksFor(start))
        assertEquals(listOf(10 * chunk, 11 * chunk), GuideTimeline.chunksFor(10 * chunk))
    }

    @Test
    fun progressAndAiring() {
        val e = event(1, 0, 60)
        assertTrue(e.isAiring(base + 10))
        assertFalse(e.isAiring(base + 3600))
        assertEquals(0.5f, e.progress(base + 1800), 0.001f)
    }

    @Test
    fun mergeAddsBlocksPerChannelWithoutDuplicates() {
        val other = event(9, 0, 30).copy(reference = "1:0:1:B:0:0:0:0:0:0:")
        val first = GuideTimeline.merge(emptyMap(), listOf(event(1, 0, 30), event(2, 30, 30), other))
        val renamed = event(2, 30, 30).copy(title = "Renamed")
        val merged = GuideTimeline.merge(first, listOf(renamed, event(3, 60, 30)))
        val channel = merged.getValue(NowEvents.key("1:0:1:A:0:0:0:0:0:0:"))
        assertEquals(listOf(1, 2, 3), channel.map { it.eventId })
        assertEquals("Renamed", channel[1].title)
        assertEquals(1, merged.getValue(NowEvents.key("1:0:1:B:0:0:0:0:0:0:")).size)
        assertTrue(GuideTimeline.merge(first, emptyList()) === first)
    }

    @Test
    fun focusedCellFollowsTheCursorAndNeverThePast() {
        val now = base + 40 * 60
        val row = listOf(event(1, 0, 60), event(2, 60, 60))
        assertEquals(1, GuideTimeline.focusedCell(row, GuideCursor(0, base, base), now)?.event?.eventId)
        assertEquals(2, GuideTimeline.focusedCell(row, GuideCursor(0, base + 90 * 60, base), now)?.event?.eventId)
    }
}
