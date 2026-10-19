package com.enigmacast.domain.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementsTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun premium(untilMs: Long? = now + 365 * day, verifiedAtMs: Long = now) = AccountSnapshot(
        email = "a@b.nl", plan = Plan.PREMIUM, serverPremium = true, premiumUntilMs = untilMs,
        isTrial = true, verifiedAtMs = verifiedAtMs,
    )

    @Test
    fun noAccountIsFree() {
        assertFalse(Entitlements.isPremium(null, now))
        PremiumFeature.entries.forEach { assertFalse(Entitlements.allows(null, it, now)) }
        assertEquals(1, Entitlements.maxReceivers(null, now))
        assertTrue(Entitlements.canAddReceiver(null, 0, now))
        assertFalse(Entitlements.canAddReceiver(null, 1, now))
        assertFalse(Entitlements.canSwitchReceiver(null, now))
    }

    @Test
    fun premiumUnlocksEverything() {
        val account = premium()
        PremiumFeature.entries.forEach { assertTrue(Entitlements.allows(account, it, now)) }
        assertTrue(Entitlements.canAddReceiver(account, 5, now))
        assertTrue(Entitlements.canSwitchReceiver(account, now))
    }

    @Test
    fun freePlanLocksPremiumFeatures() {
        val free = premium().copy(plan = Plan.FREE, serverPremium = false, premiumUntilMs = null)
        PremiumFeature.entries.forEach { assertFalse(Entitlements.allows(free, it, now)) }
    }

    @Test
    fun expiryEndsPremiumEvenWithFreshCache() {
        assertFalse(Entitlements.isPremium(premium(untilMs = now - 1), now))
        assertTrue(Entitlements.isPremium(premium(untilMs = null), now))
    }

    @Test
    fun offlineGraceUsesCachedAnswer() {
        assertTrue(Entitlements.isPremium(premium(verifiedAtMs = now - 13 * day), now))
        assertTrue(Entitlements.inOfflineGrace(premium(verifiedAtMs = now - 2 * day), now))
        assertFalse(Entitlements.isPremium(premium(verifiedAtMs = now - 15 * day), now))
        // A cache "from the future" (clock moved back) still counts as fresh.
        assertTrue(Entitlements.isPremium(premium(verifiedAtMs = now + day), now))
    }

    @Test
    fun serverSaysNotPremium() {
        assertFalse(Entitlements.isPremium(premium().copy(serverPremium = false), now))
    }

    @Test
    fun refreshIsDueAfterInterval() {
        assertFalse(Entitlements.needsRefresh(null, now))
        assertFalse(Entitlements.needsRefresh(premium(verifiedAtMs = now - 1000), now))
        assertTrue(Entitlements.needsRefresh(premium(verifiedAtMs = now - Entitlements.REFRESH_INTERVAL_MS), now))
    }
}
