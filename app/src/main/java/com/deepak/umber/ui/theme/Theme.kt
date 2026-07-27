package com.deepak.umber.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6E43),
    onPrimary = Color.White,
    secondary = Color(0xFF4B635B),
    error = Color(0xFFB3261E),
    background = Color(0xFFFBFDF8),
    surface = Color(0xFFFBFDF8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD8AC),
    onPrimary = Color(0xFF00391F),
    secondary = Color(0xFFB2CCC0),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF191C1A),
    surface = Color(0xFF191C1A),
)

@Composable
fun UmberTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * Stable per-category colour.
 *
 * Derived from the category name's hash rather than a hand-picked map so that adding a category
 * never requires touching the palette — and the same category always gets the same hue across the
 * chart, the chips and the list.
 */
fun categoryColor(category: String): Color {
    val hue = ((category.hashCode() and 0x7FFFFFFF) % 360).toFloat()
    return Color.hsl(hue, saturation = 0.45f, lightness = 0.55f)
}
