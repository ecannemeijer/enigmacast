package com.enigmacast.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.data.tmdb.HomeBouquetStore
import com.enigmacast.domain.profile.ProfileRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val store: ProfileStore,
    private val bouquets: HomeBouquetStore,
) : ViewModel() {
    val state = store.state

    /** "Who's watching?" full screen: at start when [ProfileRules.askOnStart], or from the menu. */
    var pickerOpen by mutableStateOf(ProfileRules.askOnStart(store.state.value))
        private set
    /** The picker in manage mode: OK edits a profile instead of switching. */
    var managing by mutableStateOf(false)
        private set
    /** The profile menu under the avatar in the left menu. */
    var menuOpen by mutableStateOf(false)
        private set

    fun openMenu() { menuOpen = true }
    fun closeMenu() { menuOpen = false }
    fun openPicker(manage: Boolean) { menuOpen = false; managing = manage; pickerOpen = true }
    fun toggleManage() { managing = !managing }

    fun choose(id: String) {
        viewModelScope.launch {
            if (id != store.activeId) {
                store.switchTo(id)
                bouquets.profileChanged()
            } else {
                store.confirmActive()
            }
            pickerOpen = false
            menuOpen = false
            managing = false
        }
    }

    fun closePicker() {
        store.confirmActive()
        pickerOpen = false
        managing = false
    }

    fun add(name: String, avatar: Int) { store.add(name, avatar) }
    fun edit(id: String, name: String, avatar: Int) = store.edit(id, name, avatar)
    fun canDelete(id: String) = ProfileRules.canDelete(store.state.value, id)
    fun delete(id: String) = store.delete(id)
}
