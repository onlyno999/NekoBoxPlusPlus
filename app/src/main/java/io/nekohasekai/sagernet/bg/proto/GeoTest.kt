package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.ProxyEntity

/**
 * Fetches the exit country through a single profile, so the answer
 * describes that profile's exit location. NekoBox999 addition.
 *
 * Primary source is Cloudflare's trace endpoint (plain text with
 * a `loc=XX` line, tiny and fast, happy with heavy concurrency).
 * Some exits cannot reach it at all, so ip.sb's geoip JSON
 * (`country_code`) is kept as a fallback (added in 1.2.4).
 */
class GeoTest {

    val link = "https://cp.cloudflare.com/cdn-cgi/trace"
    private val fallbackLink = "https://api.ip.sb/geoip"
    private val timeout = 8000

    suspend fun doFetch(profile: ProxyEntity): String {
        return TestInstance(profile, link, timeout).doFetch()
    }

    /** Two-letter exit country code for the profile, or null. */
    suspend fun doFetchCountry(profile: ProxyEntity): String? {
        runCatching {
            val body = doFetch(profile)
            for (line in body.lineSequence()) {
                if (line.startsWith("loc=")) {
                    line.substring(4).trim()
                        .takeIf { it.length == 2 }?.let { return it }
                }
            }
        }
        return runCatching {
            val body = TestInstance(profile, fallbackLink, timeout).doFetch()
            Regex("\"country_code\"\\s*:\\s*\"([A-Za-z]{2})\"").find(body)
                ?.groupValues?.get(1)?.uppercase()
        }.getOrNull()
    }

}
