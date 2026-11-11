package io.nekohasekai.sagernet.geo

import android.content.Context
import android.util.Log
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.GeoTest
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Load balancing for NekoBox++: in any subscription group, one
 * country section can be marked "auto" (one armed group+country at
 * a time). A round URL-tests only that country's nodes, selects
 * the reachable one with the lowest latency, and (when the service
 * is running) switches the connection to it. Speed rounds repeat
 * every 10 minutes, whole-group region sweeps every 30, and a
 * 30-second watchdog swaps instantly from the recorded results
 * when the node in use dies twice in a row. The loop lives in the
 * app process; it resumes on the next app start while armed.
 */
object GroupBalancer {

    private const val TAG = "GrpLB"
    private const val PREFS = "nbpp_lb"
    private const val KEY_COUNTRY = "lb_country"
    private const val KEY_GROUP = "lb_group"
    private const val SPEED_INTERVAL_MS = 10 * 60_000L
    private const val REGION_INTERVAL_MS = 30 * 60_000L
    private const val WATCHDOG_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val roundRunning = AtomicBoolean(false)
    private val sweepRunning = AtomicBoolean(false)

    @Volatile
    private var roundDone: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    @Volatile
    private var speedJob: Job? = null

    @Volatile
    private var regionJob: Job? = null

    @Volatile
    private var watchdogJob: Job? = null

    @Volatile
    private var watchdogFails = 0

    /** The armed country, but only for the group it was armed in. */
    fun activeCountry(context: Context, groupId: Long): String? {
        if (armedGroupId(context) != groupId) return null
        return armedCountry(context)
    }

    fun armedCountry(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COUNTRY, null)?.takeIf { it.isNotBlank() }

