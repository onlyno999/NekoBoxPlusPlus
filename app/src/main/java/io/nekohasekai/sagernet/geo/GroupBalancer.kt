package io.nekohasekai.sagernet.geo

import android.content.Context
import android.util.Log
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * the lit pool IS the general line. Since 1.2.12 the upkeep is
 * the owner's standby model: rounds every 5 minutes test a
 * rotating batch of FIVE pool members and only refresh their
 * records (a healthy line is never displaced for speed), and
 * death is judged by the core itself — the sing-box fork counts
 * consecutive failed dials of real traffic on the main line,
 * the watchdog reads that number every 10 seconds through the
 * service, and at 3 in a row the line is swapped at once to the
 * best recorded standby. Arming a pool steers the main
 * connection into it from records; region sweeps of the lit
 * group run every 30 minutes. Route-rule load balancing is
 * separate: those pools live in the generated config as urltest
 * outbounds and never touch this state.
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
    private const val WATCHDOG_MS = 10_000L
    private const val BATCH_SIZE = 5
    private const val DEATH_THRESHOLD = 3L

    /** Tag of the general line in the generated config
     *  (ConfigBuilder.TAG_PROXY). */
    private const val MAIN_LINE_TAG = "proxy"

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

    /**
     * How the watchdog reaches the running service (its core
     * holds the dial-health counters). Set by MainActivity; null
     * whenever no activity is bound, in which case the watchdog
     * simply skips — reading a number is all it ever does.
     */
    @Volatile
    var serviceProvider: (() -> ISagerNetService?)? = null

    /** Selection the core's dial-health counter currently
     *  belongs to; a change restarts the count. */
    @Volatile
    private var healthForSelected: Long = -1L

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
                steerIntoPool(pair.first, pair.second)
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
        healthForSelected = -1L
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
     * Watchdog, standby model: no probing at all. The core counts
     * consecutive failed dials of REAL traffic on the main line
     * (a handshake that never came back); this tick only reads
     * that number through the service, every 10 seconds. At
     * [DEATH_THRESHOLD] in a row the node in use is dead: swap at
     * once to the best recorded standby of the lit pool. A change
     * of selection restarts the count, so every member is judged
     * on its own dials only. No traffic means no signal and no
     * verdict — an idle line is left alone.
     */
    private suspend fun watchdogTick() {
        if (!DataStore.serviceState.connected) return
        val armed = armedPair(app) ?: return
        val service = serviceProvider?.invoke() ?: return
        val selectedId = DataStore.selectedProxy
        val selectedExists = withContext(Dispatchers.IO) {
            selectedId != 0L &&
                SagerDatabase.proxyDao.getById(selectedId) != null
        }
        if (!selectedExists) {
            // The node in use was deleted (by hand, or by a
            // subscription update that replaced it): fail over
            // to the best recorded standby of the armed pool
            // instead of leaving the line aimed at a ghost.
            Log.i(TAG, "watchdog: selected profile is gone; failing over")
            failOverFromRecord(armed.first, armed.second, selectedId)
            healthForSelected = -1L
            scope.launch { runSpeedRound() }
            return
        }
        if (healthForSelected != selectedId) {
            healthForSelected = selectedId
            runCatching { service.resetDialHealth(MAIN_LINE_TAG) }
            return
        }
        val failures = runCatching {
            service.queryDialFailures(MAIN_LINE_TAG)
        }.getOrDefault(0L)
        if (failures >= DEATH_THRESHOLD) {
            Log.i(TAG, "watchdog: line dead ($failures consecutive failed dials)")
            // The lit pool is the general line, so it takes over
            // no matter where the dead node lived.
            failOverFromRecord(armed.first, armed.second, selectedId)
            healthForSelected = -1L
            scope.launch { runSpeedRoundFor(armed.first, armed.second) }
        }
    }

    /**
     * Arm-time steering, from records only: when the current
     * selection sits outside the pool, move it onto the pool's
     * best known member. A selection already inside the pool is
     * left alone — standby rounds never displace a working line.
     */
    private suspend fun steerIntoPool(groupId: Long, cc: String) {
        val inside = withContext(Dispatchers.IO) {
            SagerDatabase.proxyDao.getByGroup(groupId)
        }.any {
            it.id == DataStore.selectedProxy &&
                runCatching {
                    GeoStore.countryFor(app, it.requireBean())
                }.getOrNull() == cc
        }
        if (inside) return
        val best = withContext(Dispatchers.IO) {
            bestKnown(app, groupId, cc)
        } ?: return
        val previous = DataStore.selectedProxy
        DataStore.selectedProxy = best.id
        ProfileManager.postUpdate(previous)
        ProfileManager.postUpdate(best.id)
        Log.i(TAG, "arm steer -> ${best.requireBean().displayName()} (${best.ping}ms)")
        if (DataStore.serviceState.canStop) {
            SagerNet.reloadService()
        }
    }

    /**
     * The selected profile has just been deleted (by hand, or by
     * a subscription update that replaced it). Point the main
     * connection at the best remaining node instead of leaving
     * the selection empty or dangling: inside the armed pool
     * when this group holds it, otherwise the group's best
     * recorded node. Reloads the service when it is running.
     */
    suspend fun repickSelectionForGroup(groupId: Long) {
        val all = withContext(Dispatchers.IO) {
            SagerDatabase.proxyDao.getByGroup(groupId)
        }
        if (all.isEmpty()) return
        val armed = armedPair(app)
        val inPool = if (armed != null && armed.first == groupId) {
            all.filter {
                GeoStore.countryFor(app, it.requireBean()) == armed.second
            }
        } else {
            emptyList()
        }
        val candidates = inPool.ifEmpty { all }
        val best = candidates.filter { it.status == 1 && it.ping > 0 }
            .minByOrNull { it.ping }
            ?: candidates.filter { it.ping > 0 }.minByOrNull { it.ping }
            ?: candidates.first()
        val previous = DataStore.selectedProxy
        if (previous == best.id) return
        DataStore.selectedProxy = best.id
        ProfileManager.postUpdate(previous)
        ProfileManager.postUpdate(best.id)
        Log.i(
            TAG,
            "selection lost; re-picked " +
                best.requireBean().displayName() + " (" + best.ping + "ms)"
        )
        if (DataStore.serviceState.canStop) {
            SagerNet.reloadService()
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


    /** A full pass: whole-group region sweep, then a standby batch. */
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
     * Standby round: test the next [BATCH_SIZE] members of the
     * pool, rotating through it round by round, and refresh
     * their records. The results only stock the standby shelf —
     * this round NEVER moves the main connection: a healthy line
     * keeps its node until the core's dial health condemns it.
     * A pool of five or fewer is covered whole in one round.
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
                runCatching {
                    GeoStore.countryFor(app, it.requireBean())
                }.getOrNull() == cc
            }.sortedBy { it.id }
            if (members.isEmpty()) {
                Log.i(TAG, "standby round for $cc: no members")
                return
            }
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cursorKey = "rot|" + keyOf(groupId, cc)
            val lastId = prefs.getLong(cursorKey, -1L)
            val startIdx = members.indexOfFirst { it.id > lastId }
                .let { if (it < 0) 0 else it }
            val batch = (0 until minOf(BATCH_SIZE, members.size))
                .map { members[(startIdx + it) % members.size] }
            Log.i(TAG, "standby batch for $cc: ${batch.size} of ${members.size} profiles")
            withContext(Dispatchers.IO) {
                batch.map { profile ->
                    async { runCatching { testPing(profile) } }
                }.awaitAll()
            }
            prefs.edit().putLong(cursorKey, batch.last().id).apply()
        } catch (e: Exception) {
            Log.w(TAG, "standby round failed", e)
        } finally {
            roundRunning.set(false)
            doneSignal.complete(Unit)
        }
    }

    /**
     * Connect-time pick: do NOT wait for a fresh round. Connect
     * immediately to the best known node of the lit pool from the
     * recorded results, then let a background standby round keep
     * the records fresh.
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
    internal suspend fun testPing(profile: ProxyEntity): Boolean {
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
