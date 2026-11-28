package com.lladlam.melox.core.provider.qqmusic

import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject

/** Refreshes only the session carried by the request. Never logs credentials. */
internal class QQMusicCredentialInterceptor(
    private val refreshClient: OkHttpClient,
    private val currentSession: () -> QQMusicSession,
    private val saveSession: (QQMusicSession, QQMusicSession) -> Boolean,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val cookie = original.header("Cookie").orEmpty()
        val session = QQMusicSessionStore.parse(cookie)
        val body = original.body
        if (!original.url.host.endsWith(".qq.com") || !session.isLoggedIn ||
            body?.isOneShot() == true || body?.isDuplex() == true
        ) return chain.proceed(original)
        val response = chain.proceed(original)
        if (!isRejected(response)) return response
        val refreshed = synchronized(refreshLock) {
            val latest = currentSession()
            when {
                latest.uin != session.uin || !latest.isLoggedIn -> null
                latest.musicKey != session.musicKey -> latest
                lastFailure[session.cookie]?.let { System.nanoTime() - it < TimeUnit.SECONDS.toNanos(60) } == true -> null
                else -> try {
                    val next = refreshQQCredential(session, refreshClient)
                    if (saveSession(session, next)) {
                        lastFailure.remove(session.cookie)
                        Log.i(TAG, "Credential refresh succeeded")
                        next
                    } else {
                        Log.i(TAG, "Credential refresh discarded after account changed")
                        null
                    }
                } catch (_: IOException) {
                    lastFailure.clear()
                    lastFailure[session.cookie] = System.nanoTime()
                    Log.w(TAG, "Credential refresh failed; saved session retained")
                    null
                }
            }
        } ?: return response
        val retry = rebuildRequest(original, session, refreshed)
        response.close()
        Log.i(TAG, "Retrying authenticated request")
        return chain.proceed(retry)
    }

    private fun isRejected(response: Response): Boolean {
        if (response.code == 401) return true
        if (!response.isSuccessful) return false
        val root = runCatching { JSONObject(response.peekBody(1_048_576).string()) }.getOrNull() ?: return false
        val codes = buildList {
            add(root.optInt("code", 0))
            root.keys().forEach { key -> root.optJSONObject(key)?.let { add(it.optInt("code", 0)) } }
        }
        return codes.any { it == 1000 || it == 2001 }
    }

    private fun rebuildRequest(request: Request, old: QQMusicSession, next: QQMusicSession): Request {
        val oldGtk = qqMusicHash33(old.musicKey, 5381).toString()
        val newGtk = qqMusicHash33(next.musicKey, 5381).toString()
        val url = request.url.newBuilder()
        for (key in listOf("g_tk", "g_tk_new_20200303")) {
            if (request.url.queryParameter(key) == oldGtk) url.setQueryParameter(key, newGtk)
        }
        val builder = request.newBuilder().url(url.build()).header("Cookie", next.cookie)
        val body = request.body
        if (body != null && !body.isOneShot() && !body.isDuplex()) {
            val buffer = Buffer()
            body.writeTo(buffer)
            val raw = buffer.readUtf8()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (json != null) {
                fun update(value: JSONObject) {
                    value.keys().asSequence().toList().forEach { key ->
                        val item = value.opt(key)
                        when {
                            item is JSONObject -> update(item)
                            item == old.musicKey -> value.put(key, next.musicKey)
                            key in listOf("g_tk", "g_tk_new_20200303") && item.toString() == oldGtk ->
                                value.put(key, newGtk.toLong())
                        }
                    }
                }
                update(json)
                builder.method(request.method, json.toString().toRequestBody(body.contentType()))
            }
        }
        return builder.build()
    }

    private companion object {
        const val TAG = "MeloXQQAuth"
        val refreshLock = Any()
        val lastFailure = mutableMapOf<String, Long>()
    }
}

