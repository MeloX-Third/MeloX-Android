package com.lladlam.melox.core.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lladlam.melox.core.network.MeloXHttpClient
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.OkHttpClient

@UnstableApi
internal class MediaStreamDurationProbe(
    context: Context,
    private val httpClient: OkHttpClient = MeloXHttpClient.shared,
) {
    private val appContext = context.applicationContext

    fun durationMs(url: String, headers: Map<String, String>): Long? {
        val dataSource = OkHttpDataSource.Factory(httpClient).setDefaultRequestProperties(headers)
        return MetadataRetriever.Builder(appContext, MediaItem.fromUri(url))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .use { retriever ->
                val duration = retriever.retrieveDurationUs()
                try {
                    duration.get(3, TimeUnit.SECONDS).takeIf { it > 0 }?.div(1_000L)
                } catch (_: TimeoutException) {
                    duration.cancel(true)
                    null
                } catch (_: ExecutionException) {
                    null
                }
            }
    }
}

internal fun isUnexpectedlyShortStream(expectedMs: Long?, actualMs: Long?): Boolean =
    expectedMs != null && actualMs != null && actualMs > 0L &&
        actualMs < expectedMs * 0.75 && expectedMs - actualMs >= 15_000L
