package com.enigmacast.data.enigmasync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Lets writers wake a sync without depending on the engine. */
@Singleton
class SyncTrigger @Inject constructor() {
    @Volatile var run: (() -> Unit)? = null
    fun nudge() { run?.invoke() }
}

@Singleton
class UserSyncScheduler @Inject constructor(
    private val engine: EnigmaSyncEngine,
    private val profiles: com.enigmacast.data.profile.ProfileStore,
    private val trigger: SyncTrigger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        trigger.run = { scope.launch { engine.sync() } }
        scope.launch { profiles.switched.collect { engine.sync() } }
        scope.launch { engine.sync() }
    }
}
