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
 * Load balancing for NekoBox++ (front page). Rule since 1.2.1:
 * exactly ONE (group, country) pool can be lit ("自动中") across
 * the whole front page — lighting another one replaces it — and
 * the lit pool IS the general line: arming it steers the main
 * connection into the pool right away, its speed rounds (every
 * 10 minutes, region sweeps of its group every 30) keep the main
 * connection on the pool's fastest member, and a 30-second
 * watchdog fails over into the pool when the node in use dies
 * twice in a row. Route-rule load balancing is separate: those
 * pools live in the generated config as urltest outbounds and
 * never touch this state.
 */
object GroupBalancer {

    private const val TAG = "GrpLB"
    private const val PREFS = "nbpp_lb"
    private const val KEY_ARMED = "lb_armed"
    private const val KEY_SET = "lb_armed_set"
    private const val KEY_LOCK = "lb_lock"
    private const val KEY_COUNTRY = "lb_country"
    private const val KEY_GROUP = "lb_group"
    private const val SPEED_INTERVAL_MS = 5 * 60_000L
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

    private fun parsePair(raw: String?): Pair<Long, String>? {
        if (raw == null) return null
        val parts = raw.split("|")
        val gid = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val cc = parts.getOrNull(1)?.takeIf { it.length == 2 } ?: return null
        return gid to cc
    }

    /**
     * The one lit pool, or null. Migrates the pre-1.2.1 state on
     * first read: a locked pool wins, then the first pool of the
     * old multi-arm set, then the legacy single-pair keys.
     */
    fun armedPair(context: Context): Pair<Long, String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        parsePair(prefs.getString(KEY_ARMED, null))?.let { return it }
        val migrated = parsePair(prefs.getString(KEY_LOCK, null))
            ?: prefs.getStringSet(KEY_SET, null)?.let { set ->
                set.mapNotNull { parsePair(it) }
                    .sortedBy { keyOf(it.first, it.second) }
                    .firstOrNull()
            }
            ?: run {
                val cc = prefs.getString(KEY_COUNTRY, null)?.takeIf { it.isNotBlank() }
                val gid = prefs.getLong(KEY_GROUP, -1L).takeIf { it >= 0 }
                if (cc != null && gid != null) gid to cc.uppercase() else null
            }
        if (migrated != null) {
            prefs.edit()
                .putString(KEY_ARMED, keyOf(migrated.first, migrated.second))
                .remove(KEY_SET).remove(KEY_LOCK)
                .remove(KEY_COUNTRY).remove(KEY_GROUP)
                .apply()
        }
        return migrated
    }

    fun isArmed(context: Context, groupId: Long, countryCode: String): Boolean =
        armedPair(context) == (groupId to countryCode.uppercase())

    /**
     * Light or unlight a pool. Lighting one pool replaces any
     * other lit pool anywhere (front-page rule) and immediately
     * runs a round that steers the main connection into it.
     */
    fun setArmed(context: Context, groupId: Long, countryCode: String, on: Boolean) {
        val pair = groupId to countryCode.uppercase()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (on) {
            prefs.edit().putString(KEY_ARMED, keyOf(pair.first, pair.second)).apply()
            ensureLoop(context)
            scope.launch {
                runRegionSweep()
                runSpeedRoundFor(pair.first, pair.second)
            }
        } else {
            if (armedPair(context) == pair) {
                prefs.edit().remove(KEY_ARMED).apply()
            }
            if (armedPair(context) == null) stopLoops()
        }
    }

    private fun stopLoops() {
        speedJob?.cancel(); speedJob = null
        regionJob?.cancel(); regionJob = null
        watchdogJob?.cancel(); watchdogJob = null
        watchdogFails = 0
    }

    /** Start the periodic loops if a pool is lit. */
    fun ensureLoop(context: Context) {
        if (armedPair(context) == null) return
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
                    try {
                        watchdogTick()
                    } catch (e: Exception) {
                        Log.w(TAG, "watchdog tick failed", e)
                    }
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
        val armed = armedPair(app) ?: return
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
                // urltest group does. The lit pool is the general
                // line, so it takes over no matter where the dead
                // node lived.
                failOverFromRecord(armed.first, armed.second, selected.id)
                scope.launch { runSpeedRoundFor(armed.first, armed.second) }
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
                runCatching {
                    GeoStore.countryFor(app, it.requireBean())
                }.getOrNull() == cc
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
            armedPair(app)?.let { sweepGroup(it.first) }
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
                        val bean = runCatching { profile.requireBean() }
                            .getOrNull() ?: continue
                        val stored = GeoStore.countryFor(app, bean)
                        val actual = runCatching {
                            GeoTest().doFetchCountry(profile)
                        }.getOrNull()?.uppercase()?.takeIf { it.length == 2 }
                        if (actual != null && actual != stored) {
                            GeoStore.record(app, bean, actual)
                        }
                    }
                })
            }
            sweepers.forEach { it.join() }
    }

    /** Race the one lit pool. */
    suspend fun runSpeedRound() {
        armedPair(app)?.let { runSpeedRoundFor(it.first, it.second) }
    }

    /**
     * Speed-test one pool's members and refresh the records. When
     * the pool is the lit one (the general line), the round steers
     * the main connection to the pool's fastest member; rounds for
     * any other pool only refresh records.
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

            // Switch right after testing, to the fastest known:
            // this round's winner, or a recorded available best if
            // it is faster (a flaky parallel pass must not bury a
            // node that measured well minutes ago).
            val tested = eligible.minByOrNull { it.ping }
            val recorded = bestKnown(app, groupId, cc)?.takeIf { it.ping > 0 }
            val best = listOfNotNull(tested, recorded).minByOrNull { it.ping }
            if (best == null) {
                Log.i(TAG, "round for $cc: nothing reachable")
                return
            }
            // The lit pool is the general line: its rounds always
            // steer the main connection into the pool, wherever the
            // current selection happens to sit.
            val steering = armedPair(app) == (groupId to cc)
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
        // The lit pool is the general line: connect starts on its
        // best known member, wherever the selection sat before.
        val armed = armedPair(app) ?: return
        // bestKnown runs a whole-group DB query; callers may sit
        // on the main thread (connect tap), so resolve off it.
        val best = withContext(Dispatchers.IO) {
            bestKnown(app, armed.first, armed.second)
        } ?: return
        if (DataStore.selectedProxy != best.id) {
            val previous = DataStore.selectedProxy
            DataStore.selectedProxy = best.id
            ProfileManager.postUpdate(previous)
            ProfileManager.postUpdate(best.id)
            Log.i(TAG, "connect pick -> ${best.requireBean().displayName()} (${best.ping}ms)")
        }
        scope.launch { runSpeedRoundFor(armed.first, armed.second) }
    }

    /** Best known member of a pool, from the recorded test results. */
    fun bestKnown(
        context: Context, groupId: Long, countryCode: String, preferId: Long = -1,
    ): ProxyEntity? {
        val inCountry = SagerDatabase.proxyDao.getByGroup(groupId).filter {
            runCatching {
                GeoStore.countryFor(context, it.requireBean())
            }.getOrNull() == countryCode.uppercase()
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
