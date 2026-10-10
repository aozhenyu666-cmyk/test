package app.jobtracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.jobtracker.data.db.AppDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 可手动拨动的时钟，用来验证 updatedAt 等时间字段 */
class MutableClock(var now: Instant, private val zone: ZoneId = ZoneOffset.ofHours(8)) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now
}

fun inMemoryDatabase(): AppDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