internal fun refreshQQCredential(session: QQMusicSession, client: OkHttpClient): QQMusicSession {
    val cookies = session.cookie.split(';').mapNotNull {
        val split = it.trim().split('=', limit = 2)
        if (split.size == 2) split[0] to split[1] else null
    }.toMap().toMutableMap()
    val loginType = cookies["tmeLoginType"]?.toIntOrNull()
        ?: cookies["login_type"]?.toIntOrNull()
        ?: if (session.musicKey.startsWith("W_X")) 1 else 2
    val param = JSONObject()
        .put("openid", cookies[if (loginType == 1) "wxopenid" else "psrf_qqopenid"] ?: cookies["psrf_qqopenid"].orEmpty())
        .put("refresh_token", cookies[if (loginType == 1) "wxrefresh_token" else "psrf_qqrefresh_token"] ?: cookies["psrf_qqrefresh_token"].orEmpty())
        .put("musickey", session.musicKey)
        .put("refresh_key", cookies["qm_refresh_key"].orEmpty())
        .put("loginMode", 2)
    if (loginType == 1) {
        param.put("str_musicid", session.uin).put("unionid", cookies["psrf_qqunionid"].orEmpty())
    } else {
        param.put("musicid", session.uin.toLongOrNull() ?: 0L)
            .put("access_token", cookies["psrf_qqaccess_token"].orEmpty())
            .put("expired_in", cookies["psrf_access_token_expiresAt"]?.toLongOrNull() ?: 0L)
    }
    val payload = JSONObject()
        .put("comm", JSONObject().put("ct", 24).put("cv", 0)
            .put("uin", session.uin).put("authst", session.musicKey).put("tmeLoginType", loginType))
        .put("request", JSONObject().put("module", "music.login.LoginServer")
            .put("method", "Login").put("param", param))
    val request = Request.Builder().url("https://u.y.qq.com/cgi-bin/musicu.fcg")
        .header("Cookie", session.cookie).header("Referer", "https://y.qq.com/")
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36")
        .header(com.lladlam.melox.core.network.RetryInterceptor.NO_RETRY_HEADER, "true")
        .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
    val data = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("QQ credential refresh HTTP failure")
        val root = runCatching { JSONObject(response.body.string()) }
            .getOrElse { throw IOException("Invalid QQ refresh response") }
        val result = root.optJSONObject("request")
        if (root.optInt("code", -1) != 0 || result?.optInt("code", -1) != 0) {
            throw IOException("QQ credential refresh rejected: outer=${root.optInt("code", -1)}, inner=${result?.optInt("code", -1)}")
        }
        result.optJSONObject("data") ?: throw IOException("Missing QQ refresh data")
    }
    val key = data.optString("musickey")
    if (key.isBlank() || key.any { it == ';' || it == '\r' || it == '\n' }) {
        throw IOException("Invalid refreshed QQ key")
    }
    val returnedId = data.optString("str_musicid").ifBlank { data.optString("musicid") }
        .removePrefix("o").trimStart('0')
    if (returnedId.isNotBlank() && returnedId != session.uin.trimStart('0')) throw IOException("QQ account changed")
    listOf("qm_keyst", "qqmusic_key", "music_key").forEach { cookies[it] = key }
    val fields = mapOf(
        "refresh_token" to if (loginType == 1) "wxrefresh_token" else "psrf_qqrefresh_token",
        "openid" to if (loginType == 1) "wxopenid" else "psrf_qqopenid",
        "access_token" to "psrf_qqaccess_token", "unionid" to "psrf_qqunionid",
        "refresh_key" to "qm_refresh_key", "expired_at" to "psrf_access_token_expiresAt",
        "encryptUin" to "euin",
    )
    fields.forEach { (field, cookieName) ->
        data.optString(field).takeIf { it.isNotBlank() && it != "null" && it.none { c -> c == ';' || c == '\r' || c == '\n' } }
            ?.let { cookies[cookieName] = it }
    }
    return QQMusicSessionStore.parse(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
}
