package com.enigmacast.domain.tuner

import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.service.BouquetChannels

/** Channel up/down and number entry over the current bouquet, for direct streams. */
object ChannelNavigator {
    fun numberOf(channels: List<BouquetEntry>, entry: BouquetEntry): Int {
        if (entry.number > 0) return entry.number
        val index = channels.indexOfFirst { BouquetChannels.sameService(it.reference, entry.reference) }
        return if (index >= 0) index + 1 else 0
    }

    /** Next ([delta] > 0) or previous channel, wrapping at the ends of the list. */
    fun step(channels: List<BouquetEntry>, currentReference: String, delta: Int): BouquetEntry? {
        if (channels.isEmpty()) return null
        val index = channels.indexOfFirst { BouquetChannels.sameService(it.reference, currentReference) }
        if (index < 0) return channels.first()
        val size = channels.size
        return channels[((index + delta) % size + size) % size]
    }

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
