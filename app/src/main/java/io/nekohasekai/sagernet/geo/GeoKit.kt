package io.nekohasekai.sagernet.geo

import android.content.Context
import android.util.Log
import io.nekohasekai.sagernet.bg.proto.GeoTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Country store + prober for NekoBox++: for any subscription group,
 * every profile is asked (through itself) for https://cp.cloudflare.com/cdn-cgi/trace,
 * the resulting country code is kept here keyed by the same bean key
 * the subscription sync uses to remember the selection, and the group
 * is re-sorted so profiles sit grouped by country.
 */
object GeoStore {

    private const val PREFS = "nbpp_geo"
    private const val KEY_MAP = "geo_map"
    private const val KEY_TRY = "geo_try"
    private const val RETRY_MS = 24 * 60 * 60_000L

    @Volatile
    private var cache: MutableMap<String, String>? = null

    @Volatile
    private var tryCache: MutableMap<String, Long>? = null

    // Probe workers (up to 100), balancer sweepers and UI reads all
    // hit these maps concurrently; every access goes through this
    // one lock (stability pass 1.2.8).
    private val lock = Any()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadMap(context: Context): MutableMap<String, String> {
        synchronized(lock) {
            cache?.let { return it }
            val out = mutableMapOf<String, String>()
            val raw = prefs(context).getString(KEY_MAP, null)
            if (raw != null) {
                runCatching {
                    val json = JSONObject(raw)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        out[k] = json.getString(k)
                    }
                }
            }
            cache = out
            return out
        }
    }

    private fun loadTry(context: Context): MutableMap<String, Long> {
        synchronized(lock) {
            tryCache?.let { return it }
            val out = mutableMapOf<String, Long>()
            val raw = prefs(context).getString(KEY_TRY, null)
            if (raw != null) {
                runCatching {
                    val json = JSONObject(raw)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        out[k] = json.getLong(k)
                    }
                }
            }
            tryCache = out
            return out
        }
    }

    private fun save(context: Context) {
        synchronized(lock) {
            val mapJson = JSONObject()
            loadMap(context).forEach { (k, v) -> mapJson.put(k, v) }
            val tryJson = JSONObject()
            loadTry(context).forEach { (k, v) -> tryJson.put(k, v) }
            prefs(context).edit()
                .putString(KEY_MAP, mapJson.toString())
                .putString(KEY_TRY, tryJson.toString())
                .apply()
        }
    }

    /**
     * Drop this process's in-memory copy of the maps. The config
     * builder runs in the service process, whose copy loads once
     * and would otherwise stay frozen at whatever the shared file
     * held when that process first touched it: an LB pool whose
     * members' countries were probed later (in the UI process)
     * then resolves to zero members at config-build time and its
     * rules silently fall back to the main line.
     */
    fun invalidateCache() {
        synchronized(lock) {
            cache = null
            tryCache = null
        }
    }

    fun keyOf(bean: AbstractBean): String =
        "${bean.javaClass.name}|${bean.serverAddress}|${bean.serverPort}"

    fun countryFor(context: Context, bean: AbstractBean): String? =
        synchronized(lock) { loadMap(context)[keyOf(bean)] }

    fun attemptedRecently(context: Context, bean: AbstractBean): Boolean {
        val ts = synchronized(lock) { loadTry(context)[keyOf(bean)] } ?: return false
        return System.currentTimeMillis() - ts < RETRY_MS
    }

    fun record(context: Context, bean: AbstractBean, countryCode: String?) {
        val key = keyOf(bean)
        // Only stamp the 24h retry lock on success: a failed probe (storm
        // timeout, transient network) must stay retryable on the next round.
        if (!countryCode.isNullOrBlank()) {
            synchronized(lock) {
                loadTry(context)[key] = System.currentTimeMillis()
                loadMap(context)[key] = countryCode.uppercase()
                save(context)
            }
        }
    }

    private const val KEY_COLLAPSE = "geo_collapse"

    /** Stored expand state for a list section; null = no stored state. */
    fun sectionExpanded(context: Context, groupId: Long, key: String): Boolean? {
        val raw = prefs(context).getString(KEY_COLLAPSE, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            if (json.has("$groupId|$key")) json.getBoolean("$groupId|$key") else null
        }.getOrNull()
    }

    /**
     * The group's manually arranged country-section order (1.2.0).
     * Once the user drags sections around, this order wins over the
     * automatic size-based sort and never reshuffles on its own;
     * countries not listed yet append after it. Null = never arranged.
     */
    fun sectionOrder(context: Context, groupId: Long): List<String>? {
        val raw = prefs(context).getString("order|$groupId", null) ?: return null
        return raw.split(",").filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
    }

    fun saveSectionOrder(context: Context, groupId: Long, order: List<String>) {
        prefs(context).edit()
            .putString("order|$groupId", order.joinToString(",")).apply()
    }

    fun setSectionExpanded(context: Context, groupId: Long, key: String, expanded: Boolean) {
        val raw = prefs(context).getString(KEY_COLLAPSE, null)
        val json = runCatching { if (raw != null) JSONObject(raw) else JSONObject() }
            .getOrDefault(JSONObject())
        json.put("$groupId|$key", expanded)
        prefs(context).edit().putString(KEY_COLLAPSE, json.toString()).apply()
    }

    /** Section title for a country, e.g. "🇺🇸 美国". */
    fun countryTitle(countryCode: String): String {
        val cc = countryCode.uppercase()
        val cn = runCatching {
            java.util.Locale("", cc).getDisplayCountry(java.util.Locale.CHINESE)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != cc } ?: cc
        val flag = flag(cc)
        return if (flag.isEmpty()) cn else "$flag $cn"
    }

    /** Regional-indicator flag emoji for a 2-letter country code. */
    fun flag(countryCode: String): String {
        val cc = countryCode.uppercase()
        if (cc.length != 2) return ""
        val sb = StringBuilder()
        for (c in cc) {
            if (c !in 'A'..'Z') return ""
            sb.append(Character.toChars(0x1F1E6 + (c - 'A')))
        }
        return sb.toString()
    }
}

