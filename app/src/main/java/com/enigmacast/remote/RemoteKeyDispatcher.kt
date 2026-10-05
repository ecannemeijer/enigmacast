package com.enigmacast.remote

import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.remote.EnigmaKey
import com.enigmacast.domain.remote.KeyAction
import com.enigmacast.domain.source.RemoteControlSource
import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class RemoteKeyDispatcher(
    private val remote: RemoteControlSource,
    private val scope: CoroutineScope,
    private val configProvider: () -> ReceiverConfig?,
    private val onSent: (EnigmaKey) -> Unit,
) {
    private var held: EnigmaKey? = null

    fun onDown(key: EnigmaKey) {
        if (held == key) return
        held = key
        send(key, KeyAction.SHORT)
    }

    fun onUp(key: EnigmaKey?) {
        if (key == null || held != key) return
        held = null
    }

    private fun send(key: EnigmaKey, action: KeyAction) {
        val config = configProvider() ?: return
        onSent(key)
        scope.launch {
            try {
                remote.sendKey(config, key, action)
            } catch (error: Exception) {
                EnigmaLog.remote("send failed ${key.name}: ${error.javaClass.simpleName}")
            }
        }
    }
}
