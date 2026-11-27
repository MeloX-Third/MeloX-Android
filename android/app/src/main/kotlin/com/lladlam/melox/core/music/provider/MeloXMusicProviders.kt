package com.lladlam.melox.core.music.provider

import android.content.Context
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.SourceId
import com.lladlam.melox.core.provider.spotify.SpotifySessionStore
import com.lladlam.melox.core.provider.youtubemusic.YouTubeSessionStore
import com.lladlam.melox.core.account.NeteaseSessionStore
import com.lladlam.melox.core.provider.applemusic.AppleMusicApiClient
import com.lladlam.melox.core.provider.applemusic.AppleMusicSessionStore
import com.lladlam.melox.core.provider.kugou.KugouLiteProvider
import com.lladlam.melox.core.provider.kugou.KugouProvider
import com.lladlam.melox.core.provider.kugou.KugouSessionStore
import com.lladlam.melox.core.provider.kuwo.KuwoProviderRegistration
import com.lladlam.melox.core.provider.netease.NeteaseProvider
import com.lladlam.melox.core.provider.qqmusic.QQMusicProvider
import com.lladlam.melox.core.provider.qqmusic.QQMusicSessionStore
import com.lladlam.melox.core.provider.bilibili.BilibiliProvider
import com.lladlam.melox.core.provider.bilibili.BilibiliSessionStore
import com.lladlam.melox.core.provider.bilibili.BilibiliPlaybackAssociationStore
import com.lladlam.melox.core.provider.bilibili.BilibiliApiCache
import com.lladlam.melox.core.provider.bilibili.BilibiliDirectedTrafficPolicy
import com.lladlam.melox.core.provider.spotify.SpotifyClientConfig
import com.lladlam.melox.core.provider.spotify.SpotifyProvider
import com.lladlam.melox.core.provider.youtubemusic.YouTubeMusicProvider
import com.lladlam.melox.core.provider.jellyfin.JellyfinProvider
import com.lladlam.melox.core.provider.jellyfin.JellyfinSessionStore
import com.lladlam.melox.core.provider.local.LocalProvider
import com.lladlam.melox.core.network.MeloXHttpClient
import okhttp3.OkHttpClient
import com.lladlam.melox.core.provider.qishui.QishuiProvider
import com.lladlam.melox.core.provider.qishui.QishuiSessionStore

/** Creates provider instances that all read their authentication state locally. */
object MeloXMusicProviders {
    private val dynamicProviders = DynamicMusicProviders()
    @Volatile
    private var sharedRegistry: MusicProviderRegistry? = null

    fun create(
        context: Context,
        httpClient: OkHttpClient = MeloXHttpClient.shared,
    ): MusicProviderRegistry {
        val appContext = context.applicationContext
        if (httpClient !== MeloXHttpClient.shared) return buildRegistry(appContext, httpClient)
        return sharedRegistry ?: synchronized(this) {
            sharedRegistry ?: buildRegistry(appContext, httpClient).also { sharedRegistry = it }
        }
    }

    /** Registry reserved for URL resolution and quality probing. */
    fun createPlayback(
        context: Context,
        httpClient: OkHttpClient = MeloXHttpClient.shared,
    ): MusicProviderRegistry = buildRegistry(context.applicationContext, httpClient, forPlayback = true)

