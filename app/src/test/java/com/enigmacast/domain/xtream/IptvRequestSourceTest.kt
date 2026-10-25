package com.enigmacast.domain.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IptvRequestSourceTest {
    private val providers = mapOf("p1" to "keyA", "p2" to "keyB")

    @Test fun otherProviderOpensWithItsId() = assertEquals("p2", IptvRequestSource.providerFor("keyB", "keyA", providers))

    @Test fun activeProviderKeepsItsId() = assertEquals("p1", IptvRequestSource.providerFor("keyA", "keyA", providers))

    @Test fun singleAccountWithoutProviders() = assertEquals("", IptvRequestSource.providerFor("keyA", "keyA", emptyMap()))

    @Test fun unknownSourceWaits() = assertNull(IptvRequestSource.providerFor("keyC", "keyA", providers))

    @Test fun noActiveAccountWaitsUnlessAProviderMatches() {
        assertNull(IptvRequestSource.providerFor("keyC", null, providers))
        assertEquals("p1", IptvRequestSource.providerFor("keyA", null, providers))
    }
}
