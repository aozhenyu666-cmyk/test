package com.behaviordept.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Skill::class, SubSkill::class, Drill::class, StudyUnit::class, Review::class,
        Transfer::class, MatchLog::class, TrainingSession::class, Rule::class, UsageDay::class,
        UrgeLog::class, Event::class, WeeklyReview::class, AiCall::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studyDao(): StudyDao
    abstract fun sessionDao(): SessionDao
    abstract fun eventDao(): EventDao
    abstract fun aiCallDao(): AiCallDao
    abstract fun backupDao(): BackupDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "behavior.db").build()
    }
}
