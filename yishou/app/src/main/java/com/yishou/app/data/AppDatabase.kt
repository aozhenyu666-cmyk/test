package com.yishou.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Task::class, Breakpoint::class, Round::class, Pass::class, DailySummary::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        /** v0.5：给轮次和断点加上“骰子第几面”。旧数据保留，面记为 0。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE round ADD COLUMN face INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE breakpoint ADD COLUMN pendingFace INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "yishou.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
