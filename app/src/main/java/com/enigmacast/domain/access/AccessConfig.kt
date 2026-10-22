package com.enigmacast.domain.access

/** The server-controlled way EnigmaCast opens: free for everyone, or login required. */
enum class AccessMode { FREE, PREMIUM }

/**
 * Signed-in access settings as last received from api.velixatv.com. [authEpoch] is increased by
 * the server whenever the mode changes, so a client can force a fresh login after Free -> Premium.
 */
data class AccessConfig(
    val mode: AccessMode = AccessMode.FREE,
    val authEpoch: Long = 0L,
    val minVersionCode: Int = 0,
    val minVersionName: String = "",
    val updateUrl: String = "https://update.velixatv.com/enigmacast.apk",
    val message: String = "",
    val features: Map<String, Boolean> = emptyMap(),
    val verifiedAtMs: Long = 0L,
) {
    val requiresLogin: Boolean get() = mode == AccessMode.PREMIUM
    val blocksThisVersion: Boolean get() = minVersionCode > 0
}
