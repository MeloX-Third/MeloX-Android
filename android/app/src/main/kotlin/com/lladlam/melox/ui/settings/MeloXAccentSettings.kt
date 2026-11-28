package com.lladlam.melox.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lladlam.melox.R
import com.lladlam.melox.ui.glass.MeloXIosListRow
import com.lladlam.melox.ui.theme.accessibleMeloXAccent
import com.lladlam.melox.ui.theme.parseMeloXAccent

@Composable
internal fun MeloXAccentSettings(context: Context) {
    var open by remember { mutableStateOf(false) }
    val saved = MeloXSettingsRuntime.accentHex
    MeloXIosListRow(title = stringResource(R.string.settings_accent_color),
        detail = saved.ifEmpty { stringResource(R.string.settings_accent_default) }, onClick = { open = true })
    if (!open) return
    var hex by remember { mutableStateOf(saved.ifEmpty { "#FF3B30" }) }
    val parsed = parseMeloXAccent(hex)
    val preview = parsed ?: Color(0xFFFF3B30)
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.settings_accent_color)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(hex, { hex = it.take(7) }, label = { Text("HEX") }, singleLine = true, isError = parsed == null)
                listOf(Color.White, Color(0xFF151518)).forEach { surface ->
                    Text(stringResource(R.string.settings_accent_color), color = accessibleMeloXAccent(preview, surface),
                        modifier = Modifier.fillMaxWidth().background(surface).padding(10.dp))
                }
                listOf("R" to preview.red, "G" to preview.green, "B" to preview.blue).forEachIndexed { index, (label, value) ->
                    Text(label)
                    Slider(value, { next ->
                        val channels = floatArrayOf(preview.red, preview.green, preview.blue)
                        channels[index] = next
                        val argb = Color(channels[0], channels[1], channels[2]).toArgb()
                        hex = "#%06X".format(java.util.Locale.ROOT, argb and 0xFFFFFF)
                    })
                }
                TextButton(onClick = { MeloXSettingsPreferences.setString(context, "theme_accent", ""); open = false }) {
                    Text(stringResource(R.string.settings_accent_default))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = {
                MeloXSettingsPreferences.setString(context, "theme_accent", "#" + hex.trim().removePrefix("#").uppercase(java.util.Locale.ROOT))
                open = false
            }) { Text(stringResource(R.string.settings_accent_apply)) }
        },
    )
}
