package com.lladlam.melox.ui.plugin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import com.lladlam.melox.playback.ProviderPlaybackCommands
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.SourceId
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.TrackAvailability
import com.lladlam.melox.core.plugin.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun hasPluginUiEntries(slot: PluginUiSlot, filter: String): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { MeloXPlugins.get(context) }
    val states by manager.plugins.collectAsState()
    return states.values.any { state -> state.status == PluginManager.Status.Enabled &&
        state.manifest.contributions.any { it.slot == slot && matches(it, state, filter) } }
}

private fun matches(entry: PluginUiEntry, state: PluginManager.State, filter: String): Boolean =
    filter.isBlank() || entry.title.contains(filter, true) || state.manifest.name.contains(filter, true)

@Composable
internal fun PluginUiEntries(
    slot: PluginUiSlot,
    owner: SourceId? = null,
    resource: MusicResourceId? = null,
    filter: String = "",
    initialQuery: String = "",
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    entryLabel: String = "扩展",
) {
    if (slot.contextType != null && resource == null) return
    val context = LocalContext.current
    val manager = remember(context) { MeloXPlugins.get(context) }
    val states by manager.plugins.collectAsState()
    val selectedOwner = resource?.sourceId ?: owner
    val entries = remember(states, slot, selectedOwner, filter) {
        states.values.filter {
            it.status == PluginManager.Status.Enabled && (selectedOwner == null || it.manifest.sourceId == selectedOwner)
        }.flatMap { state ->
            state.manifest.contributions.filter {
                it.slot == slot && matches(it, state, filter)
            }.map { state to it }
        }.sortedBy { (state, entry) -> state.manifest.name + entry.title }
    }
    if (entries.isEmpty()) return
    var opened by remember(slot, resource, owner) { mutableStateOf<Pair<PluginManager.State, PluginUiEntry>?>(null) }
    var choose by remember(slot, resource, owner) { mutableStateOf(false) }
    if (compact) {
        TextButton(modifier = modifier, onClick = { choose = true }) { Text(entryLabel) }
    } else {
        Column(modifier) {
            entries.forEach { (state, entry) ->
                TextButton(onClick = { opened = state to entry }) { Text("${entry.title} · ${state.manifest.name}") }
            }
        }
    }
    if (choose) AlertDialog(
        onDismissRequest = { choose = false },
        title = { Text("插件扩展") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                entries.forEach { (state, entry) ->
                    TextButton(onClick = { choose = false; opened = state to entry }) { Text("${entry.title} · ${state.manifest.name}") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choose = false }) { Text("关闭") } },
    )
    opened?.let { (state, entry) ->
        if (states[state.manifest.sourceId] === state) key(state, entry.id, resource) {
            PluginUiDialog(manager, state, entry, resource, initialQuery.take(1024)) { opened = null }
        }
    }
}

@Composable
private fun PluginUiDialog(
    manager: PluginManager,
    state: PluginManager.State,
    entry: PluginUiEntry,
    resource: MusicResourceId?,
    initialQuery: String,
    close: () -> Unit,
) {
    val context = LocalContext.current
    var stack by remember { mutableStateOf(listOf(entry.id)) }
    var index by remember { mutableIntStateOf(1) }
    var query by remember { mutableStateOf(initialQuery) }
    var revision by remember { mutableIntStateOf(0) }
    var content by remember { mutableStateOf<JSONObject?>(null) }
    var tracks by remember { mutableStateOf(emptyList<MusicTrack>()) }
    var working by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun request() = JSONObject().put("entry", entry.id).put("page", stack.last()).put("index", index).put("query", query).apply {
        if (resource != null) put("scope", JSONObject().put("type", entry.slot.contextType).put("id", resource.value))
    }
    LaunchedEffect(stack, index, query, revision) {
        content = null
        loadError = null
        try {
            val page = manager.invokeUi(state, "uiPage", request())
            val codec = PluginCatalogCodec(state.manifest)
            tracks = page.optJSONArray("tracks")?.let { items ->
                List(items.length()) { codec.track(items.getJSONObject(it)) }
            }.orEmpty()
            content = page
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { loadError = PluginErrors.describe(failure) }
    }
    fun back() {
        if (stack.size == 1) close() else {
            content = null
            stack = stack.dropLast(1)
            index = 1
        }
    }
    Dialog(onDismissRequest = { if (!working) back() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 720.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.manifest.name, style = MaterialTheme.typography.labelMedium)
                Text(content?.optString("title") ?: entry.title, style = MaterialTheme.typography.titleLarge)
                message?.let { Text(it) }
                val page = content
                if (page == null) {
                    val error = loadError
                    if (error == null) LinearProgressIndicator() else {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { revision++ }) { Text("重试") }
                    }
                } else {
                    if (page.optString("template") == "search") {
                        var draft by remember(query) { mutableStateOf(query) }
                        OutlinedTextField(draft, { draft = it.take(1024) }, label = { Text("搜索") }, singleLine = true, enabled = !working)
                        TextButton(enabled = !working, onClick = { content = null; query = draft; index = 1; revision++ }) { Text("搜索") }
                    }
                    val playableTracks = tracks.filter {
                        it.availability in setOf(TrackAvailability.Unknown, TrackAvailability.Playable, TrackAvailability.PreviewOnly)
                    }
                    val playbackSupported = PluginCapability.Playback in state.manifest.capabilities
                    tracks.forEach { track ->
                        TextButton(enabled = playbackSupported && track in playableTracks && !working, onClick = {
                            ProviderPlaybackCommands.playQueue(context, playableTracks, track.id)
                        }) { Text(track.title) }
                    }
                    val rows = page.getJSONArray("controls")
                    repeat(rows.length()) { item ->
                        val row = rows.getJSONObject(item)
                        key(page, row.getString("id")) {
                            PluginUiControl(row, !working, onLink = {
                                if (stack.size < 12) {
                                    content = null
                                    stack = stack + it
                                    index = 1
                                }
                            }) { value ->
                                if (!working) {
                                    working = true
                                    message = null
                                    val action = request().put("control", row.getString("id"))
                                    if (value != null) action.put("value", value)
                                    scope.launch {
                                        try {
                                            val result = manager.invokeUi(state, "uiAction", action)
                                            message = result.optString("message").takeIf(String::isNotBlank)
                                            if (result.optBoolean("refresh", true)) revision++
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) { message = PluginErrors.describe(failure) }
                                        finally { working = false }
                                    }
                                }
                            }
                        }
                    }
                    if (index > 1 || page.optBoolean("hasMore")) Row {
                        TextButton(enabled = !working && index > 1, onClick = { content = null; index-- }) { Text("上一页") }
                        TextButton(enabled = !working && page.optBoolean("hasMore") && index < 10000, onClick = { content = null; index++ }) { Text("下一页") }
                    }
                }
                TextButton(onClick = ::back, enabled = !working) { Text(if (stack.size == 1) "关闭" else "返回") }
            }
        }
    }
}

