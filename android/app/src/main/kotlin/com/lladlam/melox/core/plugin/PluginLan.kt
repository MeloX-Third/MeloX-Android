package com.lladlam.melox.core.plugin

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** User-entered origin, isolated per plugin. The first version accepts private IPv4 literals. */
internal class PluginLan(context: Context, private val manifest: PluginManifest) {
    private val prefs = context.applicationContext.getSharedPreferences("melox_plugin_lan", Context.MODE_PRIVATE)
    private val key = manifest.sourceId.value
    private val vault = PluginAccountVault(context, manifest.sourceId)
    fun origin(): HttpUrl? = if (manifest.lanCookie == null) null else prefs.getString(key, null)?.let { runCatching { parse(it) }.getOrNull() }
    fun save(raw: String?) = synchronized(lock) {
        val parsed = raw?.takeIf { it.isNotBlank() }?.let(::parse)
        if (parsed == origin()) return@synchronized
        signOut()
        check(prefs.edit().putString(key, parsed?.toString()).commit())
    }
    fun stamp(): Pair<HttpUrl?, String?> = origin() to vault.sessionStamp()
    fun cookie(origin: HttpUrl): String? {
        val policy = policy(origin)
        return vault.lease()?.takeIf { it.policy == policy }?.let { policy.prefix + it.token }
    }
    fun signIn(origin: HttpUrl, token: String) = synchronized(lock) {
        require(origin == origin() && token.matches(Regex("[A-Za-z0-9._~+\\/=%-]{1,4096}")))
        val previous = vault.selected()
        vault.importTokenAndActivate(origin.host, token, policy(origin))
        previous?.let(vault::remove)
        Unit
    }
    fun signOut() = synchronized(lock) { vault.selected()?.let(vault::remove); Unit }
    private fun policy(origin: HttpUrl) = PluginAuthentication("apiToken", "Cookie", "${manifest.lanCookie}=", setOf(origin.toString()))

    companion object {
        private val lock = Any()
        fun clearOrigin(context: Context, source: com.lladlam.melox.core.music.model.SourceId) {
            check(context.applicationContext.getSharedPreferences("melox_plugin_lan", Context.MODE_PRIVATE)
                .edit().remove(source.value).commit())
        }
        fun parse(raw: String): HttpUrl {
            val url = raw.trim().toHttpUrl()
            require(isPrivateIpv4(url.host) && url.encodedPath == "/" && url.query == null && url.fragment == null &&
                url.username.isEmpty() && url.password.isEmpty()) { "请输入局域网 IPv4 地址和端口" }
            return url
        }
        fun isPrivateIpv4(host: String): Boolean {
            val parts = host.split('.').mapNotNull(String::toIntOrNull)
            return parts.size == 4 && parts.joinToString(".") == host && parts.all { it in 0..255 } &&
                (parts[0] == 10 || parts[0] == 172 && parts[1] in 16..31 || parts[0] == 192 && parts[1] == 168)
        }
        fun matches(origin: HttpUrl?, url: HttpUrl): Boolean = origin != null &&
            origin.scheme == url.scheme && origin.host == url.host && origin.port == url.port &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null
    }
}