    fun armedGroupId(context: Context): Long? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_GROUP, -1L).takeIf { it >= 0 }

    fun setActive(context: Context, groupId: Long, countryCode: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (countryCode.isNullOrBlank()) {
                remove(KEY_COUNTRY)
                remove(KEY_GROUP)
            } else {
                putString(KEY_COUNTRY, countryCode.uppercase())
                putLong(KEY_GROUP, groupId)
            }
        }.apply()
        if (countryCode.isNullOrBlank()) {
            stopLoops()
        } else {
            ensureLoop(context)
            scope.launch { runRound() }
        }
    }

    private fun stopLoops() {
        speedJob?.cancel(); speedJob = null
        regionJob?.cancel(); regionJob = null
        watchdogJob?.cancel(); watchdogJob = null
        watchdogFails = 0
    }

    /** Start the periodic loops if a country is active. */
    fun ensureLoop(context: Context) {
        if (armedCountry(context) == null) return
        if (speedJob?.isActive != true) {
            speedJob = scope.launch {
                while (isActive) {
                    delay(SPEED_INTERVAL_MS)
                    runSpeedRound()
                }
            }
        }
        if (regionJob?.isActive != true) {
            regionJob = scope.launch {
                while (isActive) {
                    delay(REGION_INTERVAL_MS)
                    runRegionSweep()
                }
            }
        }
        if (watchdogJob?.isActive != true) {
            watchdogJob = scope.launch {
                while (isActive) {
                    delay(WATCHDOG_MS)
                    watchdogTick()
                }
            }
        }
    }

    /**
     * Watchdog: every 30 s, touch the speed-test address through the
     * node currently in use (one light test, nothing else). Two
     * failures in a row mean it died mid-session: immediately race
     * the active country again and switch to its fastest survivor.
     */
    private suspend fun watchdogTick() {
        if (!DataStore.serviceState.connected) return
        if (armedCountry(app) == null) return
        if (roundRunning.get()) return
        val groupId = armedGroupId(app) ?: return
        val selected = withContext(Dispatchers.IO) {
            SagerDatabase.proxyDao.getByGroup(groupId)
                .firstOrNull { it.id == DataStore.selectedProxy }
        } ?: return
        if (testPing(selected)) {
            watchdogFails = 0
        } else {
            watchdogFails++
            Log.i(TAG, "watchdog: in-use node failed ($watchdogFails)")
            if (watchdogFails >= 2) {
                watchdogFails = 0
                // Swap FIRST, from the recorded results, like a native
                // urltest group does: the fastest other node the
                // background rounds already know is reachable. The
                // fresh race afterwards only refreshes the record.
                failOverFromRecord(groupId, selected.id)
                scope.launch { runSpeedRound() }
            }
        }
    }

    /**
     * Instant failover from the recorded test history: pick the
     * fastest other member of the active country whose last recorded
     * test passed, and switch to it right away. No testing happens
     * on this path, so the swap itself takes no time.
     */
    private suspend fun failOverFromRecord(groupId: Long, deadId: Long) {
        val cc = armedCountry(app) ?: return
        val candidates = withContext(Dispatchers.IO) {
            SagerDatabase.proxyDao.getByGroup(groupId)
        }.filter {
            it.id != deadId && it.status == 1 && it.ping > 0 &&
                GeoStore.countryFor(app, it.requireBean()) == cc
        }
        val best = candidates.minByOrNull { it.ping } ?: run {
            Log.i(TAG, "watchdog failover: no recorded alternative in $cc")
            return
        }
        val previous = DataStore.selectedProxy
        DataStore.selectedProxy = best.id
        ProfileManager.postUpdate(previous)
        ProfileManager.postUpdate(best.id)
        Log.i(TAG, "watchdog failover -> ${best.requireBean().displayName()} (${best.ping}ms)")
        if (DataStore.serviceState.canStop) {
            SagerNet.reloadService()
        }
    }


    /** A full pass: whole-group region sweep, then the speed race. */
    suspend fun runRound() {
        runRegionSweep()
        runSpeedRound()
    }

    /**
     * Re-check the regions of the WHOLE group, not just the active
     * country's section. Exits move: a node filed under one country
     * may sit somewhere else by now, and one filed elsewhere may
     * have moved in. Every node whose exit country is known lands
     * in its real section.
     */
    suspend fun runRegionSweep() {
        if (!sweepRunning.compareAndSet(false, true)) return
        try {
            val groupId = armedGroupId(app) ?: return
            val all = withContext(Dispatchers.IO) {
                SagerDatabase.proxyDao.getByGroup(groupId)
            }
            if (all.isEmpty()) return
            val sweepQueue = ConcurrentLinkedQueue(all)
            val sweepers = mutableListOf<Job>()
            repeat(8) {
                sweepers.add(scope.launch {
                    while (true) {
                        val profile = sweepQueue.poll() ?: break
                        val stored = GeoStore.countryFor(app, profile.requireBean())
                        val actual = runCatching {
                            GeoTest().doFetchCountry(profile)
                        }.getOrNull()?.uppercase()?.takeIf { it.length == 2 }
                        if (actual != null && actual != stored) {
                            GeoStore.record(app, profile.requireBean(), actual)
                        }
                    }
                })
            }
            sweepers.forEach { it.join() }
        } catch (e: Exception) {
            Log.w(TAG, "region sweep failed", e)
        } finally {
            sweepRunning.set(false)
        }
    }

    /** Speed-test the active country's members and pick the fastest. */
    suspend fun runSpeedRound() {
        if (!roundRunning.compareAndSet(false, true)) return
        val doneSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
        roundDone = doneSignal
        try {
            val cc = armedCountry(app) ?: return
            val groupId = armedGroupId(app) ?: return
            val all = withContext(Dispatchers.IO) {
                SagerDatabase.proxyDao.getByGroup(groupId)
            }
            val members = all.filter {
                GeoStore.countryFor(app, it.requireBean()) == cc
            }
            if (members.isEmpty()) {
                Log.i(TAG, "round for $cc: no members")
                return
            }
            Log.i(TAG, "round for $cc over ${members.size} profiles")

            val queue = ConcurrentLinkedQueue(members)
            val eligible = ConcurrentLinkedQueue<ProxyEntity>()
            val workers = mutableListOf<Job>()
            val concurrent = DataStore.connectionTestConcurrent.coerceIn(1, 16)
            repeat(concurrent) {
                workers.add(scope.launch {
                    while (true) {
                        val profile = queue.poll() ?: break
                        if (testPing(profile)) {
                            eligible.add(profile)
                        }
                    }
                })
            }
            workers.forEach { it.join() }

            val best = eligible.minByOrNull { it.ping }
            if (best == null) {
                Log.i(TAG, "round for $cc: nothing reachable")
                return
            }
            if (DataStore.selectedProxy != best.id) {
                val previous = DataStore.selectedProxy
                DataStore.selectedProxy = best.id
                ProfileManager.postUpdate(previous)
                ProfileManager.postUpdate(best.id)
                Log.i(TAG, "auto-connect -> ${best.requireBean().displayName()} (${best.ping}ms)")
                if (DataStore.serviceState.canStop) {
                    SagerNet.reloadService()
                }
            } else {
                Log.i(TAG, "round for $cc: staying on ${best.requireBean().displayName()} (${best.ping}ms)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "round failed", e)
        } finally {
            roundRunning.set(false)
            doneSignal.complete(Unit)
        }
    }

    /**
     * Connect-time pick: do NOT wait for a fresh round. The round
     * that started when the user switched the country to auto has
     * already measured everyone, so connect immediately to the
     * best known node of the active country, then let a background
     * round refresh regions and speeds (it switches over if it
     * finds something better).
     */
    suspend fun pickForConnect() {
        val cc = armedCountry(app) ?: return
        val groupId = armedGroupId(app) ?: return
        val profiles = withContext(Dispatchers.IO) {
            SagerDatabase.proxyDao.getByGroup(groupId)
        }
        val inCountry = profiles.filter {
            GeoStore.countryFor(app, it.requireBean()) == cc
        }
        val current = inCountry.firstOrNull { it.id == DataStore.selectedProxy }
        val best = when {
            current != null -> current
            else -> inCountry.filter { it.status == 1 && it.ping > 0 }
                .minByOrNull { it.ping }
                ?: inCountry.filter { it.ping > 0 }.minByOrNull { it.ping }
                ?: inCountry.firstOrNull()
        }
        if (best != null && DataStore.selectedProxy != best.id) {
            val previous = DataStore.selectedProxy
            DataStore.selectedProxy = best.id
            ProfileManager.postUpdate(previous)
            ProfileManager.postUpdate(best.id)
            Log.i(TAG, "connect pick -> ${best.requireBean().displayName()} (${best.ping}ms)")
        }
        scope.launch { runSpeedRound() }
    }

    /** Speed-test one candidate of the active country. */
    private suspend fun testPing(profile: ProxyEntity): Boolean {
        return try {
            val result = UrlTest().doTest(profile)
            profile.status = 1
            profile.ping = result
            ProfileManager.updateProfile(profile)
            true
        } catch (e: Exception) {
            profile.status = 3
            profile.error = e.message
            ProfileManager.updateProfile(profile)
            false
        }
    }

}
