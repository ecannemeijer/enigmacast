package com.enigmacast.domain.profile.photo

/**
 * A small QR code encoder (ISO/IEC 18004): byte mode, error correction level M, versions 1–10
 * (up to 213 bytes, plenty for a LAN upload URL). Written here so no QR library is needed.
 */
class QrCode private constructor(val version: Int, val mask: Int, private val modules: Array<BooleanArray>) {
    val size: Int get() = modules.size

    /** True for a dark module; x is the column, y the row. */
    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    companion object {
        const val MAX_VERSION = 10
        // Level M, versions 1..10.
        private val ECC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
        private val ALIGN = arrayOf(
            intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
            intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
        )

        /** Raw data modules of a version (all modules minus function patterns), Nayuki's formula. */
        internal fun rawModules(ver: Int): Int {
            var result = (16 * ver + 128) * ver + 64
            if (ver >= 2) {
                val align = ver / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (ver >= 7) result -= 36
            }
            return result
        }

        internal fun dataCodewords(ver: Int) = rawModules(ver) / 8 - ECC_PER_BLOCK[ver - 1] * BLOCKS[ver - 1]

        /** Smallest version that holds [data]; throws when it is longer than version 10 allows. */
        fun encode(data: ByteArray): QrCode {
            val ver = (1..MAX_VERSION).firstOrNull { v -> 4 + (if (v < 10) 8 else 16) + data.size * 8 <= dataCodewords(v) * 8 }
                ?: throw IllegalArgumentException("too long for a QR code up to version $MAX_VERSION: ${data.size} bytes")
            val capacity = dataCodewords(ver) * 8
            val bits = BitBuffer()
            bits.append(0b0100, 4)
            bits.append(data.size, if (ver < 10) 8 else 16)
            data.forEach { bits.append(it.toInt() and 0xff, 8) }
            bits.append(0, minOf(4, capacity - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { bits.append(pad, 8); pad = pad xor 0xEC xor 0x11 }
            val codewords = interleave(ver, bits.toBytes())
            return Builder(ver).build(codewords)
        }

        fun encode(text: String): QrCode = encode(text.toByteArray(Charsets.UTF_8))

        private fun interleave(ver: Int, data: IntArray): IntArray {
            val blocks = BLOCKS[ver - 1]
            val eccLen = ECC_PER_BLOCK[ver - 1]
            val raw = rawModules(ver) / 8
            val shortBlocks = blocks - raw % blocks
            val shortLen = raw / blocks
            val divisor = ReedSolomon.divisor(eccLen)
            val all = ArrayList<IntArray>()
            var k = 0
            for (i in 0 until blocks) {
                val len = shortLen - eccLen + if (i < shortBlocks) 0 else 1
                val dat = data.copyOfRange(k, k + len)
                k += len
                val ecc = ReedSolomon.remainder(dat, divisor)
                val block = IntArray(shortLen + 1)
                dat.copyInto(block)
                ecc.copyInto(block, shortLen + 1 - eccLen)
                all += block
            }
            val out = ArrayList<Int>(raw)
            for (i in 0..shortLen) for (j in 0 until blocks) {
                if (i != shortLen - eccLen || j >= shortBlocks) out += all[j][i]
            }
            return out.toIntArray()
        }
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size get() = bits.size
        fun append(value: Int, len: Int) { for (i in len - 1 downTo 0) bits += (value ushr i) and 1 == 1 }
        fun toBytes(): IntArray = IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, b -> (acc shl 1) or if (bits[i * 8 + b]) 1 else 0 } }
    }

    private class Builder(val ver: Int) {
        val size = ver * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; function[y][x] = true }

        fun build(codewords: IntArray): QrCode {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val pos = ALIGN[ver - 1]
            for (i in pos.indices) for (j in pos.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == pos.lastIndex) || (i == pos.lastIndex && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(pos[i] + dx, pos[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            format(0)
            versionInfo()
            place(codewords)
            var best = 0
            var bestScore = Int.MAX_VALUE
            for (m in 0 until 8) {
                applyMask(m); format(m)
                val score = penalty()
                if (score < bestScore) { best = m; bestScore = score }
                applyMask(m)
            }
            applyMask(best); format(best)
            return QrCode(ver, best, modules)
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until size && y in 0 until size) {
                    val d = maxOf(Math.abs(dx), Math.abs(dy))
                    set(x, y, d != 2 && d != 4)
                }
            }
        }

        private fun format(mask: Int) {
            val data = (0b00 shl 3) or mask // level M = 00
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 == 1
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            for (i in 0..7) set(size - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun versionInfo() {
            if (ver < 7) return
            var rem = ver
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (ver shl 12) or rem
            for (i in 0 until 18) {
                val dark = (bits ushr i) and 1 == 1
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, dark); set(b, a, dark)
            }
        }

        private fun place(data: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!function[y][x] && i < data.size * 8) {
                        modules[y][x] = (data[i ushr 3] ushr (7 - (i and 7))) and 1 == 1
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(m: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (function[y][x]) continue
                val flip = when (m) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (flip) modules[y][x] = !modules[y][x]
            }
        }

        /** Rules 1, 2 and 4 of the standard's mask penalty (enough to avoid poor masks). */
        private fun penalty(): Int {
            var score = 0
            for (line in 0 until size) {
                var runRow = 1
                var runCol = 1
                for (k in 1 until size) {
                    if (modules[line][k] == modules[line][k - 1]) runRow++ else { if (runRow >= 5) score += runRow - 2; runRow = 1 }
                    if (modules[k][line] == modules[k - 1][line]) runCol++ else { if (runCol >= 5) score += runCol - 2; runCol = 1 }
                }
                if (runRow >= 5) score += runRow - 2
                if (runCol >= 5) score += runCol - 2
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) score += 3
            }
            val dark = modules.sumOf { row -> row.count { it } }
            val total = size * size
            score += (Math.abs(dark * 20 - total * 10) + total - 1) / total * 10
            return score
        }
    }
}

/** GF(256) Reed-Solomon for QR codes (primitive polynomial 0x11D). */
internal object ReedSolomon {
    fun multiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    fun divisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                result[j] = multiply(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = multiply(root, 2)
        }
        return result
    }

    fun remainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = b xor result[0]
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
        }
        return result
    }
}
