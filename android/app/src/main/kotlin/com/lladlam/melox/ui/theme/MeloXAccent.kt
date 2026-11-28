package com.lladlam.melox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.lladlam.melox.ui.glass.MeloXSystemColors
import com.lladlam.melox.ui.settings.MeloXSettingsRuntime

internal fun parseMeloXAccent(value: String): Color? = value.trim().removePrefix("#")
    .takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }
    ?.toInt(16)?.let { Color(it or 0xFF000000.toInt()) }

internal fun accessibleMeloXAccent(color: Color, background: Color): Color {
    val luminance = background.luminance()
    val end = if (luminance > 0.179f) Color.Black else Color.White
    fun contrast(sample: Color): Float {
        val foreground = sample.luminance()
        return (maxOf(foreground, luminance) + .05f) / (minOf(foreground, luminance) + .05f)
    }
    if (contrast(color) >= 4.5f) return color
    var low = 0f
    var high = 1f
    repeat(12) {
        val middle = (low + high) / 2f
        if (contrast(lerp(color, end, middle)) >= 4.5f) high = middle else low = middle
    }
    return lerp(color, end, high)
}

@Composable
internal fun meloXAccent(background: Color = MaterialTheme.colorScheme.surface): Color =
    parseMeloXAccent(MeloXSettingsRuntime.accentHex)?.let { accessibleMeloXAccent(it, background) }
        ?: MeloXSystemColors.Red
