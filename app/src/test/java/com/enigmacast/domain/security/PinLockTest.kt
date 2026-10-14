package com.enigmacast.domain.security

import com.enigmacast.data.security.SecretStore
import com.enigmacast.data.security.SettingsLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLockTest {
    private class MemSecrets : SecretStore {
        val map = mutableMapOf<String, String>()
        override fun read(name: String) = map[name]
        override fun write(name: String, value: String?) { if (value.isNullOrEmpty()) map.remove(name) else map[name] = value }
    }

    @Test fun hashVerifiesAndIsSalted() {
        val a = PinHash.create("1234", iterations = 1000)
        val b = PinHash.create("1234", iterations = 1000)
        assertNotEquals(a, b)
        assertFalse(a.contains("1234"))
        assertTrue(PinHash.verify("1234", a))
        assertFalse(PinHash.verify("1235", a))
        assertFalse(PinHash.verify("1234", "garbage"))
    }

    @Test fun onlyFourDigits() {
        assertTrue(PinHash.valid("0000"))
        assertFalse(PinHash.valid("123"))
        assertFalse(PinHash.valid("12a4"))
    }

    @Test fun cooldownAfterFiveWrong() {
        var a = PinAttempts()
        repeat(4) { a = a.failed(0) }
        assertFalse(a.coolingDown(0)); assertEquals(1, a.left)
        a = a.failed(1_000)
        assertTrue(a.coolingDown(1_000)); assertEquals(30, a.secondsLeft(1_000))
        assertFalse(a.coolingDown(31_000)); assertEquals(5, a.left)
    }

    @Test fun lockFlow() {
        val secrets = MemSecrets()
        val lock = SettingsLock(secrets)
        assertFalse(lock.enabled.value)
        lock.setPin("4321")
        assertTrue(lock.enabled.value)
        assertFalse(secrets.map.values.any { it.contains("4321") })
        lock.relock()
        assertEquals(PinCheck.Wrong(4), lock.unlock("0000"))
        assertFalse(lock.unlocked.value)
        assertEquals(PinCheck.Ok, lock.unlock("4321"))
        assertTrue(lock.unlocked.value)
        repeat(4) { lock.check("1111", 10_000) }
        assertEquals(PinCheck.CoolingDown(30), lock.check("1111", 10_000))
        assertEquals(PinCheck.CoolingDown(20), lock.check("4321", 20_000))
        assertEquals(PinCheck.Ok, lock.disable("4321").let { if (it is PinCheck.CoolingDown) lock.check("4321", 41_000).also { lock.disable("4321") } else it })
    }
}
