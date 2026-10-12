package com.enigmacast.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.R
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.recording.Recording
import com.enigmacast.domain.recording.forReceiver
import com.enigmacast.live.RecordingBook
import com.enigmacast.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MyItemsViewModel @Inject constructor(
    private val book: RecordingBook,
    settingsRepository: SettingsRepository,
    private val resume: com.enigmacast.live.ResumeTracker,
    private val artwork: com.enigmacast.live.RecordingArtwork,
) : ViewModel() {
    /** Saved positions by recording id, for the progress bars. */
    val resumePoints: StateFlow<Map<String, com.enigmacast.domain.resume.ResumePoint>> = resume.points
    /** TMDB matches by recording id (null = no match, placeholder). */
    val posters: StateFlow<Map<String, com.enigmacast.domain.films.TmdbMovie?>> = artwork.movies
    /** "Resume from mm:ss / Start from the beginning" for this recording at this position. */
    var resumeAsk by mutableStateOf<Pair<Recording, Long>?>(null)
        private set
    private var resumeStarted: (() -> Unit)? = null

    /** Recordings that can be watched: shown as poster cards. */
    fun isPoster(item: Recording) = !isBoxTimer(item) &&
        (item.status == com.enigmacast.domain.recording.RecordingStatus.READY || item.status == com.enigmacast.domain.recording.RecordingStatus.RECORDING)

    fun loadArtwork(list: List<Recording>) {
        viewModelScope.launch { artwork.resolve(list.filter(::isPoster)) }
    }
    val items: StateFlow<List<Recording>> = combine(
        book.items,
        settingsRepository.settings.map { it.activeReceiver()?.id.orEmpty() }.distinctUntilChanged(),
    ) { list, receiverId -> list.forReceiver(receiverId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var selected by mutableStateOf<Recording?>(null)
        private set
    var pendingDelete by mutableStateOf<Recording?>(null)
        private set
    var notice by mutableStateOf(UiText.Empty)
        private set
    /** The row that should take focus again after a dialog closes. */
    var focusId by mutableStateOf<String?>(null)
        private set
    var focusToken by mutableIntStateOf(0)
        private set
    /**
     * True while a dialog is closing. Focus would otherwise land on Stream and that
     * menu item would leave My Items before the list can take focus back.
     */
    var holdList by mutableStateOf(false)
        private set
    private var deleting = false
    /** Recordings and timers on the box itself (OpenWebif movielist / timerlist), not started from this app. */
    var boxMovies by mutableStateOf<List<Recording>>(emptyList())
        private set
    var boxTimers by mutableStateOf<List<Recording>>(emptyList())
        private set

    fun isBox(item: Recording) = item.id.startsWith(RecordingBook.BOX_MOVIE) || item.id.startsWith(RecordingBook.BOX_TIMER)
    fun isBoxTimer(item: Recording) = item.id.startsWith(RecordingBook.BOX_TIMER)

    private fun restore(id: String?) {
        focusId = id
        focusToken += 1
    }

    fun releaseList() {
        holdList = false
    }

    /** Show the recordings that are actually stored, including one that just stopped. */
    suspend fun reloadNow() = book.reload()

    /** Back from playback: highlight this recording again. */
    fun focusRow(id: String) = restore(id)

    fun refresh() {
        viewModelScope.launch {
            book.refreshReceiver()
            book.boxItems()?.let { boxMovies = it.movies; boxTimers = it.timers }
        }
    }

    fun open(item: Recording) {
        notice = UiText.Empty
        focusId = item.id
        selected = item
    }

    fun dismiss() {
        val id = selected?.id ?: focusId
        holdList = true
        selected = null
        restore(id)
    }

    fun play(onStarted: () -> Unit) {
        val item = selected ?: return
        holdList = true
        selected = null
        viewModelScope.launch {
            val offer = com.enigmacast.domain.resume.ResumePolicy.offer(resume.get(item.id))
            if (offer != null) {
                resumeStarted = onStarted
                resumeAsk = item to offer
                return@launch
            }
            start(item, 0L, onStarted)
        }
    }

    /** Answer to the resume question: [fromStart] plays from 0 and forgets the position. */
    fun answerResume(fromStart: Boolean) {
        val (item, position) = resumeAsk ?: return
        val onStarted = resumeStarted ?: {}
        resumeAsk = null
        resumeStarted = null
        if (fromStart) resume.forget(item.id)
        viewModelScope.launch { start(item, if (fromStart) 0L else position, onStarted) }
    }

    fun dismissResume() {
        val item = resumeAsk?.first
        resumeAsk = null
        resumeStarted = null
        holdList = true
        restore(item?.id)
    }

    private suspend fun start(item: Recording, startMs: Long, onStarted: () -> Unit) {
        run {
            if (if (isBox(item)) book.playBox(item, startMs) else book.play(item.id, startMs)) {
                holdList = false
                onStarted()
            } else {
                notice = UiText.res(R.string.my_items_nothing)
                restore(item.id)
            }
        }
    }

    fun stop() {
        val item = selected ?: return
        holdList = true
        selected = null
        restore(item.id)
        viewModelScope.launch { book.stop(item.id) }
    }

    fun askDelete() {
        holdList = true
        pendingDelete = selected
        selected = null
    }

    fun dismissDelete() {
        val id = pendingDelete?.id ?: focusId
        holdList = true
        pendingDelete = null
        restore(id)
    }

    fun confirmDelete() {
        val item = pendingDelete ?: return
        if (deleting) return
        deleting = true
        holdList = true
        viewModelScope.launch {
            if (isBox(item)) {
                if (!book.deleteBox(item)) notice = UiText.res(R.string.my_items_box_delete_failed)
                book.boxItems()?.let { boxMovies = it.movies; boxTimers = it.timers }
            } else {
                book.delete(item.id)
            }
            resume.forget(item.id)
            deleting = false
            pendingDelete = null
            restore(items.value.firstOrNull()?.id)
        }
    }
}
