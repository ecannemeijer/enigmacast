package com.enigmacast.data.settings

import com.enigmacast.data.openwebif.openWebifJson
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.SkinDefaults

/** Reads stored settings, or the first-install defaults when this TV has no file yet. */
object SettingsBootstrap {
    fun parse(raw: String?): AppSettings =
        raw?.let {
            runCatching { SkinDefaults.decodeSettings(openWebifJson, AppSettings.serializer(), it) }
                .getOrDefault(AppSettings())
        } ?: AppSettings.firstInstall()
}
