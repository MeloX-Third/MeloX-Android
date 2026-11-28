package com.lladlam.melox.core.provider.kuwo

import com.lladlam.melox.core.music.model.AudioQualityTier
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.PlaybackResolution
import com.lladlam.melox.core.music.model.ProviderTrackMetadata
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KuwoPlaybackDurationTest {
    private val track = MusicTrack(
        id = MusicResourceId(MusicSource.Kuwo, "123"),
        title = "测试歌曲",
        artists = emptyList(),
        durationMs = 249_000L,
        providerMetadata = ProviderTrackMetadata.Kuwo(mid = 123L),
    )

    @Test
    fun rejectsAnElevenSecondMessageForAFourMinuteSong() = runBlocking {
        val api = KuwoApiClient(trackUrlClient()) { _, _ -> 11_311L }

        val result = api.resolvePlayback(track, AudioQualityTier.Standard)

        assertEquals(PlaybackResolution.Unavailable("酷我音乐只返回了短音频，未找到完整歌曲"), result)
    }

    @Test
    fun triesTheNextFormatWhenTheFirstUrlIsTooShort() = runBlocking {
        val api = KuwoApiClient(trackUrlClient()) { url, _ ->
            if (url.endsWith("/mp3")) 11_311L else 247_000L
        }

        val result = api.resolvePlayback(track, AudioQualityTier.Lossless)

        assertTrue(result is PlaybackResolution.Playable)
        assertEquals("https://media.example/aac", (result as PlaybackResolution.Playable).url)
    }

    @Test
    fun allowsAnActuallyShortSong() = runBlocking {
        val api = KuwoApiClient(trackUrlClient()) { _, _ -> 17_000L }
        assertTrue(api.resolvePlayback(track.copy(durationMs = 18_000L), AudioQualityTier.Standard) is PlaybackResolution.Playable)
    }

    private fun trackUrlClient(): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val format = request.url.queryParameter("format")
        val json = """{"code":200,"url":"https://media.example/$format"}"""
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(json.toResponseBody()).build()
    }.build()
}
