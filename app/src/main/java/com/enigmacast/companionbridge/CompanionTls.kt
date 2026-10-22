package com.enigmacast.companionbridge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

class CompanionTlsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * TLS voor de brug. De sleutel staat in de Android Keystore en wordt nooit geëxporteerd;
 * het zelfondertekende certificaat wordt door de Keystore gemaakt (API 26+).
 *
 * De telefoon vertrouwt alleen de SPKI-pin (SHA-256 van de publieke sleutel), precies zoals
 * EnigmaCast dat al doet voor zijn syncserver.
 */
object CompanionTls {
    private const val ALIAS = "enigmacast_companion_tls_v2"
    private const val KEYSTORE = "AndroidKeyStore"

    fun serverContext(): SSLContext {
        val keyStore = keyStore()
        ensureKey(keyStore)
        val keyManager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ): String? = null

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ): String = ALIAS

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                runCatching {
                    keyStore.getCertificateChain(ALIAS)
                        ?.map { it as X509Certificate }
                        ?.toTypedArray()
                }.getOrNull()

            override fun getPrivateKey(alias: String?): PrivateKey? =
                runCatching { keyStore.getKey(ALIAS, null) as? PrivateKey }.getOrNull()
        }
        return try {
            SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), null, SecureRandom()) }
        } catch (error: Exception) {
            throw CompanionTlsException("TLS-context maken mislukt: ${error.javaClass.simpleName}", error)
        }
    }

    fun pin(): String {
        val keyStore = keyStore()
        ensureKey(keyStore)
        val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate
            ?: throw CompanionTlsException("Geen certificaat in de Keystore")
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun ensureKey(keyStore: KeyStore) {
        if (keyStore.containsAlias(ALIAS)) return
        try {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // Conscrypt ondertekent de TLS-handshake met een raw digest (NONEwithECDSA);
                // zonder DIGEST_NONE weigert de Keystore de bewerking ("Incompatible digest").
                .setDigests(
                    KeyProperties.DIGEST_NONE,
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512,
                )
                .setCertificateSubject(X500Principal("CN=enigmacast-companion"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - 24L * 60 * 60 * 1000))
                .setCertificateNotAfter(Date(now + 10L * 365 * 24 * 60 * 60 * 1000))
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        } catch (error: Exception) {
            throw CompanionTlsException("TLS-sleutel maken mislukt: ${error.javaClass.simpleName}", error)
        }
    }
}
