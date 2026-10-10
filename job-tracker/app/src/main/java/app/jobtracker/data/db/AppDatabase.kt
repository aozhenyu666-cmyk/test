package app.jobtracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ApplicationEntity::class, ProfileEntity::class, SettingsEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun applicationDao(): ApplicationDao
    abstract fun profileDao(): ProfileDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        private const val FILE_NAME = "job_tracker.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME).build()
    }
}
