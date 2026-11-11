package io.nekohasekai.sagernet.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import io.nekohasekai.sagernet.ktx.app
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Minimal WebDAV client for backup upload / download (PUT / GET). */
object WebDavBackup {

    private fun client(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        // Backup traffic must not ride the app's own VPN tunnel
        // (Jianguoyun rejects the handshake when it arrives via a
        // proxy exit): route it over a physical, non-VPN network
        // whenever one is available.
        directSocketFactory()?.let { b.socketFactory(it) }
        return b.build()
    }

    private fun directSocketFactory(): SocketFactory? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n)
                caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }?.socketFactory
        } catch (e: Exception) {
            null
        }
    }

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

    /** Creates a collection; failures (already exists etc.) are ignored. */
    fun mkcol(url: String, user: String, pass: String) {
        try {
            client().newCall(request(url, user, pass).method("MKCOL", null).build())
                .execute().use { }
        } catch (e: Exception) {
            // best effort only
        }
    }
}
