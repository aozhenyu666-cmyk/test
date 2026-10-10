package app.jobtracker.ui.detail

import app.jobtracker.config.AppConfig
import app.jobtracker.data.MutableClock
import app.jobtracker.data.db.AppDatabase
import app.jobtracker.data.inMemoryDatabase
import app.jobtracker.data.model.ApplicationStatus
import app.jobtracker.data.model.EndResult
import app.jobtracker.data.model.MatchLevel
import app.jobtracker.data.repo.ApplicationRepository
import app.jobtracker.data.repo.NewApplication
import app.jobtracker.domain.FollowUpRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DetailViewModelTest {
    private val main = UnconfinedTestDispatcher()
    // 2026-10-10 09:00 北京时间
    private val clock = MutableClock(Instant.parse("2026-10-10T01:00:00Z"))
    private val today = LocalDate.of(2026, 10, 10)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        appScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun newRecord(channel: String = ""): Long = repo.create(
        NewApplication("示例科技", "数据分析师", "jd", MatchLevel.HIGH, "{}", channel),
    )

    private suspend fun loaded(id: Long): DetailViewModel {
        val vm = DetailViewModel(id, repo, FollowUpRules(AppConfig.Default), clock, appScope, textSaveDelayMillis = 0)
        vm.state.first { it.loaded }
        return vm
    }

    @Test
    fun appliedStatusIsSavedWithFollowUpInThreeDays() = runTest(main) {
        val id = newRecord()
        val vm = loaded(id)
        vm.onStatusChange(ApplicationStatus.APPLIED)

        val saved = repo.observe(id).first { it?.status == ApplicationStatus.APPLIED }!!
        assertEquals(today, saved.appliedAt)
        assertEquals(today.plusDays(3), saved.nextFollowUpAt)
    }

    @Test
    fun closingSavesResultAndClearsFollowUp() = runTest(main) {
        val id = newRecord()
        val vm = loaded(id)
        vm.onStatusChange(ApplicationStatus.APPLIED)
        vm.onClose(EndResult.REJECTED)

        val saved = repo.observe(id).first { it?.status == ApplicationStatus.CLOSED }!!
        assertEquals(EndResult.REJECTED, saved.endResult)
        assertNull(saved.nextFollowUpAt)
    }

    @Test
    fun choosingClosedWithoutResultDoesNothing() = runTest(main) {
        val id = newRecord()
        val vm = loaded(id)
        vm.onStatusChange(ApplicationStatus.CLOSED)
        assertEquals(ApplicationStatus.PENDING, vm.state.value.app!!.status)
    }

    @Test
    fun textEditsAndManualDateAreSaved() = runTest(main) {
        val id = newRecord()
        val vm = loaded(id)
        vm.onNoteChange("聊了埋点方案")
        vm.onChannelChange("官网")
        vm.onNextFollowUpChange(today)
        vm.flush()

        val saved = repo.observe(id).first { it?.note == "聊了埋点方案" && it.channel == "官网" && it.nextFollowUpAt == today }
        assertEquals("示例科技", saved!!.company)
    }

    @Test
    fun channelSuggestionsComeFromOtherRecords() = runTest(main) {
        newRecord(channel = "邮箱")
        newRecord(channel = "官网")
        val vm = loaded(newRecord())
        val channels = vm.channels.first { it.isNotEmpty() }
        assertEquals(listOf("官网", "邮箱"), channels)
    }

    @Test
    fun deleteRemovesRecord() = runTest(main) {
        val id = newRecord()
        val vm = loaded(id)
        vm.delete()
        repo.observe(id).first { it == null }
        assertEquals(0, repo.getAllForExport().size)
    }
}
