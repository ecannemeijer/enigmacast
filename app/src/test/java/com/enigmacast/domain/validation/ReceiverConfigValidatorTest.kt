package com.enigmacast.domain.validation

import com.enigmacast.domain.model.ReceiverConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverConfigValidatorTest {
    @Test
    fun acceptsALocalReceiver() {
        val result = ReceiverConfigValidator.validate(
            ReceiverConfig(name = "Living room", host = "192.168.1.100"),
        )
        assertTrue(result.isValid)
    }

    @Test
    fun rejectsBlankHostAndBadPorts() {
        val result = ReceiverConfigValidator.validate(
            ReceiverConfig(name = "", host = "bad host", httpPort = 0, streamingPort = 80_000),
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.size >= 3)
    }
}
