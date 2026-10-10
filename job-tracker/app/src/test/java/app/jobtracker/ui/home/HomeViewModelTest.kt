package app.jobtracker.ui.home

import app.jobtracker.data.MutableClock
import app.jobtracker.data.db.AppDatabase
import app.jobtracker.data.inMemoryDatabase
import app.jobtracker.data.model.ApplicationStatus
import app.jobtracker.data.model.MatchLevel
import app.jobtracker.data.repo.ApplicationRepository
import app.jobtracker.data.repo.NewApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val clock = MutableClock(Instant.parse("2026-10-10T01:00:00Z"))
    private val today = LocalDate.of(2026, 10, 10)
    private lateinit var db: AppDatabase
    private lateinit var repo: ApplicationRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        db = inMemoryDatabase()
        repo = ApplicationRepository(db.applicationDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun add(company: String, status: ApplicationStatus, next: LocalDate?) {
        val id = repo.create(NewApplication(company, "岗位", "jd", MatchLevel.LOW, "{}"))
        repo.update(repo.get(id)!!.copy(status = status, nextFollowUpAt = next))
    }

    @Test
    fun dueSegmentShowsTodayAndOverdueThenAllCanFilter() = runTest(main) {
        add("逾期", ApplicationStatus.APPLIED, today.minusDays(1))
        add("今天", ApplicationStatus.INTERVIEWING, today)
        add("以后", ApplicationStatus.APPLIED, today.plusDays(2))
        add("待投递", ApplicationStatus.PENDING, null)

        val vm = HomeViewModel(repo, clock)
        val collector = launch { vm.state.collect {} }

        val due = vm.state.first { it.loaded && it.segment == HomeSegment.DUE }
        assertEquals(listOf("逾期", "今天"), due.items.map { it.company })

        vm.selectSegment(HomeSegment.ALL)
        val all = vm.state.first { it.loaded && it.segment == HomeSegment.ALL && it.items.size == 4 }
        assertEquals(listOf("逾期", "今天", "以后", "待投递"), all.items.map { it.company })

        vm.selectStatus(ApplicationStatus.APPLIED)
        val applied = vm.state.first { it.statusFilter == ApplicationStatus.APPLIED && it.loaded }
        assertEquals(listOf("逾期", "以后"), applied.items.map { it.company })

        collector.cancel()
    }
}
