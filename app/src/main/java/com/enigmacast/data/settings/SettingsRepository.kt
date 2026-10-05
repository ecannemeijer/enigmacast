package com.enigmacast.data.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.enigmacast.data.openwebif.openWebifJson
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.ReceiverConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("enigmacast_settings") },
    )
    private val key = stringPreferencesKey("settings_json")

    val settings: Flow<AppSettings> = dataStore.data.map { preferences ->
        preferences[key]?.let { raw ->
            runCatching { openWebifJson.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
        } ?: AppSettings()
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun upsertReceiver(config: ReceiverConfig): ReceiverConfig {
        val saved = config.copy(id = config.id.ifBlank { UUID.randomUUID().toString() })
        update { current ->
            val others = current.receivers.filterNot { it.id == saved.id }
            current.copy(receivers = others + saved, activeReceiverId = saved.id)
        }
        return saved
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            val current = preferences[key]?.let { raw ->
                runCatching { openWebifJson.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
            } ?: AppSettings()
            preferences[key] = openWebifJson.encodeToString(transform(current))
        }
    }
}
