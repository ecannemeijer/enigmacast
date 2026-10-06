package com.enigmacast.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** The languages the app is translated into. English is the default for every install. */
enum class AppLanguage(val tag: String, val nativeName: String) {
    ENGLISH("en", "English"),
    DUTCH("nl", "Nederlands"),
    ;

    companion object {
        val DEFAULT = ENGLISH

        fun fromTag(tag: String?): AppLanguage? {
            val language = tag?.substringBefore('-')?.substringBefore('_')?.lowercase() ?: return null
            return entries.firstOrNull { it.tag == language }
        }
    }
}

/**
 * The app language chosen in Settings. It is kept in plain shared preferences, because
 * it has to be known synchronously in `attachBaseContext`, before anything else runs.
 * On Android 13+ the choice is mirrored to the system's per-app language setting, and a
 * change made there is picked up on the next start.
 */
object AppLanguageStore {
    private const val PREFS = "enigmacast_language"
    private const val KEY = "language"

    fun current(context: Context): AppLanguage {
        val saved = AppLanguage.fromTag(prefs(context).getString(KEY, null))
        val system = systemChoice(context)
        if (system != null && system != saved) {
            // Changed in the Android settings (app language) since the last start.
            prefs(context).edit().putString(KEY, system.tag).apply()
            return system
        }
        return saved ?: AppLanguage.DEFAULT
    }

    /**
     * Saves [language]. Returns true when the system applies it and recreates the activity
     * itself (Android 13+); otherwise the caller recreates the activity.
     */
    fun choose(context: Context, language: AppLanguage): Boolean {
        prefs(context).edit().putString(KEY, language.tag).commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return false
            val wanted = LocaleList.forLanguageTags(language.tag)
            if (manager.applicationLocales == wanted) return false
            manager.applicationLocales = wanted
            return true
        }
        return false
    }

    /** The context the activity should use: resources and formatting in the chosen language. */
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(current(base).tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    private fun systemChoice(context: Context): AppLanguage? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
        if (locales.isEmpty) return null
        return AppLanguage.fromTag(locales[0].toLanguageTag())
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
