package com.enigmacast.playback

import com.enigmacast.domain.osdinfo.Scrambling
import com.enigmacast.domain.osdinfo.TsStreamFacts

/**
 * Looks at the MPEG-TS bytes of the receiver's stream as they arrive, without changing them:
 * PAT → PMT (CA descriptors with the CAIDs, teletext, DVB subtitles, AC-3) and the
 * `transport_scrambling_control` bits of the audio/video packets. Packets that are still
 * scrambled mean the receiver did not decrypt this channel for our stream.
 *
 * Only headers and the PAT/PMT sections are parsed (CRC-checked), so the cost is a few
 * operations per 188-byte packet. [feed] runs on the loader thread; [onChange] is called on
 * that thread whenever the facts change.
 */
class TsInspector(private val onChange: (TsStreamFacts) -> Unit = {}) {
    @Volatile
    var facts: TsStreamFacts = TsStreamFacts()
        private set

    private val packet = ByteArray(PACKET)
    private var filled = 0
    private var pmtPid = -1
    private val esPids = HashSet<Int>()
    private val assemblers = HashMap<Int, SectionAssembler>()
    private var scrambled = 0
    private var clear = 0

    /** A new stream: forget everything. */
    @Synchronized
    fun reset() {
        filled = 0
        pmtPid = -1
        esPids.clear()
        assemblers.clear()
        scrambled = 0
        clear = 0
        publish(TsStreamFacts())
    }

    /** The same stream reconnected: drop the half packet, keep what is known. */
    @Synchronized
    fun resync() {
        filled = 0
        assemblers.values.forEach { it.stop() }
    }

    @Synchronized
    fun feed(data: ByteArray, offset: Int, length: Int) {
        var index = offset
        val end = offset + length
        while (index < end) {
            if (filled == 0 && data[index] != SYNC) {
                index++
                continue
            }
            val count = minOf(PACKET - filled, end - index)
            System.arraycopy(data, index, packet, filled, count)
            filled += count
            index += count
            if (filled == PACKET) {
                filled = 0
                onPacket()
            }
        }
    }

