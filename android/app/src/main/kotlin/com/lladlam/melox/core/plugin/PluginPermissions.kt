package com.lladlam.melox.core.plugin

/** Comparisons use parsed permissions, never publisher descriptions. */
object PluginPermissions {
    fun expands(previous: PluginManifest, next: PluginManifest): Boolean =
        !previous.networkDomains.containsAll(next.networkDomains) ||
            !previous.capabilities.containsAll(next.capabilities) ||
            previous.authentication != next.authentication

    fun describe(manifest: PluginManifest): String = buildString {
        append("API ").append(manifest.apiVersion)
        append("\n能力：").append(manifest.capabilities.joinToString { it.wireName }.ifEmpty { "无" })
        append("\n网络域名：").append(manifest.networkDomains.sorted().joinToString().ifEmpty { "无网络权限" })
        if (PluginCapability.PlaylistWrite in manifest.capabilities) {
            append("\n歌单写入：可向当前账号的可编辑歌单添加歌曲")
        }
        if (PluginCapability.PlaylistCreate in manifest.capabilities) {
            append("\n歌单创建：可在当前账号下创建歌单")
        }
        if (PluginCapability.PlaylistRename in manifest.capabilities) {
            append("\n歌单改名：可修改当前账号的可编辑歌单名称")
        }
        if (PluginCapability.PlaylistDelete in manifest.capabilities) {
            append("\n歌单删除：可删除当前账号的可编辑歌单")
        }
        if (PluginCapability.PlaylistRemoveMember in manifest.capabilities) {
            append("\n歌单成员移除：可移除当前账号可编辑歌单中的歌曲")
        }
        if (PluginCapability.Resolver in manifest.capabilities) {
            append("\n解析器：可返回任意 HTTP(S) 渐进式音频 URL（不要求域名白名单）")
        }
        manifest.authentication?.let {
            append("\n认证：").append(it.type)
            append("\n凭据域名：").append(it.domains.sorted().joinToString().ifEmpty { "无" })
            if (it.domains.isNotEmpty()) append("\n凭据请求头：").append(it.header)
            it.oauth?.let { oauth ->
                append("\n授权端点：").append(oauth.authorizationEndpoint)
                append("\nToken 端点：").append(oauth.tokenEndpoint)
                append("\n授权范围：").append(oauth.scopes.joinToString())
            }
        }
    }
}
