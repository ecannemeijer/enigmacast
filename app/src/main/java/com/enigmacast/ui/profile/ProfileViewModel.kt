package com.enigmacast.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.data.enigmasync.EnigmaSyncEngine
import com.enigmacast.data.profile.ProfileCleanup
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.data.security.ProfilePinLock
import com.enigmacast.data.tmdb.HomeBouquetStore
import com.enigmacast.domain.profile.Profile
import com.enigmacast.domain.profile.ProfileRules
import com.enigmacast.domain.security.PinCheck
import com.enigmacast.domain.security.PinHash
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val store: ProfileStore,
    private val bouquets: HomeBouquetStore,
    private val pins: ProfilePinLock,
    private val cleanup: ProfileCleanup,
    private val sync: EnigmaSyncEngine,
    private val photos: com.enigmacast.data.profile.AvatarPhotos,
) : ViewModel() {
    val state = store.state

    /** "Who's watching?" full screen: at start when [ProfileRules.askOnStart], or from the menu. */
    var pickerOpen by mutableStateOf(ProfileRules.askOnStart(store.state.value) && !com.enigmacast.domain.hotel.HotelRuntime.active)
        private set

    init {
        if (pickerOpen) refreshProfiles()
    }
    /** The picker in manage mode: OK edits a profile instead of switching. */
    var managing by mutableStateOf(false)
        private set
    /** The profile menu under the avatar in the left menu. */
    var menuOpen by mutableStateOf(false)
        private set
    /** Profile waiting for its PIN before a switch or edit. */
    var pinProfile by mutableStateOf<Profile?>(null)
        private set
    var pinError by mutableStateOf<String?>(null)
        private set
    /** Set after a correct PIN while managing, so the editor opens. */
    var editorId by mutableStateOf<String?>(null)
        private set

    fun openMenu() { menuOpen = true }
    fun closeMenu() { menuOpen = false }
    fun openPicker(manage: Boolean) {
        menuOpen = false
        // Hotel mode: guests never switch profiles.
        if (com.enigmacast.domain.hotel.HotelRuntime.active) return
        managing = manage
        pickerOpen = true
        refreshProfiles()
    }

    /** Asks the sync server for profiles. A missing server leaves the profiles already stored here. */
    fun refreshProfiles() {
        viewModelScope.launch { runCatching { sync.importProfiles() } }
    }
    fun toggleManage() { managing = !managing }

    fun hasPin(profile: Profile) = pins.hasPin(profile.uuid.ifBlank { com.enigmacast.domain.profile.ProfileState.LEGACY_UUID })

    fun seesPlaylist(profile: Profile) = store.seesPlaylist(profile.uuid.ifBlank { com.enigmacast.domain.profile.ProfileState.LEGACY_UUID })

    fun choose(id: String) {
        if (com.enigmacast.domain.hotel.HotelRuntime.active) return
        val profile = store.state.value.profiles.firstOrNull { it.id == id } ?: return
        if (hasPin(profile) && (managing || id != store.activeId)) {
            pinProfile = profile
            pinError = null
            menuOpen = false
            pickerOpen = true
            return
        }
        afterUnlock(profile)
    }

    fun submitPin(pin: String, wrong: (Int) -> String, cooling: (Int) -> String) {
        val profile = pinProfile ?: return
        if (!PinHash.valid(pin)) return
        when (val result = pins.check(profile.uuid.ifBlank { com.enigmacast.domain.profile.ProfileState.LEGACY_UUID }, pin)) {
            PinCheck.Ok -> {
                pinProfile = null
                pinError = null
                afterUnlock(profile)
            }
            is PinCheck.Wrong -> pinError = wrong(result.attemptsLeft)
            is PinCheck.CoolingDown -> pinError = cooling(result.seconds)
        }
    }

    fun cancelPin() {
        pinProfile = null
        pinError = null
    }

    fun setPin(id: String, pin: String) {
        val profile = store.state.value.profiles.firstOrNull { it.id == id } ?: return
        if (PinHash.valid(pin)) pins.setPin(profile.uuid.ifBlank { com.enigmacast.domain.profile.ProfileState.LEGACY_UUID }, pin)
    }

    fun clearPin(id: String) {
        val profile = store.state.value.profiles.firstOrNull { it.id == id } ?: return
        pins.clear(profile.uuid.ifBlank { com.enigmacast.domain.profile.ProfileState.LEGACY_UUID })
    }

    private fun afterUnlock(profile: Profile) {
        if (managing) {
            editorId = profile.id
            return
        }
        viewModelScope.launch {
            if (profile.id != store.activeId) {
                store.switchTo(profile.id)
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

    fun add(name: String, avatar: Int): String = store.add(name, avatar)
    fun edit(id: String, name: String, avatar: Int, playlist: Boolean) {
        store.edit(id, name, avatar)
        val uuid = store.state.value.profiles.firstOrNull { it.id == id }?.uuid ?: return
        store.setPlaylistAccess(uuid, playlist)
        editorId = null
    }

    fun canDelete(id: String) = ProfileRules.canDelete(store.state.value, id)

    fun delete(id: String) {
        val profile = store.state.value.profiles.firstOrNull { it.id == id } ?: return
        if (!ProfileRules.canDelete(store.state.value, id)) return
        store.delete(id)
        editorId = null
        viewModelScope.launch { cleanup.purge(profile) }
    }

    fun closeEditor() { editorId = null }

    /** The "photo via QR code" dialog, null when closed. */
    var photoQr by mutableStateOf<PhotoQrState?>(null)
        private set
    private var photoServer: com.enigmacast.data.profile.PhotoUploadServer? = null
    private var photoTimer: kotlinx.coroutines.Job? = null

    /** Starts the one-off upload server for profile [id] and shows its QR code. */
    fun startPhotoQr(id: String) {
        stopPhotoQr()
        val profile = store.state.value.profiles.firstOrNull { it.id == id } ?: return
        photoQr = PhotoQrState.Starting
        photoTimer = viewModelScope.launch {
            val token = com.enigmacast.domain.profile.photo.UploadToken.create(System.currentTimeMillis())
            val started = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val address = com.enigmacast.data.profile.PhotoUploadServer.lanAddress() ?: return@withContext null
                val uuid = profile.uuid.ifBlank { com.enigmacast.domain.profile.ProfileState.LEGACY_UUID }
                val server = com.enigmacast.data.profile.PhotoUploadServer(
                    address, token, profile.name,
                    onPhoto = { bytes, x, y, side -> photos.save(uuid, bytes, x, y, side) },
                    onSuccess = {
                        viewModelScope.launch {
                            store.setPhoto(id, System.currentTimeMillis())
                            photoQr = PhotoQrState.Done
                        }
                    },
                )
                runCatching { server.start() }.getOrNull()?.let { server }
            }
            if (started == null) {
                photoQr = PhotoQrState.NoNetwork
                return@launch
            }
            photoServer = started
            val url = started.url()
            val qr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.enigmacast.domain.profile.photo.QrCode.encode(url) }
            photoQr = PhotoQrState.Ready(url, qr, token.expiresAt())
            kotlinx.coroutines.delay((token.expiresAt() - System.currentTimeMillis()).coerceAtLeast(0))
            if (photoQr is PhotoQrState.Ready) {
                started.stop()
                photoQr = PhotoQrState.Expired
            }
        }
    }

    /** Closes the dialog; the server stops with it. */
    fun stopPhotoQr() {
        photoTimer?.cancel()
        photoTimer = null
        val server = photoServer
        photoServer = null
        if (server != null) viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { server.stop() }
        photoQr = null
    }

    /** Back to the bundled avatar for profile [id]; the stored photo is removed. */
    fun clearPhoto(id: String) {
        val profile = store.state.value.profiles.firstOrNull { it.id == id } ?: return
        if (profile.photo == 0L) return
        store.clearPhoto(id)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { photos.delete(profile.uuid.ifBlank { com.enigmacast.domain.profile.ProfileState.LEGACY_UUID }) }
    }

    override fun onCleared() {
        photoServer?.stop()
        photoServer = null
    }

    // Last in the class, after every property it touches is initialised.
    init {
        // Hotel mode turned on while the picker was open: it closes, the hotel profile takes over.
        viewModelScope.launch {
            com.enigmacast.domain.hotel.HotelRuntime.state.collect { config ->
                if (config.enabled) {
                    pickerOpen = false
                    menuOpen = false
                    managing = false
                    pinProfile = null
                }
            }
        }
    }
}

sealed interface PhotoQrState {
    data object Starting : PhotoQrState
    data class Ready(val url: String, val qr: com.enigmacast.domain.profile.photo.QrCode, val expiresAt: Long) : PhotoQrState
    data object Done : PhotoQrState
    data object Expired : PhotoQrState
    data object NoNetwork : PhotoQrState
}
