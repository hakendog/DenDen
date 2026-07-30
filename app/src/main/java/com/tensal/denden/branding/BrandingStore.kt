package com.tensal.denden.branding

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.tensal.denden.MainActivity
import com.tensal.denden.R
import com.tensal.denden.withSelectedAppLanguage

data class CachedBranding(
    val revision: String,
    val brandColor: Int?,
    val backgroundColor: Int?,
    val mascot: Bitmap,
    val shortcut: Bitmap
)

fun NotificationCompat.Builder.applyDenDenBranding(context: Context): NotificationCompat.Builder = apply {
    setSmallIcon(R.drawable.ic_notification)
    val store = DirectBrandStore(context.applicationContext)
    store.activeStatusMask()?.let { setSmallIcon(IconCompat.createWithBitmap(it)) }
    store.activeBrandColor()?.let(::setColor)
}

fun requestOrUpdateCustomShortcut(context: Context, branding: CachedBranding): Boolean {
    val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
    val shortcut = buildShortcut(context, Icon.createWithAdaptiveBitmap(branding.shortcut))
    if (manager.pinnedShortcuts.any { it.id == CUSTOM_SHORTCUT_ID }) return manager.updateShortcuts(listOf(shortcut))
    return manager.isRequestPinShortcutSupported && manager.requestPinShortcut(shortcut, null)
}

fun updateExistingCustomShortcut(context: Context, branding: CachedBranding?): Boolean {
    val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
    if (manager.pinnedShortcuts.none { it.id == CUSTOM_SHORTCUT_ID }) return true
    val icon = branding?.shortcut?.let(Icon::createWithAdaptiveBitmap)
        ?: Icon.createWithResource(context, R.mipmap.ic_launcher)
    return manager.updateShortcuts(listOf(buildShortcut(context, icon)))
}

fun retryPendingShortcutUpdate(context: Context) {
    val store = DirectBrandStore(context.applicationContext)
    val pending = store.pendingShortcutUpdate() ?: return
    if (updateExistingCustomShortcut(context, store.load())) store.markShortcutUpdated(pending)
}

private fun buildShortcut(context: Context, icon: Icon): ShortcutInfo =
    ShortcutInfo.Builder(context, CUSTOM_SHORTCUT_ID)
        .setShortLabel("DenDen")
        .setLongLabel(context.withSelectedAppLanguage().getString(R.string.open_denden))
        .setIcon(icon)
        .setIntent(Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        })
        .build()

private const val CUSTOM_SHORTCUT_ID = "denden-custom"
