package com.enigmacast.live

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the "Switch receiver" dialog is open; Home's button and the Red key open it. */
@Singleton
class ReceiverSwitcher @Inject constructor() {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    fun show() { _open.value = true }
    fun hide() { _open.value = false }
}
