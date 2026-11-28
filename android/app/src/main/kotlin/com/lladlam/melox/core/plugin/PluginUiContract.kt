package com.lladlam.melox.core.plugin

import org.json.JSONArray
import org.json.JSONObject

enum class PluginUiSlot(val wire: String, val contextType: String? = null) {
    Extensions("extensions"), PluginDetails("pluginDetails"), Services("services"), Search("search"),
    Home("home"), Explore("explore"), Library("library"), SongMenu("songMenu", "track"),
    PlayerMenu("playerMenu", "track"), Playlist("playlist", "playlist"), Album("album", "album"),
    Artist("artist", "artist"), Lyrics("lyrics", "track"), Queue("queue", "track"),
    Navigation("navigation"), PlayerPage("playerPage", "track"),
}

data class PluginUiEntry(val id: String, val title: String, val slot: PluginUiSlot)

internal object PluginUiContract {
    val templates = setOf("settings", "connection", "collection", "detail", "search", "status")
    private val idPattern = Regex("[a-zA-Z0-9_-]{1,64}")
    private val kinds = setOf("toggle", "singleChoice", "multiChoice", "slider", "number", "text", "secret", "url", "button", "link", "status", "qr")

    fun entries(values: JSONArray): List<PluginUiEntry> {
        require(values.length() in 1..32)
        return List(values.length()) { index ->
            val value = values.getJSONObject(index)
            val wire = text(value, "slot", 32)
            val slot = requireNotNull(PluginUiSlot.entries.firstOrNull { it.wire == wire })
            PluginUiEntry(id(value, "id"), text(value, "title", 120), slot)
        }.also { entries -> require(entries.distinctBy { it.id }.size == entries.size) }
    }

    fun page(value: JSONObject) {
        require(text(value, "template", 32) in templates)
        text(value, "title", 120)
        if (value.has("hasMore")) require(value.opt("hasMore") is Boolean)
        val rows = value.getJSONArray("controls")
        require(rows.length() <= 50)
        val ids = mutableSetOf<String>()
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            require(ids.add(id(row, "id")))
            val type = text(row, "type", 32)
            require(type in kinds)
            text(row, "label", 120)
            if (row.has("description")) text(row, "description", 512, blank = true)
            if (row.has("disabled")) require(row.opt("disabled") is Boolean)
            when (type) {
                "toggle" -> require(row.opt("value") is Boolean)
                "text", "url", "status" -> text(row, "value", 2048, blank = true)
                "secret" -> require(!row.has("value"))
                "qr" -> text(row, "value", 512)
                "number" -> number(row, "value")
                "slider" -> {
                    val min = number(row, "min")
                    val max = number(row, "max")
                    require(min.toFloat() < max.toFloat() && number(row, "value") in min..max)
                }
                "singleChoice", "multiChoice" -> {
                    val options = row.getJSONArray("options")
                    require(options.length() in 1..30)
                    val choices = (0 until options.length()).map {
                        val option = options.getJSONObject(it)
                        text(option, "label", 120)
                        id(option, "id")
                    }.toSet()
                    require(choices.size == options.length())
                    if (type == "singleChoice") require(id(row, "value") in choices)
                    else {
                        val selected = row.getJSONArray("value")
                        require(selected.length() <= choices.size)
                        val values = (0 until selected.length()).map { selected.opt(it) as? String }
                        require(values.distinct().size == values.size && values.all { it in choices })
                    }
                }
                "link" -> id(row, "target")
            }
        }
    }

    fun action(value: JSONObject) {
        if (value.has("message")) text(value, "message", 512, blank = true)
        if (value.has("refresh")) require(value.opt("refresh") is Boolean)
    }

    private fun id(value: JSONObject, key: String) = text(value, key, 64).also { require(idPattern.matches(it)) }
    private fun number(value: JSONObject, key: String): Double {
        require(value.opt(key) is Number)
        return value.getDouble(key).also { require(it.isFinite() && it in -1e9..1e9) }
    }
    private fun text(value: JSONObject, key: String, max: Int, blank: Boolean = false): String {
        val text = value.opt(key) as? String ?: error("invalid UI field $key")
        require(text.length <= max && (blank || text.isNotBlank())) { "invalid UI field $key" }
        return text
    }
}
