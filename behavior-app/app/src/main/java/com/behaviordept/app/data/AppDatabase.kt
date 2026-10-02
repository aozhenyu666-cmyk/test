package com.behaviordept.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Skill::class, SubSkill::class, Drill::class, StudyUnit::class, Review::class,
        Transfer::class, MatchLog::class, TrainingSession::class, Rule::class, UsageDay::class,
        UrgeLog::class, Event::class, WeeklyReview::class, AiCall::class,
        ExamSitting::class, ExamSection::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studyDao(): StudyDao
    abstract fun sessionDao(): SessionDao
    abstract fun eventDao(): EventDao
    abstract fun aiCallDao(): AiCallDao
    abstract fun backupDao(): BackupDao
    abstract fun skillDao(): SkillDao
    abstract fun guardDao(): GuardDao
    abstract fun weeklyDao(): WeeklyDao

    companion object {
        const val NAME = "behavior.db"

        fun build(context: Context, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

/** v1 → v2：训练引擎、考试登记、防线规则需要的新列和新表。已有数据全部保留。 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `skill` ADD COLUMN `focusSince` INTEGER")
        db.execSQL("ALTER TABLE `review` ADD COLUMN `dueAt` INTEGER")
        db.execSQL("ALTER TABLE `drill` ADD COLUMN `title` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `drill` ADD COLUMN `metric` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `drill` ADD COLUMN `active` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `rule` ADD COLUMN `name` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `rule` ADD COLUMN `pendingPackages` TEXT")
        db.execSQL("ALTER TABLE `rule` ADD COLUMN `pendingDelete` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `exam_sitting` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`skillId` INTEGER NOT NULL, `time` INTEGER NOT NULL, `kind` TEXT NOT NULL, `score` REAL, " +
                "`essayScore` REAL, `note` TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exam_sitting_skillId` ON `exam_sitting` (`skillId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `exam_section` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sittingId` INTEGER NOT NULL, `module` TEXT NOT NULL, `correct` INTEGER NOT NULL, " +
                "`total` INTEGER NOT NULL, `minutes` INTEGER, `lostReason` TEXT)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exam_section_sittingId` ON `exam_section` (`sittingId`)")
    }
}
