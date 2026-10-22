package com.enigmacast.companionbridge.ui

import androidx.lifecycle.ViewModel
import com.enigmacast.companionbridge.CompanionBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CompanionViewModel @Inject constructor(
    private val bridge: CompanionBridge,
) : ViewModel() {
    val state = bridge.state

    fun setEnabled(value: Boolean) = bridge.setEnabled(value)

    fun newCode() = bridge.newPairingCode()

    fun revoke(id: String) = bridge.revoke(id)

    fun setTestFormText(text: String) = bridge.setTestFormText(text)
}
