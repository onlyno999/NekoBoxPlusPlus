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
 * Load balancing for NekoBox++: every country section of every
 * group can be marked "auto" independently — any number of
 * (group, country) pools can be armed at once and they do not
 * affect each other. An armed pool keeps its members speed-tested
 * (rounds every 10 minutes, region sweeps of its group every 30).
 * While the main connection rides a member of an armed pool, the
 * pool keeps it on the fastest member: rounds steer within the
 * pool, and a 30-second watchdog swaps instantly from the recorded
 * results when the node in use dies twice in a row. Route-rule
 * load balancing is separate: those pools live in the generated
 * config as urltest outbounds and never touch this state.
 */
object GroupBalancer {

    private const val TAG = "GrpLB"
    private const val PREFS = "nbpp_lb"
    private const val KEY_SET = "lb_armed_set"
    private const val KEY_LOCK = "lb_lock"
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

    private fun keyOf(groupId: Long, countryCode: String) =
        "$groupId|${countryCode.uppercase()}"

    /**
     * All armed (group, country) pools. Migrates the legacy
     * single-pair keys (pre-v1.11) on first read.
     */
    fun armedPairs(context: Context): Set<Pair<Long, String>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet(KEY_SET, null)
        if (raw == null) {
            val legacyCc = prefs.getString(KEY_COUNTRY, null)
                ?.takeIf { it.isNotBlank() }
            val legacyGid = prefs.getLong(KEY_GROUP, -1L).takeIf { it >= 0 }
            if (legacyCc != null && legacyGid != null) {
                return setOf(legacyGid to legacyCc.uppercase())
            }
            return emptySet()
        }
        return raw.mapNotNull { entry ->
            val parts = entry.split("|")
            val gid = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val cc = parts.getOrNull(1)?.takeIf { it.length == 2 }
                ?: return@mapNotNull null
            gid to cc
        }.toSet()
    }

    fun isArmed(context: Context, groupId: Long, countryCode: String): Boolean =
        armedPairs(context).any {
            it.first == groupId && it.second == countryCode.uppercase()
        }

    /** Toggle one pool. Other pools are never affected. */
    fun setArmed(context: Context, groupId: Long, countryCode: String, on: Boolean) {
        val pairs = armedPairs(context).toMutableSet()
        val pair = groupId to countryCode.uppercase()
        if (on) pairs.add(pair) else pairs.remove(pair)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putStringSet(KEY_SET, pairs.map { keyOf(it.first, it.second) }.toSet())
            remove(KEY_COUNTRY)
            remove(KEY_GROUP)
            if (!on && lockedPair(context) == pair) remove(KEY_LOCK)
        }.apply()
        if (pairs.isEmpty()) {
            stopLoops()
        } else if (on) {
            ensureLoop(context)
            scope.launch {
                runRegionSweep()
                runSpeedRoundFor(groupId, countryCode.uppercase())
            }
        }
    }

    private fun stopLoops() {
        speedJob?.cancel(); speedJob = null
        regionJob?.cancel(); regionJob = null
        watchdogJob?.cancel(); watchdogJob = null
        watchdogFails = 0
    }

    /**
     * The locked pool is the general line: traffic that no route
     * rule claims rides it. At most one pool is locked at a time;
     * locking another section moves the lock. Locking also arms
     * the pool (the general line must be maintained).
     */
    fun lockedPair(context: Context): Pair<Long, String>? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOCK, null) ?: return null
        val parts = raw.split("|")
        val gid = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val cc = parts.getOrNull(1)?.takeIf { it.length == 2 } ?: return null
        return gid to cc
    }

    fun setLocked(context: Context, groupId: Long, countryCode: String, on: Boolean) {
        val pair = groupId to countryCode.uppercase()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (on) {
            prefs.edit().putString(KEY_LOCK, keyOf(pair.first, pair.second)).apply()
            if (!isArmed(context, groupId, countryCode)) {
                setArmed(context, groupId, countryCode, true)
            } else {
                scope.launch { runSpeedRoundFor(pair.first, pair.second) }
            }
        } else if (lockedPair(context) == pair) {
            prefs.edit().remove(KEY_LOCK).apply()
        }
    }

    /** Start the periodic loops if any pool is armed. */
    fun ensureLoop(context: Context) {
        if (armedPairs(context).isEmpty()) return
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
        if (armedPairs(app).isEmpty()) return
        if (roundRunning.get()) return
        val selected = withContext(Dispatchers.IO) {
            SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
        } ?: return
        if (testPing(selected)) {
            watchdogFails = 0
        } else {
            watchdogFails++
            Log.i(TAG, "watchdog: in-use node failed ($watchdogFails)")
            if (watchdogFails >= 2) {
                watchdogFails = 0
                // Swap FIRST, from the recorded results, like a native
                // urltest group does — but only inside the dead node's
                // own pool, and only when that pool is armed.
                val cc = GeoStore.countryFor(app, selected.requireBean())
                if (cc != null && isArmed(app, selected.groupId, cc)) {
                    failOverFromRecord(selected.groupId, cc, selected.id)
                    scope.launch { runSpeedRoundFor(selected.groupId, cc) }
                } else {
                    // The dead node belongs to no armed pool: the
                    // general line takes over if one is locked.
                    lockedPair(app)?.let { (gid, lcc) ->
                        failOverFromRecord(gid, lcc, selected.id)
                        scope.launch { runSpeedRoundFor(gid, lcc) }
                    }
                }
            }
        }
    }

    /**
     * Instant failover from the recorded test history: pick the
     * fastest other member of the active country whose last recorded
     * test passed, and switch to it right away. No testing happens
     * on this path, so the swap itself takes no time.
     */
    private suspend fun failOverFromRecord(groupId: Long, cc: String, deadId: Long) {
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
            for (groupId in armedPairs(app).map { it.first }.distinct()) {
                sweepGroup(groupId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "region sweep failed", e)
        } finally {
            sweepRunning.set(false)
        }
    }

    private suspend fun sweepGroup(groupId: Long) {
            val all = withContext(Dispatchers.IO) {
                SagerDatabase.proxyDao.getByGroup(groupId)
            }
            if (all.isEmpty()) return
            val sweepQueue = ConcurrentLinkedQueue(all)
            val sweepers = mutableListOf<Job>()
            repeat(32) {
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
    }

    /** Race every armed pool in turn. */
    suspend fun runSpeedRound() {
        for ((gid, cc) in armedPairs(app)) {
            runSpeedRoundFor(gid, cc)
        }
    }

    /**
     * Speed-test one pool's members and refresh the records. The
     * main selection only moves when it already rides this pool —
     * arming a pool never yanks a connection that is elsewhere.
     */
    suspend fun runSpeedRoundFor(groupId: Long, cc: String) {
        if (!roundRunning.compareAndSet(false, true)) return
        val doneSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
        roundDone = doneSignal
        try {
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
            val current = all.firstOrNull { it.id == DataStore.selectedProxy }
            val ridingThisPool = current != null &&
                GeoStore.countryFor(app, current.requireBean()) == cc
            // The locked pool is the general line: its rounds may
            // always steer the main connection into the pool.
            val steering = ridingThisPool || lockedPair(app) == (groupId to cc)
            if (DataStore.selectedProxy == best.id) {
                Log.i(TAG, "round for $cc: staying on ${best.requireBean().displayName()} (${best.ping}ms)")
            } else if (steering) {
                val previous = DataStore.selectedProxy
                DataStore.selectedProxy = best.id
                ProfileManager.postUpdate(previous)
                ProfileManager.postUpdate(best.id)
                Log.i(TAG, "auto-connect -> ${best.requireBean().displayName()} (${best.ping}ms)")
                if (DataStore.serviceState.canStop) {
                    SagerNet.reloadService()
                }
            } else {
                Log.i(TAG, "round for $cc: records refreshed (not riding this pool)")
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
        lockedPair(app)?.let { (gid, lcc) ->
            val best = bestKnown(app, gid, lcc) ?: return@let
            if (DataStore.selectedProxy != best.id) {
                val previous = DataStore.selectedProxy
                DataStore.selectedProxy = best.id
                ProfileManager.postUpdate(previous)
                ProfileManager.postUpdate(best.id)
                Log.i(TAG, "connect pick (locked) -> ${best.requireBean().displayName()} (${best.ping}ms)")
            }
            scope.launch { runSpeedRoundFor(gid, lcc) }
            return
        }
        val selected = withContext(Dispatchers.IO) {
            SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
        } ?: return
        val cc = GeoStore.countryFor(app, selected.requireBean()) ?: return
        if (!isArmed(app, selected.groupId, cc)) return
        val best = bestKnown(app, selected.groupId, cc, preferId = selected.id) ?: return
        if (DataStore.selectedProxy != best.id) {
            val previous = DataStore.selectedProxy
            DataStore.selectedProxy = best.id
            ProfileManager.postUpdate(previous)
            ProfileManager.postUpdate(best.id)
            Log.i(TAG, "connect pick -> ${best.requireBean().displayName()} (${best.ping}ms)")
        }
        scope.launch { runSpeedRoundFor(selected.groupId, cc) }
    }

    /** Best known member of a pool, from the recorded test results. */
    fun bestKnown(
        context: Context, groupId: Long, countryCode: String, preferId: Long = -1,
    ): ProxyEntity? {
        val inCountry = SagerDatabase.proxyDao.getByGroup(groupId).filter {
            GeoStore.countryFor(context, it.requireBean()) ==
                countryCode.uppercase()
        }
        if (inCountry.isEmpty()) return null
        inCountry.firstOrNull { it.id == preferId }?.let { return it }
        return inCountry.filter { it.status == 1 && it.ping > 0 }
            .minByOrNull { it.ping }
            ?: inCountry.filter { it.ping > 0 }.minByOrNull { it.ping }
            ?: inCountry.firstOrNull()
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
