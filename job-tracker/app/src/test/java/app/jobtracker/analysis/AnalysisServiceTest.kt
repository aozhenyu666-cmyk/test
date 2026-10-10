package app.jobtracker.analysis

import app.jobtracker.ai.ModelError
import app.jobtracker.ai.ModelException
import app.jobtracker.ai.ModelRole
import app.jobtracker.config.AppConfig
import app.jobtracker.data.MutableClock
import app.jobtracker.data.db.AppDatabase
import app.jobtracker.data.inMemoryDatabase
import app.jobtracker.data.repo.ProfileRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class AnalysisServiceTest {
    private lateinit var db: AppDatabase
    private lateinit var profile: ProfileRepository
    private val jd = "招聘数据分析师，要求本科及以上，熟练使用 SQL 和 Python，有报表经验优先。"

    @Before
    fun setUp() = runTest {
        db = inMemoryDatabase()
        profile = ProfileRepository(db.profileDao(), MutableClock(Instant.parse("2026-10-10T01:00:00Z")))
        profile.save("三年 SQL 报表经验", "数据分析，上海")
    }

    @After
    fun tearDown() = db.close()

    private fun service(model: FakeModelClient) = AnalysisService(model, realPrompts(), profile, AppConfig.Default)

    private suspend fun errorOf(block: suspend () -> Unit): ModelError? =
        try {
            block()
            null
        } catch (e: ModelException) {
            e.error
        }

    @Test
    fun sendsResumeDirectionAndJdToStrongModel() = runTest {
        val model = FakeModelClient({ VALID_ANALYSIS_JSON })
        val result = service(model).analyze(jd)

        assertEquals("示例科技", result.analysis.company)
        assertEquals(VALID_ANALYSIS_JSON.trim(), result.rawJson)
        val call = model.calls.single()
        assertEquals(ModelRole.ANALYSIS, call.role)
        assertTrue(call.user.contains("三年 SQL 报表经验"))
        assertTrue(call.user.contains("数据分析，上海"))
        assertTrue(call.user.contains(jd))
        assertNotNull(call.schema)
    }

    @Test
    fun retriesOnceWhenOutputIsNotJson() = runTest {
        val model = FakeModelClient({ "不是 JSON" }, { VALID_ANALYSIS_JSON })
        service(model).analyze(jd)
        assertEquals(2, model.calls.size)
    }

    @Test
    fun retriesOnceWhenModelReportsInvalidOutput() = runTest {
        val model = FakeModelClient({ throw ModelException(ModelError.InvalidOutput) }, { VALID_ANALYSIS_JSON })
        service(model).analyze(jd)
        assertEquals(2, model.calls.size)
    }

    @Test
    fun givesUpAfterSecondInvalidOutput() = runTest {
        val model = FakeModelClient({ "{}" }, { "{}" }, { VALID_ANALYSIS_JSON })
        assertEquals(ModelError.InvalidOutput, errorOf { service(model).analyze(jd) })
        assertEquals(2, model.calls.size)
    }

    @Test
    fun doesNotRetryWhenOffline() = runTest {
        val model = FakeModelClient({ throw ModelException(ModelError.NoNetwork) }, { VALID_ANALYSIS_JSON })
        assertEquals(ModelError.NoNetwork, errorOf { service(model).analyze(jd) })
        assertEquals(1, model.calls.size)
    }
}
