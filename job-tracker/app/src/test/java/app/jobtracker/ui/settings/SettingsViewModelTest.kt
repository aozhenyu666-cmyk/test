package app.jobtracker.ui.settings

import app.jobtracker.R
import app.jobtracker.config.AppConfig
import app.jobtracker.data.FakeApiKeyStore
import app.jobtracker.data.MutableClock
import app.jobtracker.data.db.AppDatabase
import app.jobtracker.data.inMemoryDatabase
import app.jobtracker.data.repo.ProfileRepository
import app.jobtracker.data.repo.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val clock = MutableClock(Instant.parse("2026-10-10T01:00:00Z"))
    private lateinit var db: AppDatabase
    private lateinit var profile: ProfileRepository
    private lateinit var settings: SettingsRepository
    private lateinit var keys: FakeApiKeyStore

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        db = inMemoryDatabase()
        profile = ProfileRepository(db.profileDao(), clock)
        settings = SettingsRepository(db.settingsDao(), AppConfig.Default)
        keys = FakeApiKeyStore()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun loadedViewModel(): SettingsViewModel {
        val vm = SettingsViewModel(profile, settings, keys)
        vm.state.first { it.loaded }
        return vm
    }

    @Test
    fun firstLaunchShowsEmptyProfileAndDefaultModels() = runTest(main) {
        val s = loadedViewModel().state.value
        assertEquals("", s.resumeText)
        assertFalse(s.hasSavedKey)
        assertEquals("https://api.anthropic.com", s.apiBaseUrl)
        assertEquals("claude-sonnet-5-5", s.strongModel)
        assertEquals("claude-haiku-5-5", s.fastModel)
    }

    @Test
    fun savesProfileAndReloadsIt() = runTest(main) {
        val vm = loadedViewModel()
        vm.onResumeChange("五年产品经验")
        vm.onDirectionChange("产品经理，上海")
        vm.saveProfile()
        vm.state.first { it.message == R.string.settings_saved }

        val reloaded = loadedViewModel().state.value
        assertEquals("五年产品经验", reloaded.resumeText)
        assertEquals("产品经理，上海", reloaded.direction)
    }

    @Test
    fun blankResumeIsNotSaved() = runTest(main) {
        val vm = loadedViewModel()
        vm.onResumeChange("   ")
        vm.saveProfile()

        assertEquals(R.string.settings_error_resume_blank, vm.state.value.message)
        assertNull(profile.get())
    }

    @Test
    fun savesKeyOutsideDatabaseAndClearsInput() = runTest(main) {
        val vm = loadedViewModel()
        vm.onApiKeyChange("  sk-test-123  ")
        vm.onBaseUrlChange("https://relay.example.com/")
        vm.saveModelSettings()
        vm.state.first { it.message == R.string.settings_saved }

        assertEquals("sk-test-123", keys.get())
        assertTrue(vm.state.value.hasSavedKey)
        assertEquals("", vm.state.value.apiKeyInput)
        assertEquals("https://relay.example.com", settings.get().apiBaseUrl)
        // 数据库里任何一张表都不能出现密钥
        for (table in listOf("settings", "profile", "applications")) {
            db.query("SELECT * FROM $table", null).use { c ->
                while (c.moveToNext()) {
                    for (i in 0 until c.columnCount) {
                        if (c.getType(i) == android.database.Cursor.FIELD_TYPE_STRING) {
                            assertFalse(c.getString(i).contains("sk-test-123"))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun emptyKeyInputKeepsSavedKey() = runTest(main) {
        keys.set("sk-old")
        val vm = loadedViewModel()
        assertTrue(vm.state.value.hasSavedKey)

        vm.onStrongModelChange("claude-opus-5-5")
        vm.saveModelSettings()
        vm.state.first { it.message == R.string.settings_saved }

        assertEquals("sk-old", keys.get())
        assertEquals("claude-opus-5-5", settings.get().strongModel)
    }

    @Test
    fun invalidUrlShowsErrorAndSavesNothing() = runTest(main) {
        val vm = loadedViewModel()
        vm.onApiKeyChange("sk-new")
        vm.onBaseUrlChange("http://insecure.example.com")
        vm.saveModelSettings()

        assertEquals(R.string.settings_error_base_url, vm.state.value.modelError)
        assertNull(keys.get())
        assertEquals("https://api.anthropic.com", settings.get().apiBaseUrl)
    }

    @Test
    fun clearKeyRemovesIt() = runTest(main) {
        keys.set("sk-old")
        val vm = loadedViewModel()
        vm.clearApiKey()

        assertNull(keys.get())
        assertFalse(vm.state.value.hasSavedKey)
    }
}
