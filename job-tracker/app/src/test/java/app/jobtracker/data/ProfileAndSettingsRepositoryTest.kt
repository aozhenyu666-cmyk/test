package app.jobtracker.data

import app.jobtracker.config.AppConfig
import app.jobtracker.data.db.AppDatabase
import app.jobtracker.data.repo.ProfileRepository
import app.jobtracker.data.repo.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class ProfileAndSettingsRepositoryTest {
    private val clock = MutableClock(Instant.parse("2026-10-10T01:00:00Z"))
    private lateinit var db: AppDatabase
    private lateinit var profile: ProfileRepository
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        profile = ProfileRepository(db.profileDao(), clock)
        settings = SettingsRepository(db.settingsDao(), AppConfig.Default)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun profileStartsEmptyThenSavesAndOverwritesSingleRow() = runTest {
        assertNull(profile.get())
        assertFalse(profile.observeHasResume().first())

        profile.save("简历第一版", "产品经理，上海")
        profile.save("简历第二版", "产品经理，杭州")

        val saved = profile.get()!!
        assertEquals("简历第二版", saved.resumeText)
        assertEquals("产品经理，杭州", saved.direction)
        assertTrue(profile.observeHasResume().first())
        assertEquals(1, db.query("SELECT * FROM profile", null).use { it.count })
    }

    @Test
    fun blankResumeCountsAsNoResume() = runTest {
        profile.save("   ", "方向")
        assertFalse(profile.observeHasResume().first())
    }

    @Test
    fun settingsFallBackToConfigDefaults() = runTest {
        val s = settings.observe().first()
        assertEquals("claude-sonnet-5-5", s.strongModel)
        assertEquals("claude-haiku-5-5", s.fastModel)
        assertEquals(LocalTime.of(9, 0), s.reminderTime)
        assertTrue(s.channelApps.isEmpty())
    }

    @Test
    fun settingsUpdateKeepsOtherFields() = runTest {
        settings.update { it.copy(reminderTime = LocalTime.of(8, 30)) }
        settings.update { it.copy(channelApps = mapOf("邮箱" to "com.example.mail")) }

        val s = settings.get()
        assertEquals(LocalTime.of(8, 30), s.reminderTime)
        assertEquals(mapOf("邮箱" to "com.example.mail"), s.channelApps)
        assertEquals("claude-sonnet-5-5", s.strongModel)
    }
}
