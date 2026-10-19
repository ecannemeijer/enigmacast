package com.enigmacast.domain.soccer

/**
 * Countries the user can follow, with the competitions the football-data.org free plan serves.
 * Checked with the key on 2026-10-12: /v4/competitions listed exactly these codes, and each
 * returned matches (HTTP 200); BL2 and other codes answer 403.
 */
object SoccerCountries {
    data class Country(val id: String, val competitions: List<String>)

    val ALL = listOf(
        Country("NL", listOf("DED")),
        Country("ENG", listOf("PL", "ELC")),
        Country("DE", listOf("BL1")),
        Country("ES", listOf("PD")),
        Country("IT", listOf("SA")),
        Country("FR", listOf("FL1")),
        Country("PT", listOf("PPL")),
        Country("BR", listOf("BSA", "CLI")),
        Country("EU", listOf("CL", "EC")),
        Country("WORLD", listOf("WC")),
    )

    const val DEFAULT = "NL"

    fun byId(id: String?): Country = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT }

    /**
     * Countries with at least one competition the key can read. [available] null (not yet
     * checked) keeps the full list; competitions outside the plan are dropped from each country.
     */
    fun offered(available: Set<String>?): List<Country> =
        if (available == null) ALL else ALL.mapNotNull { c -> c.competitions.filter { it in available }.takeIf { it.isNotEmpty() }?.let { c.copy(competitions = it) } }

    /** The region [id] with the competitions the key reads; the full region before /v4/competitions answered. */
    fun resolve(id: String, available: Set<String>?): Country = offered(available).firstOrNull { it.id == id } ?: byId(id)

    /** Default from an ISO-3166 country code (TV locale or detected country); falls back to the Netherlands. */
    fun defaultFor(iso: String?): String = when (iso?.uppercase()) {
        "GB", "UK" -> "ENG"
        "DE", "AT" -> "DE"
        "ES" -> "ES"
        "IT" -> "IT"
        "FR" -> "FR"
        "PT" -> "PT"
        "BR" -> "BR"
        else -> DEFAULT
    }
}
