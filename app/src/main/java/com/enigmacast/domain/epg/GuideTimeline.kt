package com.enigmacast.domain.epg

import com.enigmacast.domain.tuner.NowEvents

/**
 * One block in a channel's row of the programme guide: a programme, or a stretch
 * without EPG data ([event] null) so the row stays navigable.
 */
data class GuideCell(val event: GuideEvent?, val startSec: Long, val endSec: Long) {
    fun contains(sec: Long): Boolean = sec in startSec until endSec
}

/** Where the guide's highlight is: a channel row, a moment in time and the first visible time. */
data class GuideCursor(val row: Int = 0, val focusSec: Long = 0L, val windowStartSec: Long = 0L)

/** Result of a Left/Right move; [atLeftEdge] when Left was pressed on the programme on air now. */
data class GuideMove(val cursor: GuideCursor, val atLeftEdge: Boolean = false)

object GuideTimeline {
    /** Programmes are laid out on half-hour columns. */
    const val SLOT_SEC = 30 * 60L

    /** How much time the grid shows at once. */
    const val WINDOW_SEC = 2 * 60 * 60L

    /** How far ahead the guide can be scrolled. */
    const val HORIZON_SEC = 7 * 24 * 60 * 60L

    fun slotFloor(sec: Long): Long = sec - Math.floorMod(sec, SLOT_SEC)

    /**
     * The row between [fromSec] and [toSec]: programmes clipped to that stretch, sorted,
     * overlaps resolved in favour of the earlier one, and gaps filled with empty cells
     * that end on half-hour marks.
     */
    fun cells(events: List<GuideEvent>, fromSec: Long, toSec: Long): List<GuideCell> {
        if (toSec <= fromSec) return emptyList()
        val result = mutableListOf<GuideCell>()
        var cursor = fromSec
        for (event in events.sortedBy { it.beginEpochSec }) {
            if (event.durationSec <= 0 || event.endEpochSec <= cursor || event.beginEpochSec >= toSec) continue
            val start = maxOf(event.beginEpochSec, cursor)
            if (start > cursor) fillGap(result, cursor, start)
            val end = minOf(event.endEpochSec, toSec)
            result += GuideCell(event, start, end)
            cursor = end
            if (cursor >= toSec) break
        }
        if (cursor < toSec) fillGap(result, cursor, toSec)
        return result
    }

    private fun fillGap(into: MutableList<GuideCell>, fromSec: Long, toSec: Long) {
        var start = fromSec
        while (start < toSec) {
            val end = minOf(slotFloor(start) + SLOT_SEC, toSec)
            into += GuideCell(null, start, end)
            start = end
        }
    }

    fun cellAt(cells: List<GuideCell>, sec: Long): GuideCell? =
        cells.firstOrNull { it.contains(sec) } ?: cells.firstOrNull { it.startSec >= sec } ?: cells.lastOrNull()

    /** Keeps the cursor inside the guide when time moves on: nothing before the current half hour. */
    fun clamp(cursor: GuideCursor, nowSec: Long, rowCount: Int): GuideCursor {
        val earliest = slotFloor(nowSec)
        return cursor.copy(
            row = if (rowCount <= 0) 0 else cursor.row.coerceIn(0, rowCount - 1),
            focusSec = maxOf(cursor.focusSec, nowSec),
            windowStartSec = maxOf(cursor.windowStartSec, earliest),
        )
    }

    /**
     * Right goes to the next programme (or the next empty half hour) and scrolls the time
     * window once that programme starts beyond it. Left goes back; on the programme on air
     * now it reports the left edge, where the bouquet column opens.
     */
    fun moveHorizontal(row: List<GuideEvent>, cursor: GuideCursor, direction: Int, nowSec: Long): GuideMove {
        val earliest = slotFloor(nowSec)
        val cells = cells(row, earliest, earliest + HORIZON_SEC)
        val current = cellAt(cells, cursor.focusSec) ?: return GuideMove(cursor, atLeftEdge = direction < 0)
        val index = cells.indexOf(current)
        if (direction > 0) {
            val next = cells.getOrNull(index + 1) ?: return GuideMove(cursor)
            var window = cursor.windowStartSec
            if (next.startSec >= window + WINDOW_SEC) window = maxOf(earliest, slotFloor(next.startSec) - SLOT_SEC)
            return GuideMove(cursor.copy(focusSec = next.startSec, windowStartSec = window))
        }
        if (current.startSec <= maxOf(earliest, nowSec) || index == 0) return GuideMove(cursor, atLeftEdge = true)
        val previous = cells[index - 1]
        val focus = maxOf(previous.startSec, nowSec)
        var window = cursor.windowStartSec
        if (focus < window) window = maxOf(earliest, slotFloor(focus))
        return GuideMove(cursor.copy(focusSec = focus, windowStartSec = window))
    }

    /**
     * Up/Down keep the moment in time, so the highlight does not drift while moving through channels.
     * A single step past either end wraps to the other end; a page jump stays inside the list.
     */
    fun moveVertical(cursor: GuideCursor, delta: Int, rowCount: Int): GuideCursor {
        if (rowCount <= 0) return cursor
        val row = when {
            delta == -1 && cursor.row == 0 -> rowCount - 1
            delta == 1 && cursor.row == rowCount - 1 -> 0
            else -> (cursor.row + delta).coerceIn(0, rowCount - 1)
        }
        return cursor.copy(row = row)
    }

    /** The cell the guide highlights in one channel's row. */
    fun focusedCell(row: List<GuideEvent>, cursor: GuideCursor, nowSec: Long): GuideCell? {
        val earliest = slotFloor(nowSec)
        val focus = maxOf(cursor.focusSec, nowSec)
        return cellAt(cells(row, earliest, maxOf(focus, cursor.windowStartSec + WINDOW_SEC) + SLOT_SEC), focus)
    }

    /** Adds freshly loaded programmes to the per-channel lists; newer data wins for the same start time. */
    fun merge(current: Map<String, List<GuideEvent>>, events: List<GuideEvent>): Map<String, List<GuideEvent>> {
        if (events.isEmpty()) return current
        val result = current.toMutableMap()
        events.groupBy { NowEvents.key(it.reference) }.forEach { (key, fresh) ->
            result[key] = (fresh + current[key].orEmpty()).distinctBy { it.beginEpochSec }.sortedBy { it.beginEpochSec }
        }
        return result
    }

    /** The 3-hour blocks ([CHUNK_SEC]) needed to show the window plus a block of look-ahead. */
    fun chunksFor(windowStartSec: Long, chunkSec: Long = CHUNK_SEC): List<Long> {
        val first = windowStartSec - Math.floorMod(windowStartSec, chunkSec)
        val last = windowStartSec + WINDOW_SEC + chunkSec
        return generateSequence(first) { it + chunkSec }.takeWhile { it < last }.toList()
    }

    const val CHUNK_SEC = 3 * 60 * 60L
}
