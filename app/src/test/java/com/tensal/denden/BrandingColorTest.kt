package com.tensal.denden

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandingColorTest {
    @Test
    fun builtInMascotUsesAppSurfaceForLightAndDarkThemes() {
        val originalDark = DenDenColors.darkMode
        val originalBrand = DenDenColors.brandColor
        try {
            DenDenColors.brandColor = null
            DenDenColors.darkMode = false
            assertEquals(DenDenColors.surfaceContainerLowest, DenDenColors.mascotBackground)
            assertEquals(Color.Black, DenDenColors.mascotForeground)

            DenDenColors.darkMode = true
            assertEquals(DenDenColors.surfaceContainerLowest, DenDenColors.mascotBackground)
            assertNotEquals(Color.Black, DenDenColors.mascotBackground)
            assertEquals(Color.White, DenDenColors.mascotForeground)
        } finally {
            DenDenColors.darkMode = originalDark
            DenDenColors.brandColor = originalBrand
        }
    }

    @Test
    fun brandColorChangesAccentWithoutChangingThemeBackground() {
        val originalDark = DenDenColors.darkMode
        val originalBrand = DenDenColors.brandColor
        try {
            DenDenColors.brandColor = Color(0xFFFFCC33)
            DenDenColors.darkMode = false
            val lightBackground = DenDenColors.mascotBackground
            assertTrue(contrast(DenDenColors.primary, DenDenColors.background) >= 3f)

            DenDenColors.darkMode = true
            assertTrue(contrast(DenDenColors.primary, DenDenColors.background) >= 3f)
            assertNotEquals(lightBackground, DenDenColors.mascotBackground)
        } finally {
            DenDenColors.darkMode = originalDark
            DenDenColors.brandColor = originalBrand
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
