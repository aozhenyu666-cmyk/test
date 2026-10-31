package com.yishou.app.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.yishou.app.gate.*
import com.yishou.app.integration.RuntimeSupport.app
import com.yishou.app.integration.RuntimeSupport.device
import com.yishou.app.integration.RuntimeSupport.eventually
import com.yishou.app.integration.RuntimeSupport.reset
import com.yishou.app.settings.AppGroup
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith

/**
 * 用户报告：真手机上打开 B 站、抖音照样能刷。模拟器上拦得住，真手机上拦不住，
 * 最常见的原因是国产系统没开“后台弹出界面”：弹出入口页时系统不报错，只是不显示。
 * 这里假装系统这样拦了一次，确认一手会改用遮罩挡住，并把经过写进拦截日志。
 */
@RunWith(AndroidJUnit4::class)
class GateBlockedIntegrationTest {
    @get:Rule val evidence = RuntimeEvidence()
    @Before fun before() = runBlocking { reset(); GateTrace.init(app); GateTrace.clear() }
    @After fun after() {
        GateService.pretendBlockedForTest = false
        device.executeShellCommand("settings put secure enabled_accessibility_services ''")
        device.executeShellCommand("settings put secure accessibility_enabled 0")
        device.pressHome()
    }

    private suspend fun connectGate() {
        val component = "${app.packageName}/com.yishou.app.gate.GateService"
        device.executeShellCommand("settings put secure enabled_accessibility_services $component")
        device.executeShellCommand("settings put secure accessibility_enabled 1")
        eventually { GateService.connected.value }
        app.settings.updateApp { it.copy(groups = listOf(AppGroup("联通娱乐", 15)), watched = mapOf(RuntimeSupport.TARGET to "联通娱乐")) }
    }

    @Test fun whenTheSystemSilentlyBlocksTheGateACoverStillStopsTheApp() = runBlocking {
        val activity = RuntimeSupport.activity()
        try {
            connectGate()
            GateService.pretendBlockedForTest = true
            RuntimeSupport.fixture()
            Assert.assertTrue("遮罩没有盖上", device.wait(Until.hasObject(By.text("打开「入口测试应用」之前，先做一道题")), 15_000))
            RuntimeEvidence.capture("gate-cover-when-launch-blocked")
            val log = GateTrace.export()
            Assert.assertTrue(log, log.contains("切到前台：入口测试应用"))
            Assert.assertTrue(log, log.contains("拦下：弹出入口页"))
            Assert.assertTrue(log, log.contains("入口页没出来"))
            Assert.assertTrue(log, log.contains("遮罩已盖上"))
            Assert.assertNull(app.database.dao().activePass("联通娱乐", System.currentTimeMillis()))
            // 系统放行以后，从遮罩点“去做题”能打开真正的入口页，遮罩收起
            GateService.pretendBlockedForTest = false
            RuntimeSupport.find(By.text("去做题")).click()
            Assert.assertTrue("入口页没打开", device.wait(Until.hasObject(By.text("打开「入口测试应用」之前")), 15_000))
            eventually { !device.hasObject(By.text("打开「入口测试应用」之前，先做一道题")) }
            Assert.assertTrue(GateTrace.export().contains("入口页显示出来了"))
            RuntimeSupport.find(By.text("先回桌面")).click()
            eventually { !GateActivity.isShowing }
        } finally { activity.close() }
    }

    @Test fun coverGoesAwayWhenGoingHome() = runBlocking {
        val activity = RuntimeSupport.activity()
        try {
            connectGate()
            GateService.pretendBlockedForTest = true
            RuntimeSupport.fixture()
            Assert.assertTrue(device.wait(Until.hasObject(By.text("打开「入口测试应用」之前，先做一道题")), 15_000))
            RuntimeSupport.find(By.text("回桌面")).click()
            eventually { !device.hasObject(By.text("打开「入口测试应用」之前，先做一道题")) }
            Assert.assertTrue(GateTrace.export().contains("遮罩收起"))
        } finally { activity.close() }
    }

    @Test fun traceSaysWhyAnUnwatchedAppIsNotStopped() = runBlocking {
        val activity = RuntimeSupport.activity()
        try {
            connectGate()
            app.settings.updateApp { it.copy(watched = emptyMap()) }
            RuntimeSupport.fixture()
            eventually { GateTrace.export().contains("不拦：不在关注名单") }
            Assert.assertFalse(GateActivity.isShowing)
        } finally { activity.close() }
    }
}
