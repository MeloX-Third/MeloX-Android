package com.lladlam.melox.playback

import android.content.Context
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.SourceId
import com.lladlam.melox.core.music.provider.MeloXMusicProviders
import com.lladlam.melox.core.music.provider.MusicProviderRegistry

/** Process-local bridge used by Media3's synchronous resolver callback. */
object ProviderPlaybackRuntime {
    @Volatile private var currentRegistry: MusicProviderRegistry? = null

    fun initialize(context: Context) {
        if (currentRegistry != null) return
        synchronized(this) {
            if (currentRegistry == null) currentRegistry = MeloXMusicProviders.createPlayback(context.applicationContext)
        }
    }

    fun registryOrNull(): MusicProviderRegistry? = currentRegistry

    fun authKey(source: MusicSource): String =
        currentRegistry?.playbackCacheKey(SourceId.fromBuiltIn(source)).orEmpty()
}
