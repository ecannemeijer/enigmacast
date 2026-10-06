package com.enigmacast.data.security

import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.ReceiverConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretFieldsTest {
    private val box = ReceiverConfig(id = "a", name = "Box", host = "192.168.1.10", username = "root", password = "secret")
    private val old = ReceiverConfig(id = "b", name = "Old", host = "192.168.1.11", password = "gone")

    @Test
    fun stripKeepsPasswordsOutOfTheSettingsAndRemovesSecretsOfDeletedReceivers() {
        val (stripped, secrets) = SecretFields.strip(AppSettings(receivers = listOf(box, old)), AppSettings(receivers = listOf(box)))
        assertEquals("", stripped.receivers.single().password)
        assertEquals("root", stripped.receivers.single().username)
        assertEquals("secret", secrets[SecretFields.passwordName("a")])
        assertTrue(secrets.containsKey(SecretFields.passwordName("b")))
        assertNull(secrets[SecretFields.passwordName("b")])
        assertNull(secrets[SecretFields.wireGuardName("b")])
    }

    @Test
    fun emptyPasswordRemovesTheSecret() {
        val (_, secrets) = SecretFields.strip(AppSettings(), AppSettings(receivers = listOf(box.copy(password = ""))))
        assertTrue(secrets.containsKey(SecretFields.passwordName("a")))
        assertNull(secrets[SecretFields.passwordName("a")])
    }

    @Test
    fun fillUsesTheStoreAndKeepsLegacyPlaintextUntilMoved() {
        val stored = mapOf(SecretFields.passwordName("a") to "fromStore")
        val filled = SecretFields.fill(AppSettings(receivers = listOf(box.copy(password = ""), old)), stored::get)
        assertEquals("fromStore", filled.receivers[0].password)
        assertEquals("gone", filled.receivers[1].password)
    }

    @Test
    fun receiverConfigNeverPrintsThePassword() {
        val text = box.toString()
        assertTrue(!text.contains("secret") && !text.contains("root"))
        assertTrue(text.contains("password=***"))
    }

    @Test
    fun tunnelConfigIsStoredAsASecretAndNeverPrinted() {
        val tunnel = box.copy(wireGuard = true, wireGuardConfig = "[Interface]\nPrivateKey = topsecretkey")
        val (stripped, secrets) = SecretFields.strip(AppSettings(), AppSettings(receivers = listOf(tunnel)))
        assertEquals("", stripped.receivers.single().wireGuardConfig)
        assertTrue(stripped.receivers.single().wireGuard)
        assertEquals(tunnel.wireGuardConfig, secrets[SecretFields.wireGuardName("a")])
        val filled = SecretFields.fill(stripped, secrets::get)
        assertEquals(tunnel.wireGuardConfig, filled.receivers.single().wireGuardConfig)
        assertTrue(!tunnel.toString().contains("topsecretkey"))
    }
}
