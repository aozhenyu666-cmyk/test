package com.yishou.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Task::class, Breakpoint::class, Round::class, Pass::class, DailySummary::class, Bet::class, Recall::class, TrainSet::class, SetMove::class, ChainRecall::class, CardRow::class, CardAttempt::class, Material::class, DrillRow::class, Deed::class, MaterialKey::class, Job::class, JobStep::class, Outside::class, Line::class, Move::class],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
    abstract fun practice(): PracticeDao
    abstract fun coach(): CoachDao
    abstract fun cards(): CardDao
    abstract fun think(): ThinkDao
    abstract fun jobs(): JobDao
    abstract fun lines(): LineDao

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

        /** v0.8：教练组。一组训练、组里的每一手、离开回来后的结论链复述。 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `train_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`taskId` INTEGER NOT NULL, `face` INTEGER NOT NULL, `level` INTEGER NOT NULL, `goal` TEXT NOT NULL, " +
                        "`material` TEXT NOT NULL, `standard` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, " +
                        "`status` TEXT NOT NULL, `lesson` TEXT NOT NULL, `warmup` TEXT NOT NULL, `verified` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_train_set_startedAt` ON `train_set` (`startedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `set_move` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`setId` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `kind` TEXT NOT NULL, `attempt` INTEGER NOT NULL, " +
                        "`question` TEXT NOT NULL, `say` TEXT NOT NULL, `answer` TEXT NOT NULL, `verdict` TEXT NOT NULL, " +
                        "`gap` TEXT NOT NULL, `conclusion` TEXT NOT NULL, `help` INTEGER NOT NULL, `helpText` TEXT NOT NULL, " +
                        "`correctStep` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `answeredAt` INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_set_move_setId` ON `set_move` (`setId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_set_move_createdAt` ON `set_move` (`createdAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chain_recall` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`setId` INTEGER NOT NULL, `at` INTEGER NOT NULL, `total` INTEGER NOT NULL, `recalled` INTEGER NOT NULL, " +
                        "`awaySeconds` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chain_recall_at` ON `chain_recall` (`at`)")
            }
        }

        /** v0.9：题卡。答案在出题时定死的单选题，以及每次作答的结果。 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `card` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`skill` TEXT NOT NULL, `difficulty` INTEGER NOT NULL, `json` TEXT NOT NULL, `verified` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `usedAt` INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_createdAt` ON `card` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_skill` ON `card` (`skill`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `card_attempt` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`cardId` INTEGER NOT NULL, `skill` TEXT NOT NULL, `difficulty` INTEGER NOT NULL, `mode` TEXT NOT NULL, " +
                        "`level` INTEGER NOT NULL, `bet` INTEGER NOT NULL, `conf` TEXT NOT NULL, `evidenceOk` INTEGER NOT NULL, " +
                        "`checks` INTEGER NOT NULL, `checksOk` INTEGER NOT NULL, `final` INTEGER NOT NULL, " +
                        "`firstCorrect` INTEGER NOT NULL, `finalCorrect` INTEGER NOT NULL, `help` INTEGER NOT NULL, " +
                        "`cause` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, `verified` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_attempt_cardId` ON `card_attempt` (`cardId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_card_attempt_createdAt` ON `card_attempt` (`createdAt`)")
            }
        }

        /** v0.10：素材库、各种练法的记录、实操（先估再对照）。旧表不动。 */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `material` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `text` TEXT NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`usedAt` INTEGER, `uses` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_material_createdAt` ON `material` (`createdAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drill` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`materialId` INTEGER, `lens` TEXT NOT NULL, `key` TEXT NOT NULL, `answer` TEXT NOT NULL, " +
                        "`checks` TEXT NOT NULL, `score` INTEGER NOT NULL, `reply` TEXT NOT NULL, `takeaway` TEXT NOT NULL, " +
                        "`disputed` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_drill_createdAt` ON `drill` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_drill_materialId` ON `drill` (`materialId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `deed` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `plan` TEXT NOT NULL, `estimate` REAL NOT NULL, `unit` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `actual` REAL, `note` TEXT NOT NULL, `resolvedAt` INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_deed_createdAt` ON `deed` (`createdAt`)")
            }
        }

        /** v0.11：题库申论题的参考要点；做事（一步一步走）的事和步。旧表不动。 */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `material_key` (`materialId` INTEGER NOT NULL, `key` TEXT NOT NULL, PRIMARY KEY(`materialId`))")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `job` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                        "`mode` TEXT NOT NULL, `estimateMinutes` INTEGER, `createdAt` INTEGER NOT NULL, `finishedAt` INTEGER, " +
                        "`takeaway` TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_createdAt` ON `job` (`createdAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `job_step` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `jobId` INTEGER NOT NULL, " +
                        "`idx` INTEGER NOT NULL, `text` TEXT NOT NULL, `startedAt` INTEGER, `doneAt` INTEGER, `result` TEXT NOT NULL, " +
                        "`note` TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_job_step_jobId` ON `job_step` (`jobId`)")
            }
        }

        /** v0.12：刷题 App 的练习记录；做事可以指向一个 App。 */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `job` ADD COLUMN `app` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outside` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `app` TEXT NOT NULL, " +
                        "`subject` TEXT NOT NULL, `questions` INTEGER NOT NULL, `correct` INTEGER, `seconds` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, `startedAt` INTEGER, `jobId` INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outside_createdAt` ON `outside` (`createdAt`)")
            }
        }

        /** v0.13：主线（一局一局往下想）和每一手。旧表不动。 */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `line` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, " +
                        "`anchor` TEXT NOT NULL, `conclusions` TEXT NOT NULL, `doubts` TEXT NOT NULL, `nextQuestion` TEXT NOT NULL, " +
                        "`face` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `closedAt` INTEGER, `materialId` INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_line_updatedAt` ON `line` (`updatedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `move` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lineId` INTEGER NOT NULL, " +
                        "`question` TEXT NOT NULL, `answer` TEXT NOT NULL, `ask` TEXT NOT NULL, `verdict` TEXT NOT NULL, `note` TEXT NOT NULL, " +
                        "`gist` TEXT NOT NULL, `face` INTEGER NOT NULL, `voice` INTEGER NOT NULL, `latencyMs` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_move_lineId` ON `move` (`lineId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_move_createdAt` ON `move` (`createdAt`)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "yishou.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build()
    }
}
