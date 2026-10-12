package com.enigmacast.domain.soccer

/** Table colours: a subtle mark for the title/qualification places, European places and relegation. Approximate per league. */
enum class Zone { TOP, EUROPE, RELEGATION, NONE }

object StandingZones {
    /** (top places, European places after those, relegation places at the bottom). */
    private val RULES = mapOf(
        "PL" to Triple(4, 2, 3),
        "ELC" to Triple(2, 4, 3),
        "BL1" to Triple(4, 2, 3),
        "PD" to Triple(4, 2, 3),
        "SA" to Triple(4, 2, 3),
        "FL1" to Triple(3, 3, 3),
        "DED" to Triple(2, 3, 3),
        "PPL" to Triple(2, 3, 3),
        "BSA" to Triple(6, 6, 4),
        "CL" to Triple(8, 16, 12),
    )

    fun of(code: String, position: Int, size: Int): Zone {
        val (top, europe, bottom) = RULES[code] ?: Triple(1, 0, 0)
        return when {
            position <= top -> Zone.TOP
            position <= top + europe -> Zone.EUROPE
            bottom > 0 && position > size - bottom -> Zone.RELEGATION
            else -> Zone.NONE
        }
    }
}
