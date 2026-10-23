package com.enigmacast.ui.remote

/**
 * The activity sees Back before Compose panels do. A player panel registers a handler here so
 * Back closes the panel (on key-up) and never reaches live TV, the launcher or the dashboard.
 */
object PanelBackGuard {
    @Volatile
    private var handler: (() -> Boolean)? = null

    fun isActive(): Boolean = handler != null

    fun register(close: () -> Boolean) {
        handler = close
    }

    fun unregister() {
        handler = null
    }

    fun consume(): Boolean = handler?.invoke() ?: false
}