@Composable
private fun PluginUiControl(row: JSONObject, available: Boolean, onLink: (String) -> Unit, submit: (Any?) -> Unit) {
    val enabled = available && !row.optBoolean("disabled")
    val label = row.getString("label")
    val type = row.getString("type")
    when (type) {
        "toggle" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(row.getBoolean("value"), { submit(it) }, enabled = enabled)
        }
        "singleChoice", "multiChoice" -> {
            Text(label)
            val options = row.getJSONArray("options")
            val multiple = type == "multiChoice"
            val selected = if (multiple) row.getJSONArray("value").let { array ->
                List(array.length()) { array.getString(it) }.toSet()
            } else setOf(row.getString("value"))
            repeat(options.length()) { index ->
                val option = options.getJSONObject(index)
                val id = option.getString("id")
                val change = {
                    submit(if (multiple) JSONArray((if (id in selected) selected - id else selected + id).toList()) else id)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (multiple) Checkbox(id in selected, { change() }, enabled = enabled)
                    else RadioButton(id in selected, change, enabled = enabled)
                    Text(option.getString("label"))
                }
            }
        }
        "slider" -> {
            Text(label)
            var level by remember(row) { mutableFloatStateOf(row.getDouble("value").toFloat()) }
            Slider(level, { level = it }, enabled = enabled, valueRange = row.getDouble("min").toFloat()..row.getDouble("max").toFloat(), onValueChangeFinished = { submit(level.toDouble()) })
        }
        "number", "text", "secret", "url" -> {
            var draft by remember(row) { mutableStateOf(row.optString("value")) }
            val number = draft.toDoubleOrNull()?.takeIf { it.isFinite() && it in -1e9..1e9 }
            val url = if (type == "url") runCatching { java.net.URI(draft) }.getOrNull() else null
            val valid = when (type) {
                "number" -> number != null
                "url" -> url?.scheme in setOf("http", "https") && url?.host != null && url.rawUserInfo == null
                "secret" -> draft.isNotEmpty()
                else -> true
            }
            OutlinedTextField(draft, { draft = it.take(2048) }, label = { Text(label) }, singleLine = true, enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = when (type) {
                    "number" -> KeyboardType.Decimal
                    "url" -> KeyboardType.Uri
                    "secret" -> KeyboardType.Password
                    else -> KeyboardType.Text
                }), visualTransformation = if (type == "secret") PasswordVisualTransformation() else VisualTransformation.None)
            TextButton(enabled = enabled && valid, onClick = {
                submit(if (type == "number") number else draft)
                if (type == "secret") draft = ""
            }) { Text("保存") }
        }
        "button" -> Button(onClick = { submit(null) }, enabled = enabled) { Text(label) }
        "link" -> TextButton(onClick = { onLink(row.getString("target")) }, enabled = enabled) { Text(label) }
        "status" -> { Text(label, style = MaterialTheme.typography.labelMedium); Text(row.getString("value")) }
        "qr" -> {
            Text(label)
            val data = row.getString("value")
            val matrix = remember(data) { runCatching { QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8")) }.getOrNull() }
            if (matrix != null) Canvas(Modifier.size(180.dp)) {
                drawRect(Color.White)
                val unit = size.width / matrix.width
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) {
                    drawRect(Color.Black, Offset(x * unit, y * unit), Size(unit + .3f, unit + .3f))
                }
            }
        }
    }
    if (row.has("description")) Text(row.getString("description"), style = MaterialTheme.typography.bodySmall)
}
