package app.jobtracker.ui.analysis

import app.jobtracker.ai.ModelError
import app.jobtracker.ai.ModelException
import app.jobtracker.analysis.AnalysisService
import app.jobtracker.analysis.FakeModelClient
import app.jobtracker.analysis.VALID_ANALYSIS_JSON
import app.jobtracker.analysis.realPrompts
import app.jobtracker.config.AppConfig
import app.jobtracker.data.MutableClock
import app.jobtracker.data.db.AppDatabase
import app.jobtracker.data.inMemoryDatabase
import app.jobtracker.data.model.ApplicationStatus
import app.jobtracker.data.model.MatchLevel
import app.jobtracker.data.repo.ApplicationRepository
import app.jobtracker.data.repo.ProfileRepository
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AnalysisViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val clock = MutableClock(Instant.parse("2026-10-10T01:00:00Z"))
    private lateinit var db: AppDatabase
    private lateinit var profile: ProfileRepository
    private lateinit var applications: ApplicationRepository
    private val jd = "招聘数据分析师，要求本科及以上，熟练使用 SQL 和 Python，有报表经验优先，能独立搭建数据看板。"

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        db = inMemoryDatabase()
        profile = ProfileRepository(db.profileDao(), clock)
        applications = ApplicationRepository(db.applicationDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun viewModel(model: FakeModelClient): AnalysisViewModel {
        val vm = AnalysisViewModel(AnalysisService(model, realPrompts(), profile, AppConfig.Default), applications, profile, AppConfig.Default)
        vm.state.first { it.hasResume != null }
        return vm
    }

    @Test
    fun cannotAnalyzeWithoutResume() = runTest(main) {
        val vm = viewModel(FakeModelClient())
        vm.onJdChange(jd)
        assertFalse(vm.canAnalyze())
    }

    @Test
    fun cannotAnalyzeShortJd() = runTest(main) {
        profile.save("简历", "")
        val vm = viewModel(FakeModelClient())
        vm.state.first { it.hasResume == true }
        vm.onJdChange("太短的 JD")
        assertFalse(vm.canAnalyze())
        vm.onJdChange(jd)
        assertTrue(vm.canAnalyze())
    }

    @Test
    fun addCreatesPendingRecordWithEditedNamesAndRawJson() = runTest(main) {
        profile.save("简历", "")
        val vm = viewModel(FakeModelClient({ VALID_ANALYSIS_JSON }))
        vm.state.first { it.hasResume == true }
        vm.onJdChange(jd)
        vm.analyze()
        vm.state.first { it.result != null && !it.loading }

        vm.onCompanyChange("示例科技（上海）")
        val added = CompletableDeferred<Long>()
        vm.addToApplications { added.complete(it) }
        val saved = applications.get(added.await())!!

        assertEquals("示例科技（上海）", saved.company)
        assertEquals("数据分析师", saved.title)
        assertEquals(ApplicationStatus.PENDING, saved.status)
        assertEquals(MatchLevel.MEDIUM, saved.matchLevel)
        assertEquals(jd, saved.jdText)
        assertEquals(VALID_ANALYSIS_JSON.trim(), saved.analysisJson)
        assertTrue(vm.state.value.added)
    }

    @Test
    fun reanalyzeDoesNotTouchAddedRecord() = runTest(main) {
        profile.save("简历", "")
        val second = VALID_ANALYSIS_JSON.replace("示例科技", "另一家公司")
        val vm = viewModel(FakeModelClient({ VALID_ANALYSIS_JSON }, { second }))
        vm.state.first { it.hasResume == true }
        vm.onJdChange(jd)
        vm.analyze()
        vm.state.first { it.result != null && !it.loading }
        val added = CompletableDeferred<Long>()
        vm.addToApplications { added.complete(it) }
        val id = added.await()

        vm.analyze()
        vm.state.first { it.company == "另一家公司" && !it.loading }

        assertEquals("示例科技", applications.get(id)!!.company)
        assertEquals(1, applications.getAllForExport().size)
        assertFalse(vm.state.value.added)
    }

    @Test
    fun failureKeepsJdAndShowsError() = runTest(main) {
        profile.save("简历", "")
        val vm = viewModel(FakeModelClient({ throw ModelException(ModelError.NoNetwork) }))
        vm.state.first { it.hasResume == true }
        vm.onJdChange(jd)
        vm.analyze()
        vm.state.first { it.error != null && !it.loading }

        assertEquals(ModelError.NoNetwork, vm.state.value.error)
        assertEquals(jd, vm.state.value.jd)
    }
}
