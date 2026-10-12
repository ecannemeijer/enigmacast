package com.enigmacast.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.enigmacast.domain.films.HomeFilm
import com.enigmacast.ui.theme.EnigmaColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object FilmFormat {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun time(epochSec: Long): String = Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(clock)

    /** "RTL 7 · 20:30". */
    fun channelTime(film: HomeFilm): String = "${film.candidate.channelName} · ${time(film.event.beginEpochSec)}"

    /** "20:30 – 22:45". */
    fun slot(film: HomeFilm): String = "${time(film.event.beginEpochSec)} – ${time(film.event.endEpochSec)}"

    fun rating(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}

/** Five stars for a TMDB 0–10 rating, then the number. */
@Composable
internal fun RatingStars(value: Double, size: TextUnit = 14.sp, modifier: Modifier = Modifier) {
    if (value <= 0.0) return
    val filled = kotlin.math.round(value / 2.0).toInt().coerceIn(0, 5)
    val stars = "★".repeat(filled) + "☆".repeat(5 - filled)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stars, color = Gold, fontSize = size)
        Text(FilmFormat.rating(value), color = EnigmaColors.Text, fontSize = size, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun Badge(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = EnigmaColors.OnAccent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(color, RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

internal val Gold = androidx.compose.ui.graphics.Color(0xFFFFC94D)
internal val LiveRed = androidx.compose.ui.graphics.Color(0xFFE5484D)
