package com.enigmacast.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enigmacast.R
import com.enigmacast.domain.films.CountryResolver
import com.enigmacast.ui.theme.ChoiceRow
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.TvTextField

/** Settings › General › Home page: the country for the film rows and an optional own TMDB key. */
@Composable
fun HomeFilmSettingsCard(model: HomeFilmsViewModel = hiltViewModel()) {
    val values by model.homeSettings.values.collectAsStateWithLifecycle()
    val codes = listOf(CountryResolver.AUTO) + CountryResolver.CHOICES
    val labels = listOf(stringResource(R.string.home_country_auto)) + CountryResolver.CHOICES.map { if (it == "GB") "UK" else it }
    SettingsCard(stringResource(R.string.settings_home)) {
        ChoiceRow(
            title = stringResource(R.string.home_country),
            description = stringResource(R.string.home_country_desc),
            options = labels,
            selectedIndex = codes.indexOf(values.country).coerceAtLeast(0),
            onSelect = { index -> model.setCountry(codes[index]) },
        )
        TvTextField(
            label = stringResource(R.string.home_tmdb_key),
            value = values.tmdbKey,
            onValueChange = model::setTmdbKey,
        )
    }
}
