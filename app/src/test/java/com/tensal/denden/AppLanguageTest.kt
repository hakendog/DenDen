package com.tensal.denden

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun unknownStoredLanguageFallsBackToSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorage(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorage("future"))
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromStorage("zh-TW"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromStorage("en"))
    }
}
