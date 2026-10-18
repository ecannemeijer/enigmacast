package com.enigmacast.ui.torrent

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.enigmacast.R
import com.enigmacast.data.torrent.ScraperRepository
import com.enigmacast.data.torrent.ScraperStatus
import com.enigmacast.domain.torrent.ManifestError
import com.enigmacast.ui.theme.CompactButton
import com.enigmacast.ui.theme.EnigmaColors
import com.enigmacast.ui.theme.SettingsCard
import com.enigmacast.ui.theme.TvTextField
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class ScraperSettingsViewModel @Inject constructor(private val repository: ScraperRepository) : ViewModel() {
    val status = repository.status
    /** What is being typed or pasted; the saved URL is never put back in the field (it can hold a key). */
    var input by mutableStateOf("")
        private set
    var masked by mutableStateOf(repository.masked())
        private set

    fun onInput(value: String) { input = value }

    fun save() {
        val value = input
        viewModelScope.launch {
            repository.save(value)
            input = ""
            masked = repository.masked()
        }
    }

    fun recheck() { viewModelScope.launch { repository.check() } }

    fun remove() {
        viewModelScope.launch {
            repository.save("")
            input = ""
            masked = null
        }
    }
}

/** Settings › Scrapers: one Stremio addon (e.g. Torrentio) by its manifest URL, checked on save. */
@Composable
fun ScraperSettingsPanel(contentFocus: FocusRequester, model: ScraperSettingsViewModel = hiltViewModel()) {
    val status by model.status.collectAsStateWithLifecycle()
    Column(Modifier.focusRequester(contentFocus).focusGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard(stringResource(R.string.settings_scrapers)) {
            Text(stringResource(R.string.scraper_help), color = EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Text(
                stringResource(R.string.scraper_current, model.masked ?: stringResource(R.string.scraper_none)),
                color = EnigmaColors.Text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            TvTextField(
                label = stringResource(R.string.scraper_url_label),
                value = model.input,
                onValueChange = model::onInput,
                leftFocus = null,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                CompactButton(stringResource(R.string.scraper_save), onClick = model::save)
                if (model.masked != null) {
                    CompactButton(stringResource(R.string.scraper_check), onClick = model::recheck)
                    CompactButton(stringResource(R.string.scraper_remove), onClick = model::remove, modifier = Modifier.focusProperties { right = FocusRequester.Cancel })
                }
            }
            Text(statusText(status), color = if (status is ScraperStatus.Invalid) EnigmaColors.Danger else EnigmaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun statusText(status: ScraperStatus): String = when (status) {
    ScraperStatus.None -> stringResource(R.string.scraper_status_none)
    ScraperStatus.Checking -> stringResource(R.string.scraper_status_checking)
    is ScraperStatus.Valid -> stringResource(R.string.scraper_status_valid, status.manifest.name, status.manifest.version)
    is ScraperStatus.Invalid -> when (status.error) {
        ManifestError.URL -> stringResource(R.string.scraper_error_url)
        ManifestError.HTTP -> stringResource(R.string.scraper_error_http)
        ManifestError.JSON, ManifestError.MISSING_FIELDS -> stringResource(R.string.scraper_error_manifest)
        ManifestError.NO_STREAM -> stringResource(R.string.scraper_error_stream)
        ManifestError.NO_TYPES -> stringResource(R.string.scraper_error_types)
    }
}
