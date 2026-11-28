package com.lladlam.melox.core.network

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseSongSearchPaginationTest {
    @Test
    fun searchesWithoutAccountCredentialsAndUsesThePublicPageSize() = runBlocking {
        val offsets = mutableListOf<Long>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("/eapi/batch", request.url.encodedPath)
            assertNull(request.header("Cookie"))
            val form = request.body as FormBody
            val encrypted = form.value(0).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
            val payload = cipher.doFinal(encrypted).decodeToString()
            assertTrue(payload.startsWith("/api/search/song/list/page-36cd479b6b5-"))
            val query = JSONObject(payload.substringAfter("-36cd479b6b5-").substringBeforeLast("-36cd479b6b5-"))
            assertFalse(query.has("header"))
            assertEquals(20, query.getInt("limit"))
            val offset = query.getLong("offset").also(offsets::add)
            val songId = offset + 1
            val json = """{"code":200,"data":{"totalCount":21,"hasMore":${offset == 0L},"resources":[{"baseInfo":{"simpleSongData":{"id":$songId,"name":"测试歌曲","dt":249000,"ar":[{"name":"测试歌手"}],"al":{"name":"测试专辑"}}}}]}}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(json.toResponseBody()).build()
        }.build()
        val search = NeteaseSearchClient(httpClient = client, cookieProvider = { "MUSIC_U=test-session" })
        val first = search.searchSongPage("测试歌曲", page = 1, limit = 25)
        val second = search.searchSongPage("测试歌曲", page = 2, limit = 25)

        assertEquals(listOf(0L, 20L), offsets)
        assertEquals(1L, first.items.single().id)
        assertEquals(21L, second.items.single().id)
        assertEquals(249_000L, first.items.single().durationMs)
        assertEquals("测试歌手", first.items.single().artists)
        assertEquals(21L, second.total)
        assertTrue(first.hasMore)
        assertFalse(second.hasMore)
    }
}
