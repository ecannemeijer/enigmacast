package com.enigmacast.data.enigmasync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** ECDSA P-256 key that never leaves the Android Keystore. The server stores only the public key. */
@Singleton
class DeviceIdentity @Inject constructor() {
    fun publicKey(): ByteArray {
        ensure()
        val cert = keystore().getCertificate(ALIAS) ?: error("device key missing")
        return cert.publicKey.encoded
    }

    fun sign(message: ByteArray): ByteArray {
        ensure()
        val key = keystore().getKey(ALIAS, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(key)
        signature.update(message)
        return signature.sign()
    }

    private fun ensure() {
        val store = keystore()
        if (store.containsAlias(ALIAS)) return
        val generator = java.security.KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val ALIAS = "enigmacast_sync_device"
    }
}