object GeoProbe {

    private const val TAG = "GeoProbe"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    /** Fire-and-forget geo probe of one group. */
    fun enqueue(context: Context, groupId: Long) {
        if (!running.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                probe(appContext, groupId)
            } catch (e: Exception) {
                Log.w(TAG, "probe failed", e)
            } finally {
                running.set(false)
            }
        }
    }

    suspend fun probe(context: Context, groupId: Long) = withContext(Dispatchers.IO) {
        val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
        val targets = profiles.filter { profile ->
            // A row whose bean fails to build must not kill the probe.
            val bean = runCatching { profile.requireBean() }.getOrNull()
                ?: return@filter false
            GeoStore.countryFor(context, bean) == null &&
                !GeoStore.attemptedRecently(context, bean)
        }
        probeProfiles(context, targets)
        // Sections are rendered from the country store; just reload the list.
        GroupManager.postReload(groupId)
    }

    /**
     * Probe the given profiles' exit countries: at most 16 box
     * instances at a time (each one is a full native box; the old
     * 100-way fan-out spiked memory), and the whole phase yields
     * the moment the user starts a manual connection test.
     */
    suspend fun probeProfiles(
        context: Context,
        targets: List<ProxyEntity>,
        onProbed: (suspend () -> Unit)? = null,
    ) =
        withContext(Dispatchers.IO) {
            if (targets.isEmpty()) return@withContext
            val queue = ConcurrentLinkedQueue(targets)
            val jobs = mutableListOf<kotlinx.coroutines.Job>()
            repeat(minOf(16, targets.size)) {
                jobs.add(launch {
                    while (true) {
                        if (DataStore.runningTest) break
                        val profile = queue.poll() ?: break
                        val bean = runCatching { profile.requireBean() }
                            .getOrNull() ?: continue
                        val cc = runCatching {
                            GeoTest().doFetchCountry(profile)
                        }.getOrNull()
                        GeoStore.record(context, bean, cc)
                        onProbed?.let { runCatching { it() } }
                        Log.i(TAG, "geo ${bean.displayName()} -> ${cc ?: "?"}")
                    }
                })
            }
            jobs.forEach { it.join() }
        }

}
