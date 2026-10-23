package com.yishou.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Task::class, Breakpoint::class, Round::class, Pass::class, DailySummary::class, Bet::class, Recall::class, TrainSet::class, SetMove::class, ChainRecall::class, CardRow::class, CardAttempt::class, Material::class, DrillRow::class, Deed::class, MaterialKey::class, Job::class, JobStep::class, Outside::class, Line::class, Move::class, Echo::class, Endgame::class, Impulse::class, Soup::class, PartnerSession::class, ScrollQuizRow::class, CompanionSessionRow::class, CompanionTurnRow::class],
    version = 15,
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
    abstract fun echoes(): EchoDao
    abstract fun endgames(): EndgameDao
    abstract fun impulses(): ImpulseDao
    abstract fun partners(): PartnerDao
    abstract fun soups(): SoupDao
    abstract fun scrollQuizzes(): ScrollQuizDao
    abstract fun companion(): CompanionDao

    companion object {
        /**
         * 0.23：视频陪练的两张新表。
         *
         * 只新建，不动任何旧表——旧数据原样保留。
         * 建表语句放在 [CompanionSchemaSql]，测试会拿它和 Room 导出的 schema 比对。
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                CompanionSchemaSql.CREATE.forEach { db.execSQL(it) }
            }
        }

        /** 0.21：刷着练问过的每一句留下来，没接住的回头再想 */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `scroll_quiz` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `askedAt` INTEGER NOT NULL, `pkg` TEXT NOT NULL, `lens` TEXT NOT NULL, `seen` TEXT NOT NULL, `question` TEXT NOT NULL, `reference` TEXT NOT NULL, `text` TEXT NOT NULL, `answer` TEXT NOT NULL, `verdict` TEXT NOT NULL, `line` TEXT NOT NULL, `answeredAt` INTEGER, `redoAt` INTEGER, `redoVerdict` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scroll_quiz_askedAt` ON `scroll_quiz` (`askedAt`)")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `partner_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `material` TEXT NOT NULL, `goal` TEXT NOT NULL, `minutes` INTEGER NOT NULL, `gear` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `presentMs` INTEGER NOT NULL, `leaves` INTEGER NOT NULL, `closeLine` TEXT NOT NULL, `nextStep` TEXT NOT NULL, `route` TEXT NOT NULL)")
            }
        }

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

        /** 岔路 1：回响队列（规则、差值、结论在下一次同类场景开局时推出来）。 */
        /** 0.16：海龟汤 */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `soup` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardId` INTEGER NOT NULL, `asks` INTEGER NOT NULL, `guesses` INTEGER NOT NULL, `solved` INTEGER NOT NULL, `picked` INTEGER, `correct` INTEGER, `log` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_soup_cardId` ON `soup` (`cardId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_soup_createdAt` ON `soup` (`createdAt`)")
            }
        }

        /** 0.15：残局、急刹 */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `endgame` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `crux` TEXT NOT NULL, `ask` TEXT NOT NULL, `aim` TEXT NOT NULL, `shot` TEXT NOT NULL, `law` TEXT NOT NULL, `source` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `seen` INTEGER NOT NULL, `hits` INTEGER NOT NULL, `lastAt` INTEGER, `lastHit` INTEGER NOT NULL, `dueAt` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_endgame_sourceKey` ON `endgame` (`sourceKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_endgame_dueAt` ON `endgame` (`dueAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `impulse` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `raw` TEXT NOT NULL, `facts` TEXT NOT NULL, `fantasies` TEXT NOT NULL, `proof` TEXT NOT NULL, `hasProof` INTEGER, `worst` TEXT NOT NULL, `decision` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `checkAt` INTEGER, `happened` INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_impulse_createdAt` ON `impulse` (`createdAt`)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `echo` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`scene` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `shownAt` INTEGER, `shown` INTEGER NOT NULL, `confirmed` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_echo_scene` ON `echo` (`scene`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_echo_createdAt` ON `echo` (`createdAt`)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "yishou.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                .build()
    }
}
