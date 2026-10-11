package com.chuse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale kept to the platform families on purpose.
 *
 * Bundling a display font would look better in Latin script and worse in Chinese: a typeface
 * with no CJK coverage makes Android fall back per-glyph, which visibly breaks the rhythm of
 * a headline. The system stack renders both scripts from one family, so the Chinese layout
 * holds together.
 */
private val Default = Typography()

val ChuseTypography = Default.copy(
    displayLarge = Default.displayLarge.copy(fontWeight = FontWeight.SemiBold),
    displayMedium = Default.displayMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = Default.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Default.bodyLarge.copy(fontFamily = FontFamily.Default),
)

/** Numerals that need to align in a column, as in a ranking. */
val TabularNumberStyle = TextStyle(
    fontFeatureSettings = "tnum",
    fontWeight = FontWeight.Medium,
)