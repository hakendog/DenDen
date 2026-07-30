package com.tensal.denden.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tensal.denden.automation.tasker.TaskerAutomationActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun manifestPublishesExactlyThreeFixedBixbyActions() {
        val shortcuts = context.getSystemService(ShortcutManager::class.java).manifestShortcuts
        assertEquals(
            setOf(SHORTCUT_BIXBY_QUIET, SHORTCUT_BIXBY_NOTIFY, SHORTCUT_BIXBY_RING),
            shortcuts.mapTo(mutableSetOf()) { it.id }
        )
        assertEquals(
            setOf(ACTION_BIXBY_QUIET, ACTION_BIXBY_NOTIFY, ACTION_BIXBY_RING),
            shortcuts.mapNotNullTo(mutableSetOf()) { it.intent?.action }
        )
    }

    @Test
    fun trampolineIsPrivateAndTaskerConfigIsDiscoverable() {
        val packageManager = context.packageManager
        val trampoline = packageManager.getActivityInfo(ComponentName(context, BixbyAutomationActivity::class.java), 0)
        assertFalse(trampoline.exported)
        val tasker = packageManager.getActivityInfo(ComponentName(context, TaskerAutomationActivity::class.java), 0)
        assertTrue(tasker.exported)
        val matches = packageManager.queryIntentActivities(
            Intent("com.twofortyfouram.locale.intent.action.EDIT_SETTING").setPackage(context.packageName),
            0
        )
        assertTrue(matches.any { it.activityInfo.name == TaskerAutomationActivity::class.java.name })
    }
}