    private fun playbackCacheScopes(context: Context): Map<SourceId, () -> String> {
        return mapOf(
            SourceId.fromBuiltIn(MusicSource.Netease) to { PlaybackAccountStore.neteaseCookie(context) },
            SourceId.fromBuiltIn(MusicSource.QQMusic) to { PlaybackAccountStore.qqSession(context).cookie },
            SourceId.fromBuiltIn(MusicSource.Kugou) to { PlaybackAccountStore.kugouSession(context).let { session ->
                listOf(session.userId, session.token, session.vipToken, session.dfid).joinToString("|")
            } },
            SourceId.fromBuiltIn(MusicSource.KugouLite) to { KugouSessionStore.readLite(context).let { session ->
                listOf(session.userId, session.token, session.vipToken, session.dfid, session.auth).joinToString("|")
            } },
            SourceId.fromBuiltIn(MusicSource.AppleMusic) to { AppleMusicSessionStore.read(context).let { session ->
                listOf(session.developerToken, session.musicUserToken, session.storefront).joinToString("|")
            } },
            SourceId.fromBuiltIn(MusicSource.Bilibili) to { listOf(
                BilibiliSessionStore.read(context).cookie,
                BilibiliPlaybackAssociationStore.revision(context),
                "directedTraffic=${BilibiliDirectedTrafficPolicy.isActive(context)}",
            ).joinToString("|") },
            SourceId.fromBuiltIn(MusicSource.Spotify) to { SpotifySessionStore.read(context).let { session ->
                listOf(session.accountId, session.expiresAtEpochMs).joinToString("|")
            } },
            SourceId.fromBuiltIn(MusicSource.YouTubeMusic) to { YouTubeSessionStore.authFingerprint(context) },
            SourceId.fromBuiltIn(MusicSource.Kuwo) to { "" },
            SourceId.fromBuiltIn(MusicSource.Jellyfin) to { JellyfinSessionStore.read(context).let { session ->
                listOf(session.serverUrl, session.accessToken, session.userId).joinToString("|")
            } },
            SourceId.fromBuiltIn(MusicSource.Local) to { "local" },
            SourceId.fromBuiltIn(MusicSource.Qishui) to { QishuiSessionStore.read(context).sessionId },
        )
    }

    private fun liteProvider(context: Context, httpClient: OkHttpClient): KugouLiteProvider =
        KugouLiteProvider(
            sessionProvider = { KugouSessionStore.readLite(context) },
            httpClient = httpClient,
            persistAuth = { auth -> KugouSessionStore.updateLiteEntitlements(context, auth = auth) },
            persistDfid = { dfid -> KugouSessionStore.updateLiteDfid(context, dfid) },
            onAuthExpired = { KugouSessionStore.clearLiteLogin(context) },
            persistVip = { vip ->
                KugouSessionStore.updateLiteEntitlements(
                    context = context,
                    vipType = vip.vipType,
                    vipExpiresAtEpochSeconds = vip.expiresAtEpochSeconds,
                    superVip = vip.superVip,
                    vipActive = vip.isVip,
                    freeListenEligible = vip.freeListen,
                )
            },
        )

    private fun buildRegistry(
        context: Context,
        httpClient: OkHttpClient,
        forPlayback: Boolean = false,
    ): MusicProviderRegistry {
        val nativeProviders = buildList<MusicProvider> {
            add(NeteaseProvider(
                cookieProvider = {
                    if (forPlayback) PlaybackAccountStore.neteaseCookie(context) else NeteaseSessionStore.readCookie(context)
                },
                httpClient = httpClient,
            ))
            add(QQMusicProvider(
                sessionProvider = {
                    if (forPlayback) PlaybackAccountStore.qqSession(context) else QQMusicSessionStore.read(context)
                },
                httpClient = httpClient,
            ))
            if (forPlayback) add(KugouProvider({ PlaybackAccountStore.kugouSession(context) }, httpClient))
            add(liteProvider(context, httpClient))
            add(QishuiProvider({ QishuiSessionStore.read(context) }, httpClient))
            add(KuwoProviderRegistration.create(context, httpClient, forPlayback))
            add(AppleMusicApiClient({ AppleMusicSessionStore.read(context) }, httpClient))
            add(BilibiliProvider(
                sessionProvider = { BilibiliSessionStore.read(context) },
                httpClient = httpClient,
                associationProvider = { bvid, cid -> BilibiliPlaybackAssociationStore.read(context, bvid, cid) },
                apiCache = BilibiliApiCache.shared(context),
                sessionRevisionProvider = { BilibiliSessionStore.revision(context) },
                directedTrafficProvider = { BilibiliDirectedTrafficPolicy.isActive(context) },
            ))
            add(JellyfinProvider({ JellyfinSessionStore.read(context) }, httpClient))
            add(LocalProvider(context))
            add(YouTubeMusicProvider(context, httpClient))
        }
        return MusicProviderRegistry(
            nativeProviders + SpotifyProvider(
                context,
                SpotifyClientConfig.effective(context),
                httpClient,
                playbackProviders = { nativeProviders },
            ),
            dynamic = dynamicProviders,
            awaitDynamicReady = { com.lladlam.melox.core.plugin.MeloXPlugins.awaitRestored() },
            playbackCacheScopes = if (forPlayback) playbackCacheScopes(context) else emptyMap(),
        )
    }
}
