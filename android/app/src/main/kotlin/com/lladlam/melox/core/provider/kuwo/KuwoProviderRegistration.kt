package com.lladlam.melox.core.provider.kuwo

import android.content.Context
import com.lladlam.melox.core.music.provider.PlaybackAccountStore
import okhttp3.OkHttpClient

internal object KuwoProviderRegistration {
    fun create(context: Context, httpClient: OkHttpClient, forPlayback: Boolean = false): KuwoProvider =
        KuwoProvider(
            sessionProvider = {
                if (forPlayback) PlaybackAccountStore.kuwoSession(context) else KuwoSessionStore.read(context)
            },
            httpClient = httpClient,
        )
}
