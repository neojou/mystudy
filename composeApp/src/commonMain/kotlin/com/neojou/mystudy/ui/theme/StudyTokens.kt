package com.neojou.mystudy.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow

/**
 * Explicit lavender tokens. The numbers match Material 3 baseline light,
 * so the app keeps the current palette while the scheme is no longer implicit.
 */
data class StudyTokens(
    val heading1: Color,
    val heading2: Color,
    val heading3: Color,
    val heading4: Color,
    val heading5: Color,
    val link: Color,
    val noteCallout: Color,
    val tipCallout: Color,
    val warningCallout: Color,
    val dangerCallout: Color,
    val readingMaxWidth: Dp,
    val contentPadding: Dp,
    val documentTitleSize: TextUnit,
    val documentTitleLine: TextUnit,
    val bodySize: TextUnit,
    val bodyLine: TextUnit,
    val codeSize: TextUnit,
    val metaSize: TextUnit,
    val treeSize: TextUnit,
    val treeRowHeight: Dp,
    val cardRadius: Dp,
    val chipRadius: Dp,
) {
    fun headingColor(level: Int): Color = when (level) {
        1 -> heading1
        2 -> heading2
        3 -> heading3
        4 -> heading4
        else -> heading5
    }

    companion object {
        val Light = StudyTokens(
            heading1 = Color(0xFF4F378B),
            heading2 = Color(0xFF6750A4),
            heading3 = Color(0xFF7D5260),
            heading4 = Color(0xFF625B71),
            heading5 = Color(0xFF49454F),
            link = Color(0xFF6750A4),
            noteCallout = Color(0xFF6750A4),
            tipCallout = Color(0xFF1B7F4E),
            warningCallout = Color(0xFF8A5A00),
            dangerCallout = Color(0xFFB3261E),
            readingMaxWidth = 760.dp,
            contentPadding = 32.dp,
            documentTitleSize = 26.sp,
            documentTitleLine = 34.sp,
            bodySize = 15.sp,
            bodyLine = 26.sp,
            codeSize = 13.sp,
            metaSize = 12.sp,
            treeSize = 13.sp,
            treeRowHeight = 28.dp,
            cardRadius = 10.dp,
            chipRadius = 6.dp,
        )

        val Surface = Color(0xFFFEF7FF)
        val OnSurface = Color(0xFF1D1B20)
    }
}

val LocalStudyTokens = staticCompositionLocalOf { StudyTokens.Light }

/** WCAG contrast ratio. Inputs are opaque sRGB colors. */
fun contrastRatio(foreground: Color, background: Color): Double {
    val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
    val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val s = value.toDouble()
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}
