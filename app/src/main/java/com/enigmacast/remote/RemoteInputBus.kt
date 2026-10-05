package com.enigmacast.remote

import android.view.KeyEvent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteInputBus @Inject constructor() {
    @Volatile
    var handler: ((KeyEvent) -> Boolean)? = null
}
