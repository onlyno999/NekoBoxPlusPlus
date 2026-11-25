package io.nekohasekai.sagernet.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.matrix.roomigrant.GenerateRoomMigrations
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.gson.GsonConverters
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 6,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6)
    ]
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
@GenerateRoomMigrations
abstract class SagerDatabase : RoomDatabase() {

    companion object {
        // Dedicated pool for database work. Page reads used to
        // queue on the shared default dispatcher behind
        // subscription updates and speed tests; a read stuck in
        // that queue was the blank pages' root cause.
        private val dbExecutor: java.util.concurrent.Executor =
            java.util.concurrent.Executors.newCachedThreadPool()

        @OptIn(DelicateCoroutinesApi::class)
        @Suppress("EXPERIMENTAL_API_USAGE")
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
//                .addMigrations(*SagerDatabase_Migrations.build())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                .fallbackToDestructiveMigration()
                .setQueryExecutor(dbExecutor)
                .setTransactionExecutor(dbExecutor)
                .build()
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()

        // Independent per-page database instance: same data file, own
        // connection pool. A page's reads never queue behind another
        // page's subscription updates or speed tests, which was the
        // blank-pages root cause. Multi-instance invalidation
        // (already enabled above) keeps each instance's view fresh.
        // No extra data is stored: the file is shared, only the
        // connection is separate, so the app does not grow.
        fun newPageInstance(): SagerDatabase {
            return Room.databaseBuilder(
                SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE
            )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                .fallbackToDestructiveMigration()
                .setQueryExecutor(java.util.concurrent.Executors.newCachedThreadPool())
                .setTransactionExecutor(java.util.concurrent.Executors.newCachedThreadPool())
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Default page cache is 2000 pages (~8MB cap per
                        // connection); a full-table scan warms it up fast.
                        // Cap each page instance at ~2MB.
                        db.execSQL("PRAGMA cache_size = -2048")
                    }
                })
                .build()
        }

    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao

}
