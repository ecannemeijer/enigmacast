package com.enigmacast.domain.tvhome

import com.enigmacast.domain.films.TmdbMovie
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.service.BouquetChannels
import com.enigmacast.domain.tuner.ServiceRef

/**
 * Which picture each home-screen card gets. Pure; the publisher fetches and stores the files.
 *
 * Live cards: the receiver picon (satellite) or the playlist logo (IPTV), always copied into the
 * app's own image provider so the launcher never has to reach the receiver, a plain-http host or
 * an address with a login in it. Recording cards: the TMDB backdrop (16:9) or poster (2:3) of the
 * recorded programme, else the channel picon. The app banner only when nothing else is there.
 */
object TvHomeArt {
    /** Bump when the stored card pictures change shape, so existing rows get new art. */
    const val VERSION = 2

    /** Size of a stored live card (16:9, what the launcher shows without cropping). */
    const val CARD_WIDTH = 640
    const val CARD_HEIGHT = 360

    /** Most bytes a logo download may have. */
    const val MAX_LOGO_BYTES = 4 * 1024 * 1024

    /**
     * Picon paths to try for a satellite channel, best first: the path OpenWebif gave for this
     * service in the synced bouquets (the file the app's own channel lists show; on many images a
     * name-based or differently-typed picon), then the one for a same-named channel, then the
     * picon named after the service reference. Without bouquets only the reference picon is left.
     */
    fun piconCandidates(reference: String, name: String, channels: List<BouquetEntry>): List<String> {
        val out = LinkedHashSet<String>()
        channels.firstOrNull { it.picon.isNotBlank() && sameService(it.reference, reference) }
            ?.let { out += it.picon.trim() }
        val wanted = name.trim().lowercase()
        if (wanted.isNotEmpty()) {
            channels.firstOrNull { it.picon.isNotBlank() && it.name.trim().lowercase() == wanted }?.let { out += it.picon.trim() }
        }
        ServiceRef.piconPath(reference)?.let { out += it }
        return out.filter { it.isNotBlank() }
    }

    /** An IPTV logo the app may download itself (http or https); it is never handed to the launcher. */
    fun downloadableLogo(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) return null
        if (value.length < 12 || ' ' in value) return null
        return value
    }

    enum class Shape { WIDE, POSTER }

    data class Picture(val url: String, val shape: Shape)

    /** The TMDB picture for a recording card: backdrop (16:9) first, else poster (2:3); public TMDB image URLs only. */
    fun recordingPicture(movie: TmdbMovie?): Picture? {
        if (movie == null || movie.id == 0) return null
        tmdbImage(movie.backdropPath, "w780")?.let { return Picture(it, Shape.WIDE) }
        tmdbImage(movie.posterPath, "w342")?.let { return Picture(it, Shape.POSTER) }
        return null
    }

    /** Description of a recording card: the recording's own text, else the TMDB overview, then channel · date. */
    fun recordingDescription(own: String, movie: TmdbMovie?, channel: String, date: String?): String {
        val text = own.trim().ifBlank { movie?.takeIf { it.id != 0 }?.overview?.trim().orEmpty() }
        val meta = listOfNotNull(channel.trim().takeIf { it.isNotEmpty() }, date?.takeIf { it.isNotBlank() }).joinToString(" · ")
        return listOf(meta, text.take(MAX_DESCRIPTION)).filter { it.isNotEmpty() }.joinToString("\n")
    }

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Where a logo of [width]×[height] goes on a [canvasWidth]×[canvasHeight] card: as large as fits
     * inside the margins, aspect kept, centred. Square and tall logos are not cropped by the launcher.
     */
    fun fit(width: Int, height: Int, canvasWidth: Int = CARD_WIDTH, canvasHeight: Int = CARD_HEIGHT): Box {
        val maxW = canvasWidth * 0.78
        val maxH = canvasHeight * 0.70
        if (width <= 0 || height <= 0) return Box(0, 0, 0, 0)
        val scale = minOf(maxW / width, maxH / height)
        val w = Math.round(width * scale).toInt().coerceAtLeast(1)
        val h = Math.round(height * scale).toInt().coerceAtLeast(1)
        val left = (canvasWidth - w) / 2
        val top = (canvasHeight - h) / 2
        return Box(left, top, left + w, top + h)
    }

    /** Same DVB service, ignoring hex case (recent channels and bouquets do not always agree). */
    private fun sameService(left: String, right: String): Boolean =
        BouquetChannels.sameService(left, right) ||
            (!left.contains("://") && !left.contains("%3a", ignoreCase = true) && BouquetChannels.sameService(left.uppercase(), right.uppercase()))

    private fun tmdbImage(path: String?, size: String): String? {
        val value = path?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("https://image.tmdb.org/", true)) return value
        if (!value.startsWith("/") || "://" in value || '?' in value) return null
        return "https://image.tmdb.org/t/p/$size$value"
    }

    private const val MAX_DESCRIPTION = 400
}
