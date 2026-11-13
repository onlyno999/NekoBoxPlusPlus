package io.nekohasekai.sagernet.geo

import android.content.Context
import android.util.Log
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Post-update tester, owner's batch model: a subscription update
 * only ever tests the profiles it just brought in — never the
 * whole group. The new arrivals are worked through in batches
 * of ten: region probe first, then latency, then the next batch.
 * A batch holding a dead node or two changes nothing, and an
 * update that brings one or two nodes finishes in one batch.
 */
object NewNodeTester {

    private const val TAG = "NewNodeTester"
    private const val BATCH = 10

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** Fire-and-forget: test only the given newly arrived profiles. */
    fun enqueue(context: Context, groupId: Long, profiles: List<ProxyEntity>) {
        if (profiles.isEmpty()) return
        val appContext = context.applicationContext
        scope.launch {
            lock.withLock {
                runCatching { test(appContext, groupId, profiles) }
                    .onFailure { Log.w(TAG, "new-node test failed", it) }
            }
        }
    }

    private suspend fun test(context: Context, groupId: Long, profiles: List<ProxyEntity>) {
        Log.i(TAG, "group $groupId: ${profiles.size} new profiles, batches of $BATCH")
        for (chunk in profiles.chunked(BATCH)) {
            GeoProbe.probeProfiles(context, chunk)
            withContext(Dispatchers.IO) {
                chunk.map { profile ->
                    async { runCatching { GroupBalancer.testPing(profile) } }
                }.awaitAll()
            }
            GroupManager.postReload(groupId)
        }
        Log.i(TAG, "group $groupId: new-profile testing done")
    }
}
