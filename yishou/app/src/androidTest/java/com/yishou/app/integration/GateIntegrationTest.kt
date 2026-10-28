package com.yishou.app.integration

import android.content.Intent
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.yishou.app.data.*
import com.yishou.app.gate.*
import com.yishou.app.settings.AppGroup
import com.yishou.app.ui.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.runner.RunWith
import com.yishou.app.integration.RuntimeSupport.app
import com.yishou.app.integration.RuntimeSupport.main
import com.yishou.app.integration.RuntimeSupport.reset
import com.yishou.app.integration.RuntimeSupport.eventually
import com.yishou.app.integration.RuntimeSupport.device
import com.yishou.app.integration.RuntimeSupport.find

@RunWith(AndroidJUnit4::class)
class GateIntegrationTest {
    @get:Rule val evidence = RuntimeEvidence()
    @Before fun before() = runBlocking { reset() }
    @After fun after() {
        device.executeShellCommand("settings put secure enabled_accessibility_services ''")
        device.executeShellCommand("settings put secure accessibility_enabled 0")
        app.settings.saveLlm(RuntimeSupport.config(""))
        device.pressHome()
    }
    @Test fun questionJudgeAnswerAndTriggerAreAllRequiredBeforeRealRoomPassIsIssued() = runBlocking {
        val server = MockWebServer().apply { start() }
        val activity = RuntimeSupport.activity()
        val owner = ViewModelStore()
        try {
            app.settings.saveLlm(RuntimeSupport.config(server.url("/v1").toString()))
            RuntimeSupport.seedCard()
            app.database.strands().saveFade(FadeRow(RuntimeSupport.card.skill.name, 6, 0))
            lateinit var vm: GateViewModel
            main { vm = GateViewModel(app); owner.put("gate", vm); vm.start(RuntimeSupport.TARGET, "联通娱乐", "入口测试应用") }
            eventually { !vm.state.value.loading && !vm.state.value.busy && vm.probe != null }
            main { vm.showProbe(vm.probe!!) }
            delay(150)
            val answer = "应当先证明来访者是谁再放人，因为营业时段只解决何时可来，不等于已经有了进入资格。"
            server.enqueue(RuntimeSupport.completion("[P1] 命中 | 「应当先证明来访者是谁再放人」\n[P2] 命中 | 「营业时段只解决何时可来」\n[跑题] 否\n[一句话] 条件区分清楚了"))
            val submitted = vm.probe!!
            main { for (n in 1..answer.length) vm.editProbe(answer.take(n)); vm.submitProbe() }
            eventually { !vm.state.value.busy }
            Assert.assertTrue("判卷未通过：${submitted.verdict}; answer=${submitted.answer}; state=${vm.state.value}; current=${vm.probe?.question?.id}", vm.probe?.canSelect() == true)
            main { vm.pass() }
            eventually { !vm.state.value.busy }
            Assert.assertNull(app.database.dao().activePass("联通娱乐", System.currentTimeMillis()))
            main { vm.selectProbeOption(0) }
            eventually { !vm.state.value.busy && vm.review != null }
            main { vm.pass() }
            eventually { !vm.state.value.busy }
            Assert.assertNull(app.database.dao().activePass("联通娱乐", System.currentTimeMillis()))
            main { vm.saveTrigger("进入前", "认真读题") }
            eventually { !vm.state.value.busy }
            Assert.assertFalse(vm.triggerSaved)
            main { vm.saveTrigger("进入前", "先核验身份再决定能否进站") }
            eventually { !vm.state.value.busy && vm.triggerSaved }
            main { vm.pass() }
            eventually { vm.state.value.passMinutes == 15 }
            val pass = app.database.dao().activePass("联通娱乐", System.currentTimeMillis())!!
            Assert.assertEquals(900_000L, pass.endAt - pass.startAt)
            Assert.assertEquals(VERIFIED_GATE_PASS_MARKER, pass.roundId)
            Assert.assertNull(app.database.dao().activePass("联通娱乐", pass.endAt))
            Assert.assertEquals(1, app.database.strands().reflexes().size)
        } finally { main { owner.clear() }; activity.close(); server.shutdown() }
    }
    private suspend fun connectGate() {
        val component = "${app.packageName}/com.yishou.app.gate.GateService"
        device.executeShellCommand("settings put secure enabled_accessibility_services $component")
        device.executeShellCommand("settings put secure accessibility_enabled 1")
        eventually { GateService.connected.value }
        app.settings.updateApp { it.copy(groups = listOf(AppGroup("联通娱乐", 15)), watched = mapOf(RuntimeSupport.TARGET to "联通娱乐")) }
    }
    @Test fun openingAnExternalAppIsInterceptedByTheActualAccessibilityService() = runBlocking {
        val activity = RuntimeSupport.activity()
        try {
            connectGate()
            RuntimeSupport.fixture()
            Assert.assertTrue(device.wait(Until.hasObject(By.text("打开「入口测试应用」之前")), 15_000))
            RuntimeEvidence.capture("gate-intercepts-external-app")
            Assert.assertNull(app.database.dao().activePass("联通娱乐", System.currentTimeMillis()))
            find(By.text("先回桌面")).click()
            eventually { !GateActivity.isShowing }
        } finally { activity.close() }
    }
    @Test fun twentyExternalAppOpeningsProduceTwentyGatesWithoutDuplicateGrants() = runBlocking {
        val activity = RuntimeSupport.activity()
        try {
            connectGate()
            val day = java.time.LocalDate.now(com.yishou.app.settings.AppZone.zone)
            val initial = app.stats.day(day).event(com.yishou.app.stats.DayStats.GATE_SHOWN)
            repeat(20) { index ->
                RuntimeSupport.fixture()
                val shown = device.wait(Until.hasObject(By.text("打开「入口测试应用」之前")), 8_000)
                Assert.assertTrue("第 ${index + 1} 次打开未拦截；connected=${GateService.connected.value} foreground=${GateService.foreground} showing=${GateActivity.isShowing} problem=${GateService.problem.value}", shown)
                Assert.assertEquals(initial + index + 1, app.stats.day(day).event(com.yishou.app.stats.DayStats.GATE_SHOWN))
                Assert.assertNull(app.database.dao().activePass("联通娱乐", System.currentTimeMillis()))
                find(By.text("先回桌面")).click()
                eventually { !GateActivity.isShowing && GateService.foreground != RuntimeSupport.TARGET && GateService.foreground != app.packageName }
            }
            Assert.assertEquals(20, app.stats.day(day).event(com.yishou.app.stats.DayStats.GATE_SHOWN) - initial)
            RuntimeEvidence.capture("twenty-external-openings-blocked")
        } finally { activity.close() }
    }
    @Test fun passExpiryRelocksEvenAfterTheNotificationShadeCoveredTheExternalApp() = runBlocking {
        val activity = RuntimeSupport.activity()
        try {
            connectGate()
            val now = System.currentTimeMillis()
            app.database.dao().insertPass(Pass(packageGroup = "联通娱乐", startAt = now, endAt = now + 8_000, roundId = VERIFIED_GATE_PASS_MARKER))
            RuntimeSupport.fixture()
            eventually { GateService.foreground == RuntimeSupport.TARGET }
            Assert.assertFalse(GateActivity.isShowing)
            device.openNotification()
            delay(500)
            Assert.assertEquals(RuntimeSupport.TARGET, GateService.foreground)
            eventually(15_000) { GateActivity.isShowing }
            // 系统通知栏可以覆盖应用；关掉它后应当落在收费站，而不是娱乐页。
            if (!device.hasObject(By.text("打开「入口测试应用」之前"))) device.pressBack()
            Assert.assertTrue(device.wait(Until.hasObject(By.text("打开「入口测试应用」之前")), 5_000))
            RuntimeEvidence.capture("gate-relocks-after-expiry")
            find(By.text("先回桌面")).click()
            eventually { !GateActivity.isShowing }
        } finally { activity.close() }
    }
}
