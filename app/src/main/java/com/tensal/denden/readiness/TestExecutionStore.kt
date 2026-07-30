package com.tensal.denden.readiness

import android.content.Context

data class TestExecutionSnapshot(
    val atMillis: Long = 0,
    val result: String? = null,
    val detail: String? = null
)

class TestExecutionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(result: String, detail: String?, atMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_AT, atMillis)
            .putString(KEY_RESULT, result)
            .putString(KEY_DETAIL, detail)
            .apply()
    }

    fun snapshot(): TestExecutionSnapshot = TestExecutionSnapshot(
        atMillis = prefs.getLong(KEY_AT, 0),
        result = prefs.getString(KEY_RESULT, null),
        detail = prefs.getString(KEY_DETAIL, null)
    )

    private companion object {
        const val PREFS_NAME = "test_execution"
        const val KEY_AT = "last_test_at"
        const val KEY_RESULT = "last_test_result"
        const val KEY_DETAIL = "last_test_detail"
    }
}
