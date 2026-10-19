package com.yishou.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Task::class, Breakpoint::class, Round::class, Pass::class, DailySummary::class, Bet::class, Recall::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
    abstract fun practice(): PracticeDao

    companion object {
        /** v0.5：给轮次和断点加上“骰子第几面”。旧数据保留，面记为 0。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE round ADD COLUMN face INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE breakpoint ADD COLUMN pendingFace INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v0.7：预判本（下注、预演）和间隔回响两张新表，旧表不动。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bet` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `title` TEXT NOT NULL, `claim` TEXT NOT NULL, `basis` TEXT NOT NULL, " +
                        "`confidence` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `resolvedAt` INTEGER, " +
                        "`outcome` INTEGER NOT NULL, `note` TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bet_createdAt` ON `bet` (`createdAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recall` (`roundId` INTEGER NOT NULL, `stage` INTEGER NOT NULL, " +
                        "`doneAt` INTEGER NOT NULL, `result` INTEGER NOT NULL, `said` TEXT NOT NULL, " +
                        "PRIMARY KEY(`roundId`, `stage`))",
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "yishou.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
