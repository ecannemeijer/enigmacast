package com.enigmacast.data.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.enigmacast.data.openwebif.openWebifJson
import com.enigmacast.data.security.SecretFields
import com.enigmacast.data.security.SecretStore
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.ChannelMemory
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.model.ReceiverList
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val secrets: SecretStore,
    private val dispatchers: com.enigmacast.util.AppDispatchers,
) {
    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("enigmacast_settings") },
    )
    private val key = stringPreferencesKey("settings_json")

    /** Settings with the receiver passwords filled in from the Keystore-backed [SecretStore]. */
    // decode() parses the JSON and reads the Keystore: on IO, not on the collector's thread (often Main).
    val settings: Flow<AppSettings> = dataStore.data.map { preferences -> decode(preferences[key]) }.flowOn(dispatchers.io)

    suspend fun current(): AppSettings = settings.first()

    suspend fun upsertReceiver(config: ReceiverConfig): ReceiverConfig {
        val saved = config.copy(id = config.id.ifBlank { UUID.randomUUID().toString() })
        update { current ->
            val others = current.receivers.filterNot { it.id == saved.id }
            current.copy(receivers = others + saved, activeReceiverId = saved.id)
        }
        return saved
    }

    suspend fun selectReceiver(id: String) {
        update { ReceiverList.select(it, id) }
    }

    suspend fun removeReceiver(id: String) {
        update { ReceiverList.remove(it, id) }
    }

    /** Writes per-receiver channel memory when older settings still keep a single last channel. */
    suspend fun migrateChannelMemory() {
        val raw = dataStore.data.first()[key] ?: return
        val stored = withContext(dispatchers.default) { runCatching { openWebifJson.decodeFromString<AppSettings>(raw) }.getOrNull() } ?: return
        if (!ChannelMemory.needsMigration(stored)) return
        update { ChannelMemory.migrate(it) }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            val current = decode(preferences[key])
            val (stripped, secretValues) = SecretFields.strip(current, transform(current))
            secretValues.forEach { (name, value) -> secrets.write(name, value) }
            preferences[key] = openWebifJson.encodeToString(stripped)
        }
    }

    /** Moves passwords that older versions kept in the settings JSON into the [SecretStore]. */
    suspend fun migrateSecrets() {
        val raw = dataStore.data.first()[key] ?: return
        val stored = withContext(dispatchers.default) { runCatching { openWebifJson.decodeFromString<AppSettings>(raw) }.getOrNull() } ?: return
        if (stored.receivers.any { it.password.isNotEmpty() }) update { it }
    }

    private fun decode(raw: String?): AppSettings {
        val parsed = raw?.let { runCatching { openWebifJson.decodeFromString<AppSettings>(it) }.getOrDefault(AppSettings()) } ?: AppSettings()
        return ChannelMemory.migrate(SecretFields.fill(parsed, secrets::read))
    }
}
