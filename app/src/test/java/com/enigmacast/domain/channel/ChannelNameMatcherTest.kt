package com.enigmacast.domain.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNameMatcherTest {
    @Test fun countryAndQualityAreIgnored() {
        assertEquals("SBS6", ChannelNameMatcher.canonical("NL - SBS6 HD"))
        assertEquals("SBS6", ChannelNameMatcher.canonical("SBS6"))
        assertEquals("SBS6", ChannelNameMatcher.canonical("SBS 6"))
        assertEquals("SBS6", ChannelNameMatcher.canonical("[NL] SBS6 FHD"))
        assertEquals("NPO1", ChannelNameMatcher.canonical("NL: NPO 1"))
        assertEquals("RTL4", ChannelNameMatcher.canonical("RTL 4 HD"))
    }

    @Test fun variantsMatch() {
        assertTrue(ChannelNameMatcher.matches("SBS6", "NL - SBS6 HD"))
        assertTrue(ChannelNameMatcher.matches("NPO 1", "NL | NPO1 FHD"))
        assertTrue(ChannelNameMatcher.matches("BBC One HD", "EN - BBC ONE"))
    }

    @Test fun differentChannelsDoNotMatch() {
        assertFalse(ChannelNameMatcher.matches("NPO 1", "NPO 1 Extra"))
        assertFalse(ChannelNameMatcher.matches("RTL 4", "RTL 5"))
        assertFalse(ChannelNameMatcher.matches("SBS6", "SBS9"))
        assertFalse(ChannelNameMatcher.matches("", "SBS6"))
    }
}
