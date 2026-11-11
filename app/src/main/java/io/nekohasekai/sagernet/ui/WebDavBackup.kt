package io.nekohasekai.sagernet.ui

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Minimal WebDAV client for backup upload / download (PUT / GET). */
object WebDavBackup {

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun request(url: String, user: String, pass: String): Request.Builder {
        val b = Request.Builder().url(url)
        if (user.isNotEmpty()) {
            b.header("Authorization", Credentials.basic(user, pass))
        }
        return b
    }

    fun put(url: String, user: String, pass: String, content: String) {
        val body = content.toRequestBody("application/json".toMediaType())
        client().newCall(request(url, user, pass).put(body).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP " + resp.code)
            }
    }

    fun get(url: String, user: String, pass: String): String {
        client().newCall(request(url, user, pass).get().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP " + resp.code)
                return resp.body?.string() ?: throw IOException("empty body")
            }
    }
}
