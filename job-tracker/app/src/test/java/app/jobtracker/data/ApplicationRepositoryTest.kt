package app.jobtracker.data

import app.jobtracker.data.db.AppDatabase
import app.jobtracker.data.model.ApplicationStatus
import app.jobtracker.data.model.EndResult
import app.jobtracker.data.model.MatchLevel
import app.jobtracker.data.repo.ApplicationRepository
import app.jobtracker.data.repo.NewApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ApplicationRepositoryTest {
    private val today = LocalDate.of(2026, 10, 10)
    private val clock = MutableClock(Instant.parse("2026-10-10T01:00:00Z"))
    private lateinit var db: AppDatabase
    private lateinit var repo: ApplicationRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repo = ApplicationRepository(db.applicationDao(), clock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun add(company: String, nextFollowUpAt: LocalDate? = null, status: ApplicationStatus? = null): Long {
        val id = repo.create(
            NewApplication(
                company = company,
                title = "产品经理",
                jdText = "岗位描述原文",
                matchLevel = MatchLevel.HIGH,
                analysisJson = """{"matchLevel":"high"}""",
            ),
        )
        if (nextFollowUpAt != null || status != null) {
            val saved = repo.get(id)!!
            repo.update(
                saved.copy(
                    nextFollowUpAt = nextFollowUpAt,
                    status = status ?: saved.status,
                    endResult = if (status == ApplicationStatus.CLOSED) EndResult.REJECTED else null,
                ),
            )
        }
        return id
    }

    @Test
    fun createSavesPendingRecordWithAllFields() = runTest {
        val id = repo.create(
            NewApplication(
                company = "  示例科技 ",
                title = "数据分析师",
                jdText = "JD 原文",
                matchLevel = MatchLevel.MEDIUM,
                analysisJson = """{"advice":"maybe"}""",
                channel = "官网",
            ),
        )

        val saved = repo.get(id)!!
        assertEquals("示例科技", saved.company)
        assertEquals(ApplicationStatus.PENDING, saved.status)
        assertEquals("JD 原文", saved.jdText)
        assertEquals("""{"advice":"maybe"}""", saved.analysisJson)
        assertEquals(MatchLevel.MEDIUM, saved.matchLevel)
        assertEquals(0, saved.followUpCount)
        assertNull(saved.nextFollowUpAt)
        assertEquals(clock.now, saved.createdAt)
    }

    @Test
    fun updateRefreshesUpdatedAt() = runTest {
        val id = add("甲公司")
        clock.now = clock.now.plusSeconds(60)

        repo.update(repo.get(id)!!.copy(note = "聊了数据埋点"))

        val saved = repo.get(id)!!
        assertEquals("聊了数据埋点", saved.note)
        assertEquals(clock.now, saved.updatedAt)
    }

    @Test
    fun closedRecordRequiresEndResult() = runTest {
        val id = add("甲公司")
        val saved = repo.get(id)!!

        val closedWithoutResult = runCatching { repo.update(saved.copy(status = ApplicationStatus.CLOSED)) }
        assertTrue(closedWithoutResult.exceptionOrNull() is IllegalArgumentException)

        val openWithResult = runCatching { repo.update(saved.copy(endResult = EndResult.OFFER)) }
        assertTrue(openWithResult.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun allIsSortedByNextFollowUpWithOverdueFirstAndUndatedLast() = runTest {
        add("无日期")
        add("后天", today.plusDays(2))
        add("逾期", today.minusDays(1))
        add("今天", today)

        val companies = repo.observeAll().first().map { it.company }
        assertEquals(listOf("逾期", "今天", "后天", "无日期"), companies)
    }

    @Test
    fun dueContainsTodayAndOverdueButNotFutureOrClosed() = runTest {
        add("逾期", today.minusDays(3))
        add("今天", today)
        add("明天", today.plusDays(1))
        add("已结束", today.minusDays(1), ApplicationStatus.CLOSED)

        val companies = repo.observeDue(today).first().map { it.company }
        assertEquals(listOf("逾期", "今天"), companies)
    }

    @Test
    fun filterByStatus() = runTest {
        add("待投递")
        add("已投递", today, ApplicationStatus.APPLIED)

        val pending = repo.observeByStatus(ApplicationStatus.PENDING).first().map { it.company }
        assertEquals(listOf("待投递"), pending)
    }

    @Test
    fun deleteRemovesRecord() = runTest {
        val id = add("甲公司")
        repo.delete(id)
        assertNull(repo.get(id))
        assertEquals(0, repo.getAllForExport().size)
    }
}
