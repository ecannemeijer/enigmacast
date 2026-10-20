package com.enigmacast.domain.profile.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    private fun rows(q: QrCode) = (0 until q.size).map { y -> (0 until q.size).joinToString("") { x -> if (q[x, y]) "1" else "0" } }

    /** Checked once against zxing-cpp, which decodes it to the same text. */
    @Test fun matchesAKnownGoodCode() {
        val expected = listOf(
            "1111111000011001101111111",
            "1000001011111110101000001",
            "1011101011010101001011101",
            "1011101011100000101011101",
            "1011101001110001101011101",
            "1000001000001001001000001",
            "1111111010101010101111111",
            "0000000010111011100000000",
            "1000001011011000011001110",
            "0011010011110100100111000",
            "1001101010000111110011011",
            "0101010100111101001001011",
            "0010101111001101001001001",
            "1100110010100001000101100",
            "1011001010111001011111011",
            "1000100110010001101101101",
            "1001111001010001111111100",
            "0000000011001111100011100",
            "1111111001110000101010001",
            "1000001000101100100010010",
            "1011101001001011111111110",
            "1011101001100011101000101",
            "1011101000011001000101001",
            "1000001001110011101111001",
            "1111111011100111111010001",
        )
        assertEquals(expected, rows(QrCode.encode("hello world 123")))
    }

    @Test fun picksTheSmallestVersion() {
        assertEquals(1, QrCode.encode("A").version)
        assertEquals(4, QrCode.encode("http://192.168.1.42:8765/upload?token=abcDEF0123456789_-xyzQ").version)
        assertEquals(10, QrCode.encode("w".repeat(200)).version)
        assertEquals(57, QrCode.encode("w".repeat(200)).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesTooMuchData() { QrCode.encode("x".repeat(300)) }

    @Test fun hasFinderPatternsAndTimingLines() {
        val q = QrCode.encode("http://10.0.0.5:8765/upload?token=" + "k".repeat(22))
        for ((cx, cy) in listOf(3 to 3, q.size - 4 to 3, 3 to q.size - 4)) {
            assertTrue(q[cx, cy]); assertTrue(!q[cx + 2, cy]); assertTrue(q[cx + 3, cy])
        }
        for (i in 8 until q.size - 8) assertEquals(i % 2 == 0, q[i, 6])
        assertTrue("dark module", q[8, q.size - 8])
    }

    @Test fun reedSolomonOfTheStandardExample() {
        // ISO/IEC 18004 annex I: "01234567", version 1-M.
        val data = intArrayOf(16, 32, 12, 86, 97, 128, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17)
        val ecc = ReedSolomon.remainder(data, ReedSolomon.divisor(10))
        assertEquals(listOf(165, 36, 212, 193, 237, 54, 199, 135, 44, 85), ecc.toList())
    }
}
