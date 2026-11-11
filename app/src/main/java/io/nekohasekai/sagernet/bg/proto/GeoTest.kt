package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.ProxyEntity

/**
 * Fetches the exit country through a single profile, so the answer
 * describes that profile's exit location. NekoBox999 addition.
 *
 * The only source is Cloudflare's trace endpoint (plain text with
 * a `loc=XX` line, tiny and fast, happy with heavy concurrency).
 */
class GeoTest {

    val link = "https://cp.cloudflare.com/cdn-cgi/trace"
    private val timeout = 8000

    suspend fun doFetch(profile: ProxyEntity): String {
        return TestInstance(profile, link, timeout).doFetch()
    }

    /** Two-letter exit country code for the profile, or null. */
    suspend fun doFetchCountry(profile: ProxyEntity): String? {
        return runCatching {
            val body = doFetch(profile)
            for (line in body.lineSequence()) {
                if (line.startsWith("loc=")) {
                    return@runCatching line.substring(4).trim()
                        .takeIf { it.length == 2 }
                }
            }
            null
        }.getOrNull()
    }

}
