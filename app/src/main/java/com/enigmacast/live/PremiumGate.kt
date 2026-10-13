package com.enigmacast.live

import com.enigmacast.data.account.AccountRepository
import com.enigmacast.domain.account.Entitlements
import com.enigmacast.domain.account.PremiumFeature
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Asks [Entitlements] for the signed-in account and, when a feature is locked, raises the
 * "Premium" dialog (shown by the activity over every screen).
 */
@Singleton
class PremiumGate @Inject constructor(
    private val account: AccountRepository,
) {
    private val _prompt = MutableStateFlow<PremiumFeature?>(null)
    val prompt: StateFlow<PremiumFeature?> = _prompt.asStateFlow()

    fun allows(feature: PremiumFeature): Boolean =
        Entitlements.allows(account.account.value, feature, System.currentTimeMillis())

    fun isPremium(): Boolean = Entitlements.isPremium(account.account.value, System.currentTimeMillis())

    fun canAddReceiver(count: Int): Boolean =
        Entitlements.canAddReceiver(account.account.value, count, System.currentTimeMillis())

    /** True when [feature] may be used; otherwise shows the Premium dialog and returns false. */
    fun require(feature: PremiumFeature): Boolean {
        if (allows(feature)) return true
        _prompt.value = feature
        return false
    }

    fun dismiss() { _prompt.value = null }
}
