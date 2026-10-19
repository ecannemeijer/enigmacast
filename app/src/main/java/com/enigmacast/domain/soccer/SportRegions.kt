package com.enigmacast.domain.soccer

/**
 * The Sport landing page: one card per country or region the existing data sources serve
 * ([SoccerCountries]: football-data.org free plan, mirrored by PitchAPI in PitchStats.leaguesFor).
 * Picking one sets the followed country, so the Soccer page shows exactly its competitions.
 * Africa and Asia have no card: neither source offers a competition there on the current plan.
 */
object SportRegions {
    /** Decorative flag / federation colours (ARGB) for the hero glow; not theme colours. */
    data class Look(val primary: Long, val secondary: Long)

    data class Card(
        val id: String,
        val competitions: List<String>,
        /** Competition whose emblem is the card's and hero's logo. */
        val logoCompetition: String,
        /** Emblem URL from football-data.org /v4/competitions, or "" before it is known. */
        val emblem: String,
        /** Display names of [competitions], from the API when known. */
        val competitionNames: List<String>,
        val look: Look,
    )

    private val LOOKS = mapOf(
        "NL" to Look(0xFFAE1C28, 0xFF21468B),
        "ENG" to Look(0xFFCE1124, 0xFF1B2A6B),
        "DE" to Look(0xFFDD0000, 0xFFFFCE00),
        "ES" to Look(0xFFAA151B, 0xFFF1BF00),
        "IT" to Look(0xFF009246, 0xFFCE2B37),
        "FR" to Look(0xFF0055A4, 0xFFEF4135),
        "PT" to Look(0xFF006600, 0xFFFF0000),
        "BR" to Look(0xFF009C3B, 0xFFFFDF00),
        "EU" to Look(0xFF003399, 0xFFFFCC00),
        "WORLD" to Look(0xFF0E7C86, 0xFFD4AF37),
    )

    /** Names for the cards before /v4/competitions answered (football-data.org's own names differ a little). */
    val KNOWN_NAMES = mapOf(
        "DED" to "Eredivisie",
        "PL" to "Premier League",
        "ELC" to "Championship",
        "BL1" to "Bundesliga",
        "PD" to "LaLiga",
        "SA" to "Serie A",
        "FL1" to "Ligue 1",
        "PPL" to "Primeira Liga",
        "BSA" to "Série A",
        "CLI" to "Copa Libertadores",
        "CL" to "UEFA Champions League",
        "EC" to "UEFA EURO",
        "WC" to "FIFA World Cup",
    )

    /**
     * The cards in landing order. [available] null (not checked yet) keeps every region;
     * [competitions] (null before the first answer) supplies emblems and names.
     */
    fun cards(available: Set<String>?, competitions: List<Competition>?): List<Card> {
        val byCode = competitions.orEmpty().associateBy { it.code }
        return SoccerCountries.offered(available).map { country ->
            val logo = country.competitions.first()
            Card(
                id = country.id,
                competitions = country.competitions,
                logoCompetition = logo,
                emblem = byCode[logo]?.emblem.orEmpty(),
                competitionNames = country.competitions.map { code -> KNOWN_NAMES[code] ?: byCode[code]?.name?.takeIf { it.isNotBlank() } ?: code },
                look = LOOKS[country.id] ?: Look(0xFF1B4D3E, 0xFF2E7D32),
            )
        }
    }

    /** Initial focus: the followed (last chosen) region, else the first card. */
    fun initialIndex(cards: List<Card>, lastId: String?): Int =
        cards.indexOfFirst { it.id == lastId }.takeIf { it >= 0 } ?: 0
}
