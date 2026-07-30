package com.tensal.denden.ui

import android.content.res.Configuration
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
internal fun LocalizedTestTheme(
    localeTag: String = "zh-TW",
    content: @Composable () -> Unit
) = LocalizedTestContent(localeTag) {
    MaterialTheme(content = content)
}

@Composable
internal fun LocalizedTestContent(localeTag: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val activityResultRegistryOwner = checkNotNull(LocalActivityResultRegistryOwner.current)
    val locale = remember(localeTag) { Locale.forLanguageTag(localeTag) }
    val configuration = remember(baseConfiguration, locale) {
        Configuration(baseConfiguration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
    }
    val context = remember(base, configuration) { base.createConfigurationContext(configuration) }
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides configuration,
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
        content = content
    )
}
