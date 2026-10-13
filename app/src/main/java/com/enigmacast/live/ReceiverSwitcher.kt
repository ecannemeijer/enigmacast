package com.enigmacast.live

import android.content.Context
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.util.EnigmaLog
import com.enigmacast.sync.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The "Switch receiver" dialog (Home's button, the Red key in menus and in the player) and the
 * switch itself, which runs in the app scope so closing the dialog or leaving a screen cannot cut it off.
 */
@Singleton
class ReceiverSwitcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val ownTuner: OwnTunerSession,
    private val gate: PremiumGate,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()
    private val _switched = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** The id of the box that just became active. */
    val switched: SharedFlow<String> = _switched.asSharedFlow()

    /** Free accounts have one receiver: the Premium dialog opens instead. */
    fun show() {
        if (gate.require(com.enigmacast.domain.account.PremiumFeature.MULTIPLE_RECEIVERS)) _open.value = true
    }
    fun hide() { _open.value = false }

    /**
     * Makes [id] the active box: this device's stream on the old box stops (its tuner is freed),
     * the choice is saved, and a full sync replaces a sync of the old box that may still run.
     */
    fun switchTo(id: String) {
        _open.value = false
        if (!gate.require(com.enigmacast.domain.account.PremiumFeature.MULTIPLE_RECEIVERS)) return
        scope.launch {
            if (settings.current().activeReceiver()?.id == id) return@launch
            EnigmaLog.service("switch receiver to $id")
            ownTuner.prepareReceiverSwitch(id)
            settings.selectReceiver(id)
            SyncScheduler.runNow(context, force = true)
            _switched.tryEmit(id)
        }
    }
}
