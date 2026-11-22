package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Diagnostic for the blank-page wedge: when a page's database
 * read stays stuck for over 12 s - no exception, no result -
 * dump every thread's stack into the app log once per process,
 * so the stuck spot shows up in the log page.
 */
object DbWatchdog {
    private val dumped = AtomicBoolean(false)

    fun dumpOnce(tag: String) {
        if (!dumped.compareAndSet(false, true)) return
        Logs.w("NBPP DB WATCHDOG: read stuck >12s at $tag - thread dump follows")
        for ((t, st) in Thread.getAllStackTraces()) {
            val sb = StringBuilder()
            sb.append("THREAD ").append(t.name).append(" [").append(t.state).append("]\n")
            for (el in st.take(14)) sb.append("  at ").append(el.toString()).append('\n')
            Logs.w(sb.toString())
        }
        Logs.w("NBPP DB WATCHDOG: dump end")
    }
}
