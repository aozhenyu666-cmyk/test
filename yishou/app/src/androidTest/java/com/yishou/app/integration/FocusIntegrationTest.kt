package com.yishou.app.integration

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import com.yishou.app.data.Pass
import com.yishou.app.focus.*
import com.yishou.app.gate.GateActivity
import com.yishou.app.gate.GateService
import com.yishou.app.settings.AppGroup
import com.yishou.app.integration.RuntimeSupport.app
import com.yishou.app.integration.RuntimeSupport.main
import com.yishou.app.integration.RuntimeSupport.reset
import com.yishou.app.integration.RuntimeSupport.eventually
import com.yishou.app.integration.RuntimeSupport.device
import com.yishou.app.integration.RuntimeSupport.find
import com.yishou.app.integration.RuntimeSupport.activity
import com.yishou.app.integration.RuntimeSupport.fixture
import com.yishou.app.integration.RuntimeSupport.TARGET
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusIntegrationTest {
    @get:Rule val evidence = RuntimeEvidence()
    @Before fun before() = runBlocking { reset() }
    @After fun after() = runBlocking { reset() }

    private suspend fun connectGate() {
        val component = "${app.packageName}/com.yishou.app.gate.GateService"
        device.executeShellCommand("settings put secure enabled_accessibility_services $component")
        device.executeShellCommand("settings put secure accessibility_enabled 1")
        eventually { GateService.connected.value }
        app.settings.updateApp { it.copy(groups = listOf(AppGroup("专注测试", 15)), watched = mapOf(TARGET to "专注测试")) }
    }

    private fun start(allowed: Set<String> = emptySet(), rest: Boolean = false) {
        val now = System.currentTimeMillis()
        Assert.assertTrue(app.focus.start(FocusPlan("验证专注流", "一组", 10, 3, 1), allowed, emptySet(), if (rest) now - 181_000 else now))
        main { FocusService.start(app) }
    }

    private fun assertFocusGate() {
        Assert.assertTrue(device.wait(Until.hasObject(By.text("专注中")), 15_000))
        Assert.assertNotNull(device.findObject(By.text("回到专注页")))
        Assert.assertNull(device.findObject(By.text("打开「入口测试应用」之前")))
        Assert.assertNull(device.findObject(By.text("进去")))
    }

    @Test fun pastedSixLinePlanCanStartFromTheActualPlanScreen() = runBlocking {
        val activity = activity("focus-plan")
        try {
            connectGate()
            main {
                app.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("计划", """
                    【任务】粘贴计划验证
                    【目标】一组
                    【总时长】10 分钟
                    【每组】3 分钟
                    【休息】1 分钟
                    【允许的应用】
                """.trimIndent()))
            }
            eventually { device.findObject(By.text("粘贴计划"))?.parent?.isEnabled == true }
            find(By.text("粘贴计划")).click()
            repeat(6) { device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .8f) }
            find(By.text("开始")).click()
            eventually { app.focus.state.value?.plan?.task == "粘贴计划验证" }
            Assert.assertTrue(device.wait(Until.hasObject(By.text("工作")), 5000))
            RuntimeEvidence.capture("focus-pasted-plan-started")
        } finally { activity.close() }
    }

    @Test fun focusBlocksFixtureWithoutIssuingQuestionOrPass() = runBlocking {
        val activity = activity()
        try {
            connectGate(); start(); fixture(); assertFocusGate()
            Assert.assertNull(app.database.dao().activePass("专注测试", System.currentTimeMillis()))
            RuntimeEvidence.capture("focus-blocks-without-quiz")
        } finally { activity.close() }
    }

    @Test fun validTollPassStillBlocksDuringRest() = runBlocking {
        val activity = activity()
        try {
            connectGate()
            val now = System.currentTimeMillis()
            app.database.dao().insertPass(Pass(packageGroup = "专注测试", startAt = now, endAt = now + 900_000, roundId = -1))
            start(rest = true); fixture(); assertFocusGate()
            Assert.assertEquals(PhaseKind.REST, app.focus.state.value!!.phase(System.currentTimeMillis()).kind)
            RuntimeEvidence.capture("focus-rest-ignores-toll-pass")
        } finally { activity.close() }
    }

    @Test fun allowedFixtureBypassesEvenTheOrdinaryTollGate() = runBlocking {
        val activity = activity()
        try {
            connectGate(); start(setOf(TARGET)); fixture()
            eventually { GateService.foreground == TARGET }
            delay(3500)
            Assert.assertFalse(GateActivity.isShowing)
            Assert.assertTrue(device.hasObject(By.pkg(TARGET)))
            RuntimeEvidence.capture("focus-allowed-app")
        } finally { activity.close() }
    }

    @Test fun leavingQuitPageClearsTheWaitAndReturningStartsAgain() = runBlocking {
        val activity = activity("focus")
        try {
            connectGate(); start()
            fun requestQuit() {
                repeat(4) {
                    if (device.findObject(By.text("提前结束"))?.visibleBounds?.height()?.let { h -> h > 0 } != true)
                        device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .7f)
                }
                find(By.text("提前结束")).click()
            }
            requestQuit()
            Assert.assertTrue(device.wait(Until.hasObject(By.text("提前结束 · 等待中")), 5000))
            eventually { app.focus.state.value?.quitAskedAt != null }
            app.focus.update { it.copy(quitAskedAt = System.currentTimeMillis() - 120_000) }
            device.pressHome()
            eventually { app.focus.state.value?.quitAskedAt == null }
            main { app.startActivity(Intent(app, com.yishou.app.MainActivity::class.java).putExtra(com.yishou.app.MainActivity.EXTRA_ROUTE, "focus").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            eventually { app.focus.state.value?.quitAskedAt != null || device.hasObject(By.text("提前结束")) }
            if (app.focus.state.value?.quitAskedAt == null) requestQuit()
            eventually { app.focus.state.value?.quitAskedAt != null }
            Assert.assertTrue(FocusEngine.quitRemainingMs(app.focus.state.value!!, System.currentTimeMillis())!! > 170_000)
            Assert.assertNull(device.findObject(By.text("结束")))
            RuntimeEvidence.capture("focus-quit-wait-reset")
        } finally { activity.close() }
    }

    @Test fun closingTheMainActivityKeepsFocusAndInterceptionAlive() = runBlocking {
        val activity = activity()
        connectGate(); start(); activity.close()
        fixture(); assertFocusGate()
        Assert.assertNotNull(app.focus.active())
        RuntimeEvidence.capture("focus-continues-after-ui-close")
    }
}
