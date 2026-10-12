package com.enigmacast.live

import com.enigmacast.data.db.ResumeDao
import com.enigmacast.data.db.ResumeRow
import com.enigmacast.domain.resume.ResumePoint
import com.enigmacast.domain.resume.ResumePolicy
import com.enigmacast.playback.LivePlaybackController
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers where a recording was left: while a recording plays, its position is saved every
 * few seconds and once more when playback leaves it; reaching the end forgets it.
 */
@Singleton
class ResumeTracker @Inject constructor(
    private val dao: ResumeDao,
    private val book: RecordingBook,
    private val playback: LivePlaybackController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val points: StateFlow<Map<String, ResumePoint>> = dao.all()
        .map { rows -> rows.associate { it.key to ResumePoint(it.key, it.positionMs, it.durationMs) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private var key: String? = null
    private var positionMs = 0L
    private var durationMs = 0L
    private var savedAt = 0L
    private var savedPosition = -1L
    private var ended = false

    init {
        scope.launch {
            combine(book.nowPlaying, playback.state) { item, state -> item to state }.collect { (item, state) ->
                val current = item?.id?.takeIf { state.filePlayback }
                if (current != key) {
                    flush()
                    key = current
                    positionMs = 0; durationMs = 0; savedPosition = -1; ended = false
                }
                if (current == null) return@collect
                if (state.playbackState == "ENDED") { ended = true; return@collect }
                if (state.playbackState != "READY" && state.playbackState != "BUFFERING") return@collect
                positionMs = state.positionMs
                if (state.durationMs > 0) durationMs = state.durationMs
                val now = System.currentTimeMillis()
                if (now - savedAt >= ResumePolicy.SAVE_EVERY_MS) save(now)
            }
        }
    }

    suspend fun get(key: String): ResumePoint? = dao.get(key)?.let { ResumePoint(it.key, it.positionMs, it.durationMs) }

    fun forget(key: String) {
        scope.launch { dao.delete(key) }
    }

    private fun save(now: Long) {
        val k = key ?: return
        savedAt = now
        if (positionMs == savedPosition) return
        savedPosition = positionMs
        val p = positionMs
        val d = durationMs
        scope.launch {
            if (ResumePolicy.watched(p, d)) dao.delete(k) else if (p >= ResumePolicy.MIN_RESUME_MS) dao.put(ResumeRow(k, p, d, now))
        }
    }

    private fun flush() {
        val k = key ?: return
        if (ended) {
            scope.launch { dao.delete(k) }
            EnigmaLog.player("resume: $k watched to the end, forgotten")
        } else {
            savedAt = 0
            savedPosition = -1
            save(System.currentTimeMillis())
        }
    }
}
