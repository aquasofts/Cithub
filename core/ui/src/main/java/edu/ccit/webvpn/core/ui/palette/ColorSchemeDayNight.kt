package edu.ccit.webvpn.core.ui.palette

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

data class ColorSchemeDayNight(
    val lightColor: ColorScheme,
    val darkColor: ColorScheme,
) {
    fun getColorScheme(isDark: Boolean, isAmoled: Boolean): ColorScheme = when {

        // Create dark AMOLED background (except translucent theme)
        isDark && isAmoled && !darkColor.isTranslucent -> darkColor.copy(
            background = Color.Black,
            onBackground = Color.White,
            surface = Color.Black,
            onSurface = Color.White,
            surfaceContainerLowest = Color.Black
        )

        isDark -> darkColor

        else -> lightColor
    }
}

private val ColorScheme.isTranslucent: Boolean
    get() = surface == Color.Transparent
