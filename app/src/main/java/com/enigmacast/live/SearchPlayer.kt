package com.enigmacast.live

import com.enigmacast.R
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.StreamMode
import com.enigmacast.domain.search.SearchHit
import com.enigmacast.domain.search.SearchKind
import com.enigmacast.domain.service.LiveZapFlow
import com.enigmacast.domain.source.ServiceStateSource
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SearchOutcome {
    data object Played : SearchOutcome
    data class Bouquet(val bouquet: BouquetEntry) : SearchOutcome
    data class Failed(val text: UiText) : SearchOutcome
}

/**
 * Plays a search row: a channel (and its bouquet) in own-tuner or follow mode,
 * a recording file, or only a bouquet change.
 */
@Singleton
class SearchPlayer @Inject constructor(
    private val search: ProgrammeSearch,
    private val session: OwnTunerSession,
    private val recordings: RecordingBook,
    private val serviceSource: ServiceStateSource,
    private val streamTargets: StreamTargets,
    private val playback: LivePlaybackController,
    private val settings: SettingsRepository,
) {
    suspend fun open(hit: SearchHit): SearchOutcome {
        return when (hit.kind) {
            SearchKind.TMDB -> SearchOutcome.Failed(UiText.res(R.string.search_unavailable))
            SearchKind.BOUQUET -> openBouquet(hit)
            SearchKind.RECORDING -> {
                if (recordings.play(hit.recordingId)) SearchOutcome.Played
                else SearchOutcome.Failed(UiText.res(R.string.my_items_nothing))
            }
            SearchKind.NOW, SearchKind.LATER, SearchKind.CHANNEL -> tune(hit)
        }
    }

    private suspend fun openBouquet(hit: SearchHit): SearchOutcome {
        val bouquet = BouquetEntry(hit.bouquetName, hit.bouquetReference)
        return if (session.selectBouquet(bouquet)) {
            SearchOutcome.Bouquet(bouquet)
        } else {
            SearchOutcome.Failed(UiText.res(R.string.notice_bouquet_failed, bouquet.name))
        }
    }

    private suspend fun tune(hit: SearchHit): SearchOutcome {
        val place = search.placeOf(hit.serviceReference)
            ?: return SearchOutcome.Failed(UiText.res(R.string.search_channel_missing))
        val ownTuner = settings.current().streamMode != StreamMode.FOLLOW_RECEIVER
        if (ownTuner) {
            session.start(place.channel, place.channels, place.bouquet.name, place.bouquet.reference)
            return SearchOutcome.Played
        }
        val receiver = settings.current().activeReceiver()
            ?: return SearchOutcome.Failed(UiText.res(R.string.guide_no_receiver))
        val target = streamTargets.target(receiver, place.channel.reference)
        if (target.internet) {
            playback.stopForZap()
            playback.play(target.url, force = true, authorization = target.authorization, internet = true)
            runCatching { serviceSource.zap(receiver, place.channel.reference) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    EnigmaLog.service("search zap failed: ${error.javaClass.simpleName}")
                }
            return SearchOutcome.Played
        }
        val outcome = LiveZapFlow.run(
            token = 1,
            isCurrent = { true },
            reference = place.channel.reference,
            streamUrl = target.url,
            releaseStream = { playback.stopForZap() },
            zap = { serviceSource.zap(receiver, place.channel.reference) },
            currentReference = { serviceSource.currentService(receiver).reference },
            startStream = { playback.play(target.url, force = true, authorization = target.authorization) },
        )
        return if (outcome is LiveZapFlow.Outcome.Started) {
            SearchOutcome.Played
        } else {
            SearchOutcome.Failed(UiText.res(R.string.search_tune_failed))
        }
    }
}
