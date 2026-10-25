package com.enigmacast.domain.resume

import com.enigmacast.data.db.EpisodeHistoryRow
import com.enigmacast.data.db.IptvResumeRow

/**
 * A film's bar and check mark (cards and info page): its watch history, moved on by a newer saved
 * place; only the saved place for films played before the history existed. "Watched" stays.
 */
object FilmWatch {
    fun row(history: EpisodeHistoryRow?, resume: IptvResumeRow?): EpisodeHistoryRow? {
        if (resume == null) return history
        if (history != null && history.updatedMs >= resume.updatedMs) return history
        val fromResume = EpisodeHistoryRow(
            source = resume.source, kind = resume.kind, id = resume.id, seriesId = null,
            positionMs = resume.positionMs, durationMs = resume.durationMs,
            watched = false, updatedMs = resume.updatedMs,
        )
        return if (history == null) fromResume else history.copy(
            positionMs = resume.positionMs,
            durationMs = resume.durationMs.takeIf { it > 0 } ?: history.durationMs,
            updatedMs = resume.updatedMs,
        )
    }
}
