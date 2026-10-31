package com.enigmacast.domain.films

/** An actor's TMDB page: the actor page of a film or series cast member. */
data class TmdbPerson(
    val id: Int,
    val name: String,
    val biography: String,
    val birthday: String?,
    val deathday: String?,
    val birthPlace: String?,
    val profilePath: String?,
    val knownFor: String?,
    val credits: List<TmdbCredit>,
)

/** One film or series an actor played in (TMDB combined_credits cast). */
data class TmdbCredit(
    val id: Int,
    val isSeries: Boolean,
    val title: String,
    val originalTitle: String,
    /** yyyy-MM-dd (release or first air date), null when unknown. */
    val date: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val overview: String,
    val character: String,
    val popularity: Double,
    val rating: Double,
    val votes: Int,
    val episodes: Int,
) {
    val year: Int? get() = date?.take(4)?.toIntOrNull()
    val key: String get() = (if (isSeries) "tv:" else "movie:") + id
}

object PersonCredits {
    /** Talk shows and award galas list an actor "as Self": noise on the filmography. */
    private val selfRoles = Regex("""^(him|her|them)self\b|^self\b""", RegexOption.IGNORE_CASE)

    /**
     * The filmography: one card per title (a series with several roles once), without self
     * appearances; best known first (popularity, then votes), newest first on a tie.
     */
    fun order(credits: List<TmdbCredit>): List<TmdbCredit> = credits
        .filterNot { selfRoles.containsMatchIn(it.character.trim()) }
        .groupBy { it.key }
        .map { (_, same) -> same.maxBy { it.episodes } }
        .sortedWith(
            compareByDescending<TmdbCredit> { it.popularity }
                .thenByDescending { it.votes }
                .thenByDescending { it.date.orEmpty() },
        )

    /** Age in years on [until] (both yyyy-MM-dd); null when a date is missing. */
    fun age(birthday: String?, until: String): Int? {
        val b = birthday?.split("-")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 } ?: return null
        val t = until.split("-").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 } ?: return null
        var years = t[0] - b[0]
        if (t[1] < b[1] || (t[1] == b[1] && t[2] < b[2])) years--
        return years.takeIf { it in 0..130 }
    }
}
