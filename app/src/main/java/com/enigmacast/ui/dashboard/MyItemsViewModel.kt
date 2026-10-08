package com.enigmacast.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.R
import com.enigmacast.domain.recording.Recording
import com.enigmacast.live.RecordingBook
import com.enigmacast.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MyItemsViewModel @Inject constructor(
    private val book: RecordingBook,
) : ViewModel() {
    val items = book.items
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
        viewModelScope.launch { book.refreshReceiver() }
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
            if (book.play(item.id)) {
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
            book.delete(item.id)
            deleting = false
            pendingDelete = null
            restore(items.value.firstOrNull()?.id)
        }
    }
}
