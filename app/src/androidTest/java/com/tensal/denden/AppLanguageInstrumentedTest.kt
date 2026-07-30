package com.tensal.denden

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageInstrumentedTest {
    @Test
    fun storedLanguageSelectsMatchingResources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(APP_SETTINGS_PREFS, Context.MODE_PRIVATE)
        val original = preferences.getString(APP_LANGUAGE_KEY, null)
        try {
            preferences.edit().putString(APP_LANGUAGE_KEY, AppLanguage.ENGLISH.storageValue).commit()
            assertEquals("Settings", context.withSelectedAppLanguage().getString(R.string.settings))

            preferences.edit().putString(APP_LANGUAGE_KEY, AppLanguage.TRADITIONAL_CHINESE.storageValue).commit()
            assertEquals("設定", context.withSelectedAppLanguage().getString(R.string.settings))
        } finally {
            preferences.edit().apply {
                if (original == null) remove(APP_LANGUAGE_KEY) else putString(APP_LANGUAGE_KEY, original)
            }.commit()
        }
    }
}
