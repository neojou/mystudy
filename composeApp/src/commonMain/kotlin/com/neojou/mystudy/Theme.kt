package com.neojou.mystudy

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import org.jetbrains.compose.resources.Font

/**
 * Bundled CJK-capable family — Noto Sans TC Regular (SIL OFL 1.1).
 *
 * Compose Wasm/Desktop draw with Skia, which does **not** pick up browser or
 * CSS fonts. Without a bundled CJK face, Chinese glyphs render as blank.
 */
@Composable
fun appFontFamily(): FontFamily = FontFamily(Font(Res.font.notosanstc_regular))

private fun Typography.withFontFamily(fontFamily: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = fontFamily),
    displayMedium = displayMedium.copy(fontFamily = fontFamily),
    displaySmall = displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = titleLarge.copy(fontFamily = fontFamily),
    titleMedium = titleMedium.copy(fontFamily = fontFamily),
    titleSmall = titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = bodySmall.copy(fontFamily = fontFamily),
    labelLarge = labelLarge.copy(fontFamily = fontFamily),
    labelMedium = labelMedium.copy(fontFamily = fontFamily),
    labelSmall = labelSmall.copy(fontFamily = fontFamily),
)

/**
 * App-wide Material 3 theme with a CJK-capable default typeface.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val fontFamily = appFontFamily()
    MaterialTheme(
        typography = Typography().withFontFamily(fontFamily),
        content = content,
    )
}
