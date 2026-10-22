package com.enigmacast.companionbridge

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class CompanionDevice(
    val id: String,
    val name: String,
    val publicKey: String,
    val tokenHash: String,
    val role: String = "member",
    val createdMs: Long = 0,
    val lastSeenMs: Long = 0,
    val revoked: Boolean = false,
)

@Serializable
private data class DeviceFile(val devices: List<CompanionDevice> = emptyList())

/**
 * De koppellijst: alleen de publieke sleutel en een hash van het token, nooit het token
 * zelf. De lijst staat in de app-sandbox en is nooit via de API leesbaar.
 */
@Singleton
class CompanionDevices @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file = File(context.filesDir, "companion/devices.json")
    private val mutex = Mutex()
    private val _devices = MutableStateFlow<List<CompanionDevice>>(emptyList())
    val devices: StateFlow<List<CompanionDevice>> = _devices.asStateFlow()
    private var loaded = false

    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        loaded = true
        val list = withContext(Dispatchers.IO) {
            runCatching {
                if (!file.isFile) emptyList() else
                    CompanionProtocol.JSON.decodeFromString(DeviceFile.serializer(), file.readText()).devices
            }.getOrDefault(emptyList())
        }
        _devices.value = list
    }

    suspend fun add(name: String, publicKey: String): Pair<CompanionDevice, String> = mutex.withLock {
        loadLocked()
        val token = randomToken()
        val device = CompanionDevice(
            id = UUID.randomUUID().toString(),
            name = name.take(64).ifBlank { "Telefoon" },
            publicKey = publicKey,
            tokenHash = hashToken(token),
            createdMs = System.currentTimeMillis(),
        )
        _devices.value = _devices.value + device
        persist()
        device to token
    }

    suspend fun revoke(id: String) = mutex.withLock {
        loadLocked()
        _devices.value = _devices.value.map { if (it.id == id) it.copy(revoked = true) else it }
        persist()
    }

    suspend fun touch(id: String) = mutex.withLock {
        val now = System.currentTimeMillis()
        var changed = false
        val next = _devices.value.map {
            if (it.id == id && now - it.lastSeenMs > 60_000) {
                changed = true
                it.copy(lastSeenMs = now)
            } else it
        }
        if (changed) {
            _devices.value = next
            persist()
        }
    }

    fun find(deviceId: String): CompanionDevice? = _devices.value.firstOrNull { it.id == deviceId }

    fun findByToken(token: String): CompanionDevice? =
        _devices.value.firstOrNull { it.tokenHash == hashToken(token) && !it.revoked }

    fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun randomToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        _devices.value = runCatching {
            if (!file.isFile) emptyList() else
                CompanionProtocol.JSON.decodeFromString(DeviceFile.serializer(), file.readText()).devices
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(CompanionProtocol.JSON.encodeToString(DeviceFile(_devices.value)))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }
}
