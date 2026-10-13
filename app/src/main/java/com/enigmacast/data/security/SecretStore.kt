package com.enigmacast.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Small secrets (receiver passwords, WireGuard keys) kept apart from the plain settings. */
interface SecretStore {
    fun read(name: String): String?

    /** Stores [value]; null or empty removes the secret. */
    fun write(name: String, value: String?)
}

/**
 * Secrets encrypted with AES-256-GCM under a key that never leaves the Android Keystore,
 * stored as `v1:<base64(iv + ciphertext)>` in a private preferences file. Values are
 * never logged. When the key is gone (app data restored on another device) a secret
 * reads as null and has to be entered again.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(
    @ApplicationContext context: Context,
) : SecretStore {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun read(name: String): String? = synchronized(lock) {
        val stored = preferences.getString(name, null) ?: return null
        runCatching { decrypt(stored) }.getOrElse { error ->
            EnigmaLog.service("secret unreadable: ${error.javaClass.simpleName}")
            null
        }
    }

    override fun write(name: String, value: String?): Unit = synchronized(lock) {
        val editor = preferences.edit()
        if (value.isNullOrEmpty()) editor.remove(name) else editor.putString(name, encrypt(value))
        editor.apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        require(stored.startsWith(PREFIX))
        val sealed = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE = "enigmacast_secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "enigmacast_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * Moves receiver passwords between the settings JSON (which keeps them empty) and the
 * [SecretStore]. Settings written by older versions still carry the password in the JSON;
 * it is used until the next save moves it into the store.
 */
object SecretFields {
    fun passwordName(receiverId: String) = "receiver:$receiverId:password"

    fun wireGuardName(receiverId: String) = "receiver:$receiverId:wireguard"

    fun oscamPasswordName(receiverId: String) = "receiver:$receiverId:oscam"

    fun ftpPasswordName(receiverId: String) = "receiver:$receiverId:ftp"

    fun fill(settings: AppSettings, read: (String) -> String?): AppSettings = settings.copy(
        receivers = settings.receivers.map { receiver ->
            if (receiver.id.isBlank()) return@map receiver
            receiver.copy(
                password = read(passwordName(receiver.id)) ?: receiver.password,
                wireGuardConfig = read(wireGuardName(receiver.id)) ?: receiver.wireGuardConfig,
                oscamPassword = read(oscamPasswordName(receiver.id)) ?: receiver.oscamPassword,
                ftpPassword = read(ftpPasswordName(receiver.id)) ?: receiver.ftpPassword,
            )
        },
    )

    /** The settings to write (no passwords or tunnel keys) and the secrets to store (null removes). */
    fun strip(previous: AppSettings, next: AppSettings): Pair<AppSettings, Map<String, String?>> {
        val secrets = linkedMapOf<String, String?>()
        next.receivers.filter { it.id.isNotBlank() }.forEach {
            secrets[passwordName(it.id)] = it.password.ifEmpty { null }
            secrets[wireGuardName(it.id)] = it.wireGuardConfig.ifBlank { null }
            secrets[oscamPasswordName(it.id)] = it.oscamPassword.ifEmpty { null }
            secrets[ftpPasswordName(it.id)] = it.ftpPassword.ifEmpty { null }
        }
        val kept = next.receivers.map { it.id }.toSet()
        previous.receivers.filter { it.id.isNotBlank() && it.id !in kept }.forEach {
            secrets[passwordName(it.id)] = null
            secrets[wireGuardName(it.id)] = null
            secrets[oscamPasswordName(it.id)] = null
            secrets[ftpPasswordName(it.id)] = null
        }
        val stripped = next.copy(
            receivers = next.receivers.map {
                if (it.id.isBlank()) it else it.copy(password = "", wireGuardConfig = "", oscamPassword = "", ftpPassword = "")
            },
        )
        return stripped to secrets
    }
}
