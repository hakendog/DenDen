package com.tensal.denden.automation

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tensal.denden.withSelectedAppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BixbyAutomationActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withSelectedAppLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = bixbyModeForAction(intent?.action) ?: return finish()
        lifecycleScope.launch {
            val completed = runCatching {
                withContext(Dispatchers.IO) {
                    triggerLocalAutomation(this@BixbyAutomationActivity, bixbyAutomationRequest(this@BixbyAutomationActivity, mode))
                }
            }.isSuccess
            if (completed) getSystemService(ShortcutManager::class.java).reportShortcutUsed(mode.shortcutId)
            finish()
        }
    }
}

internal fun bixbyModeForAction(action: String?): LocalAutomationMode? = when (action) {
    ACTION_BIXBY_QUIET -> LocalAutomationMode.QUIET
    ACTION_BIXBY_NOTIFY -> LocalAutomationMode.NOTIFY
    ACTION_BIXBY_RING -> LocalAutomationMode.RING
    else -> null
}

private val LocalAutomationMode.shortcutId: String
    get() = when (this) {
        LocalAutomationMode.QUIET -> SHORTCUT_BIXBY_QUIET
        LocalAutomationMode.NOTIFY -> SHORTCUT_BIXBY_NOTIFY
        LocalAutomationMode.RING -> SHORTCUT_BIXBY_RING
    }

const val ACTION_BIXBY_QUIET = "com.tensal.denden.action.BIXBY_QUIET"
const val ACTION_BIXBY_NOTIFY = "com.tensal.denden.action.BIXBY_NOTIFY"
const val ACTION_BIXBY_RING = "com.tensal.denden.action.BIXBY_RING"
const val SHORTCUT_BIXBY_QUIET = "denden_quiet"
const val SHORTCUT_BIXBY_NOTIFY = "denden_notify"
const val SHORTCUT_BIXBY_RING = "denden_ring"
