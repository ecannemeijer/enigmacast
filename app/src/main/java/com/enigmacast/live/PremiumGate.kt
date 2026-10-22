package com.enigmacast.live

import com.enigmacast.data.access.RemoteAccessRepository
import com.enigmacast.data.account.AccountRepository
import com.enigmacast.domain.access.AccessMode
import com.enigmacast.domain.account.PremiumFeature
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides which features are available. In Free everything is unlocked; in Premium a signed-in
 * account unlocks everything (login only for now, no paid entitlement check).
 */
@Singleton
class PremiumGate @Inject constructor(
    private val account: AccountRepository,
    private val access: RemoteAccessRepository,
) {
    private val _prompt = MutableStateFlow<PremiumFeature?>(null)
    val prompt: StateFlow<PremiumFeature?> = _prompt.asStateFlow()

    fun allows(feature: PremiumFeature): Boolean = unlocked()

    fun isPremium(): Boolean =
        access.current().mode == AccessMode.PREMIUM && account.isSignedIn()

    /** Free or a signed-in Premium account: every feature is open. */
    fun isUnlocked(): Boolean = unlocked()

    fun canAddReceiver(count: Int): Boolean = unlocked()

    private fun unlocked(): Boolean =
        access.current().mode == AccessMode.FREE || account.isSignedIn()

    /** True when [feature] may be used; otherwise shows the Premium dialog and returns false. */
    fun require(feature: PremiumFeature): Boolean {
        if (allows(feature)) return true
        _prompt.value = feature
        return false
    }

    fun dismiss() { _prompt.value = null }
}
