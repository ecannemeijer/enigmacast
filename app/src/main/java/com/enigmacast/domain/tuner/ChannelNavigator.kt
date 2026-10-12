package com.enigmacast.domain.tuner

import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.service.BouquetChannels

/** Channel up/down and number entry over the current bouquet, for direct streams. */
object ChannelNavigator {
    fun numberOf(channels: List<BouquetEntry>, entry: BouquetEntry): Int {
        if (entry.number > 0) return entry.number
        val exact = channels.indexOfFirst { it.reference == entry.reference }
        if (exact >= 0) return exact + 1
        val index = channels.indexOfFirst { BouquetChannels.sameService(it.reference, entry.reference) }
        return if (index >= 0) index + 1 else 0
    }

    /**
     * OpenWebif repeats one `pos` for a run of internet streams after a numbered marker.
     * A higher number is kept, including a gap. A number that does not rise continues
     * from the previous channel. Entries without a number, such as markers, are left alone.
     */
    fun increasingNumbers(channels: List<BouquetEntry>): List<BouquetEntry> {
        var last = 0
        return channels.map { entry ->
            if (entry.number <= 0) return@map entry
            val number = if (entry.number > last) entry.number else last + 1
            last = number
            if (number == entry.number) entry else entry.copy(number = number)
        }
    }

    /** Next ([delta] > 0) or previous channel, wrapping at the ends of the list. */
    fun step(channels: List<BouquetEntry>, currentReference: String, delta: Int): BouquetEntry? {
        if (channels.isEmpty() || delta == 0) return null
        val exact = channels.indexOfFirst { it.reference == currentReference }
        val start = if (exact >= 0) {
            exact
        } else {
            channels.indexOfFirst { BouquetChannels.sameService(it.reference, currentReference) }
        }
        if (start < 0) return channels.firstOrNull { playable(it) }
        val size = channels.size
        var cursor = start
        repeat(size - 1) {
            cursor = Math.floorMod(cursor + delta, size)
            val candidate = channels[cursor]
            if (playable(candidate)) return candidate
        }
        return null
    }

    private fun playable(entry: BouquetEntry): Boolean =
        BouquetChannels.isPlayableChannel(entry.reference) && !BouquetChannels.isSectionLabel(entry.name)

    fun byNumber(channels: List<BouquetEntry>, number: Int): BouquetEntry? {
        if (number <= 0) return null
        channels.firstOrNull { it.number == number }?.let { return it }
        if (channels.any { it.number > 0 }) return null
        return channels.getOrNull(number - 1)
    }
}

/** Collects digits typed on the remote; four digits or a pause ends the entry. */
class NumberEntry(private val maxDigits: Int = 4) {
    var digits: String = ""
        private set

    /** Returns true when the entry is complete and should be applied now. */
    fun push(digit: Int): Boolean {
        if (digit !in 0..9) return false
        if (digits.isEmpty() && digit == 0) return false
        digits += digit
        return digits.length >= maxDigits
    }

    fun take(): Int? {
        val value = digits.toIntOrNull()
        digits = ""
        return value
    }

    fun clear() {
        digits = ""
    }
}
