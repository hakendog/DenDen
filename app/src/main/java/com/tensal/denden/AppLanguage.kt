package com.tensal.denden

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

internal const val APP_SETTINGS_PREFS = "app_settings"
internal const val APP_LANGUAGE_KEY = "app_language"

enum class AppLanguage(
    val storageValue: String,
    val localeTag: String?,
    @param:StringRes val labelRes: Int
) {
    SYSTEM("system", null, R.string.language_system),
    TRADITIONAL_CHINESE("zh-TW", "zh-TW", R.string.language_zh_tw),
    ENGLISH("en", "en", R.string.language_english);

    companion object {
        fun fromStorage(value: String?): AppLanguage =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

fun Context.selectedAppLanguage(): AppLanguage = AppLanguage.fromStorage(
    getSharedPreferences(APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getString(APP_LANGUAGE_KEY, null)
)

fun Context.withSelectedAppLanguage(): Context {
    val selected = selectedAppLanguage()
    val locale = selected.localeTag?.let(Locale::forLanguageTag)
        ?: resources.configuration.locales[0]
    Locale.setDefault(locale)
    if (selected == AppLanguage.SYSTEM) return this
    val configuration = Configuration(resources.configuration).apply {
        setLocale(locale)
        setLayoutDirection(locale)
    }
    return createConfigurationContext(configuration)
}
