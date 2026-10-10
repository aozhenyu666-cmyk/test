package app.jobtracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.jobtracker.data.db.AppDatabase
import app.jobtracker.security.ApiKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** 测试用的密钥存储，不碰 Android Keystore（Robolectric 不支持） */
class FakeApiKeyStore(initial: String? = null) : ApiKeyStore {
    private var key: String? = initial
    private val _hasKey = MutableStateFlow(!initial.isNullOrEmpty())
    override val hasKey: StateFlow<Boolean> = _hasKey

    override fun get(): String? = key

    override fun set(key: String) {
        this.key = key
        _hasKey.value = key.isNotEmpty()
    }

    override fun clear() {
        key = null
        _hasKey.value = false
    }
}