    private fun onPacket() {
        val b1 = packet[1].toInt() and 0xFF
        if (b1 and 0x80 != 0) return // transport error
        val pid = ((b1 and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
        if (pid == NULL_PID) return
        val b3 = packet[3].toInt() and 0xFF
        val adaptation = (b3 shr 4) and 0x3
        if (adaptation == 0 || adaptation == 2) return // no payload
        var start = 4
        if (adaptation == 3) start += 1 + (packet[4].toInt() and 0xFF)
        if (start >= PACKET) return
        val unitStart = b1 and 0x40 != 0
        when {
            pid == PAT_PID || pid == pmtPid -> section(pid, unitStart, start)
            isElementary(pid) -> count((b3 shr 6) and 0x3 != 0)
        }
    }

    private fun isElementary(pid: Int): Boolean =
        if (esPids.isNotEmpty()) pid in esPids else pid >= 0x20 && pid != pmtPid

    private fun count(isScrambled: Boolean) {
        if (isScrambled) scrambled++ else clear++
        if (scrambled + clear >= WINDOW) {
            scrambled /= 2
            clear /= 2
        }
        val total = scrambled + clear
        val state = when {
            total < MIN_PACKETS -> Scrambling.UNKNOWN
            scrambled * 5 > total -> Scrambling.SCRAMBLED
            else -> Scrambling.CLEAR
        }
        if (state != facts.scrambling) publish(facts.copy(scrambling = state))
    }

    private fun section(pid: Int, unitStart: Boolean, payloadStart: Int) {
        val assembler = assemblers.getOrPut(pid) { SectionAssembler() }
        var position = payloadStart
        if (unitStart) {
            val pointer = packet[position].toInt() and 0xFF
            position++
            if (assembler.active) {
                assembler.append(packet, position, minOf(pointer, PACKET - position))?.let { handleSection(pid, it) }
            }
            position += pointer
            if (position >= PACKET) return
            assembler.begin()
        } else if (!assembler.active) {
            return
        }
        assembler.append(packet, position, PACKET - position)?.let { handleSection(pid, it) }
    }

    private fun handleSection(pid: Int, section: ByteArray) {
        if (Crc32Mpeg.of(section, 0, section.size) != 0) return
        val table = section[0].toInt() and 0xFF
        when {
            pid == PAT_PID && table == 0x00 -> onPat(section)
            pid == pmtPid && table == 0x02 -> onPmt(section)
        }
    }

    private fun onPat(section: ByteArray) {
        var index = 8
        val end = section.size - 4
        while (index + 4 <= end) {
            val program = ((section[index].toInt() and 0xFF) shl 8) or (section[index + 1].toInt() and 0xFF)
            val pid = ((section[index + 2].toInt() and 0x1F) shl 8) or (section[index + 3].toInt() and 0xFF)
            if (program != 0) {
                if (pid != pmtPid) {
                    pmtPid = pid
                    esPids.clear()
                    assemblers.remove(pid)
                    scrambled = 0
                    clear = 0
                    publish(TsStreamFacts())
                }
                return
            }
            index += 4
        }
    }

    private fun onPmt(section: ByteArray) {
        val end = section.size - 4
        if (end < 12) return
        val programInfoLength = ((section[10].toInt() and 0x0F) shl 8) or (section[11].toInt() and 0xFF)
        val caids = LinkedHashSet<Int>()
        var teletext = false
        var subtitles = false
        var ac3 = false
        val pids = HashSet<Int>()
        fun descriptors(from: Int, until: Int) {
            var index = from
            while (index + 2 <= until) {
                val tag = section[index].toInt() and 0xFF
                val length = section[index + 1].toInt() and 0xFF
                val body = index + 2
                if (body + length > until) return
                when (tag) {
                    TAG_CA -> if (length >= 4) caids += ((section[body].toInt() and 0xFF) shl 8) or (section[body + 1].toInt() and 0xFF)
                    TAG_TELETEXT, TAG_VBI_TELETEXT -> teletext = true
                    TAG_SUBTITLING -> subtitles = true
                    TAG_AC3, TAG_EAC3 -> ac3 = true
                }
                index = body + length
            }
        }
        val programStart = 12
        descriptors(programStart, minOf(programStart + programInfoLength, end))
        var index = programStart + programInfoLength
        while (index + 5 <= end) {
            val streamType = section[index].toInt() and 0xFF
            val pid = ((section[index + 1].toInt() and 0x1F) shl 8) or (section[index + 2].toInt() and 0xFF)
            val infoLength = ((section[index + 3].toInt() and 0x0F) shl 8) or (section[index + 4].toInt() and 0xFF)
            pids += pid
            if (streamType == STREAM_AC3_ATSC || streamType == STREAM_EAC3_ATSC) ac3 = true
            descriptors(index + 5, minOf(index + 5 + infoLength, end))
            index += 5 + infoLength
        }
        esPids.clear()
        esPids += pids
        publish(
            facts.copy(
                pmtSeen = true,
                caids = caids.toList(),
                teletext = teletext,
                dvbSubtitles = subtitles,
                ac3 = ac3,
            ),
        )
    }

    private fun publish(next: TsStreamFacts) {
        if (next == facts) return
        facts = next
        onChange(next)
    }

    /** Collects one PSI section that may span several packets. */
    private class SectionAssembler {
        private val buffer = ByteArray(MAX_SECTION)
        private var size = 0
        var active = false
            private set

        fun begin() {
            size = 0
            active = true
        }

        fun stop() {
            active = false
            size = 0
        }

        /** Adds bytes; returns the whole section once it is complete. */
        fun append(source: ByteArray, from: Int, count: Int): ByteArray? {
            if (!active || count <= 0) return null
            val room = minOf(count, MAX_SECTION - size)
            System.arraycopy(source, from, buffer, size, room)
            size += room
            if (size >= 1 && (buffer[0].toInt() and 0xFF) == 0xFF) {
                stop() // stuffing
                return null
            }
            if (size < 3) return null
            val total = 3 + (((buffer[1].toInt() and 0x0F) shl 8) or (buffer[2].toInt() and 0xFF))
            if (total > MAX_SECTION) {
                stop()
                return null
            }
            if (size < total) return null
            active = false
            return buffer.copyOf(total)
        }
    }

    private companion object {
        const val PACKET = 188
        const val SYNC: Byte = 0x47
        const val PAT_PID = 0
        const val NULL_PID = 0x1FFF
        const val MAX_SECTION = 1024 + 3
        const val WINDOW = 1000
        const val MIN_PACKETS = 40
        const val TAG_CA = 0x09
        const val TAG_VBI_TELETEXT = 0x46
        const val TAG_TELETEXT = 0x56
        const val TAG_SUBTITLING = 0x59
        const val TAG_AC3 = 0x6A
        const val TAG_EAC3 = 0x7A
        const val STREAM_AC3_ATSC = 0x81
        const val STREAM_EAC3_ATSC = 0x87
    }
}

/** MPEG-2 CRC-32 (poly 0x04C11DB7, no reflection); over a whole PSI section including its CRC it is 0. */
object Crc32Mpeg {
    private val TABLE = IntArray(256) { index ->
        var crc = index shl 24
        repeat(8) { crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        crc
    }

    fun of(data: ByteArray, from: Int, length: Int): Int {
        var crc = -1
        for (i in from until from + length) {
            crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }
}
