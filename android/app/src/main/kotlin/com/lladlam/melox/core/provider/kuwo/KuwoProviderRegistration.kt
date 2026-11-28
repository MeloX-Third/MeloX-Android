package com.lladlam.melox.core.provider.kuwo

import android.content.Context
import com.lladlam.melox.core.music.provider.PlaybackAccountStore
import com.lladlam.melox.core.music.provider.selectPlaybackSession
import okhttp3.OkHttpClient

internal object KuwoProviderRegistration {
    fun create(context: Context, httpClient: OkHttpClient, forPlayback: Boolean = false): KuwoProvider =
        KuwoProvider(
            context = context,
            sessionProvider = {
                if (forPlayback) playbackSession(context) else KuwoSessionStore.read(context)
            },
            httpClient = httpClient,
        )

    fun account(context: Context): Pair<Boolean, String?> = KuwoSessionStore.read(context).let { session ->
        session.isLoggedIn to session.userId.takeIf(String::isNotBlank)
    }

    fun logout(context: Context) = KuwoSessionStore.clear(context)

    fun clearPlaybackAccount(context: Context) = KuwoSessionStore.clear(context, playback = true)

    private fun playbackSession(context: Context): KuwoSession = selectPlaybackSession(
        PlaybackAccountStore.isEnabled(context),
        KuwoSessionStore.read(context, playback = true),
        KuwoSessionStore.read(context),
        KuwoSession::isLoggedIn,
    )
}
