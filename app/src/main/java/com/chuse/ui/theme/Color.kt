package com.chuse.ui.theme

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
    primary = Color(0xFF1B6B52),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA5F2D0),
    onPrimaryContainer = Color(0xFF002017),
    secondary = Color(0xFF4C6358),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9DB),
    onSecondaryContainer = Color(0xFF092017),
    tertiary = Color(0xFF8A6A17),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9B),
    onTertiaryContainer = Color(0xFF2B2000),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDBE5DE),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFBFC9C2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF89D6B5),
    onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF00513B),
    onPrimaryContainer = Color(0xFFA5F2D0),
    secondary = Color(0xFFB3CCBF),
    onSecondary = Color(0xFF1E352B),
    secondaryContainer = Color(0xFF344C41),
    onSecondaryContainer = Color(0xFFCFE9DB),
    tertiary = Color(0xFFE9C46C),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5A4300),
    onTertiaryContainer = Color(0xFFFFDF9B),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surface = Color(0xFF191C1A),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFBFC9C2),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404943),
)

/**
 * Material 3 with dynamic colour where the device has it.
 *
 * Dynamic colour is opt-in from Android 12. Most Chinese ROMs are on 12 or newer and several
 * expose a good palette, but plenty do not — so the hand-built scheme above is the floor, not
 * a fallback, and has to look deliberate on its own. Both paths are exercised by the
 * previews.
 */
@Composable
fun ChuseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ChuseTypography,
        content = content,
    )
}