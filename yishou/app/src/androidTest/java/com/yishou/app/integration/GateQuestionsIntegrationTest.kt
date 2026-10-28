package com.yishou.app.integration

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.yishou.app.data.*
import com.yishou.app.gate.*
import com.yishou.app.integration.RuntimeSupport.app
import com.yishou.app.integration.RuntimeSupport.device
import com.yishou.app.integration.RuntimeSupport.eventually
import com.yishou.app.integration.RuntimeSupport.main
import com.yishou.app.integration.RuntimeSupport.reset
import com.yishou.app.settings.AppGroup
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.*
import org.junit.runner.RunWith

/**
 * 用户报告：打开关注的娱乐应用，入口弹出来了却没有题（“暂时没有能继续的题”）。
 * 原因：入口只认写了【要点】的题，也不再现场出题；旧题库没有【要点】，题就没了。
 * 这里按用户的真实情况测：真的无障碍服务、真的外部应用、页面上要真的出现题和作答按钮。
 */
@RunWith(AndroidJUnit4::class)
class GateQuestionsIntegrationTest {
    @get:Rule val evidence = RuntimeEvidence()
    @Before fun before() = runBlocking { reset() }
    @After fun after() {
        device.executeShellCommand("settings put secure enabled_accessibility_services ''")
        device.executeShellCommand("settings put secure accessibility_enabled 0")
        app.settings.saveLlm(RuntimeSupport.config(""))
        java.io.File(app.filesDir, "gate_fresh.json").delete()
        device.pressHome()
    }

    private val generated = """
        【题目】周六上午十点的飞机，地铁到机场要四十分钟，打车二十五分钟但早上常堵，几点出门、怎么去？
        【要点】
        1. 把最坏情况下的路上用时算进去，再倒推出门的钟点 | 最坏的时候要多久
        2. 比较误机的代价和多花的车费，说出取舍 | 两边各丢什么
        【参考】按堵车最坏一小时算，八点前出门坐地铁更稳。
    """.trimIndent()

    private val judged = "[P1] 命中 | 「按最堵的一个小时算，再留四十分钟值机」\n[P2] 命中 | 「误机要改签几百块，比打车贵得多」\n[跑题] 否\n[一句话] 两点都说到了"

    /** 判卷请求回判卷结果，其余（出题）回一道题 */
    private fun server() = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                RuntimeSupport.completion(if (request.body.readUtf8().contains("判卷员")) judged else generated)
        }
        start()
    }

    private suspend fun connectGate() {
        val component = "${app.packageName}/com.yishou.app.gate.GateService"
        device.executeShellCommand("settings put secure enabled_accessibility_services $component")
        device.executeShellCommand("settings put secure accessibility_enabled 1")
        eventually { GateService.connected.value }
        app.settings.updateApp { it.copy(groups = listOf(AppGroup("联通娱乐", 15)), watched = mapOf(RuntimeSupport.TARGET to "联通娱乐")) }
    }

    /** 题卡页上要看到作答按钮，而不是“没有题” */
    private fun assertQuestionOnScreen(what: String) {
        Assert.assertTrue("$what：入口没有弹出", device.wait(Until.hasObject(By.text("打开「入口测试应用」之前")), 15_000))
        Assert.assertTrue("$what：入口弹出了但没有题", device.wait(Until.hasObject(By.textStartsWith("交这一手")), 20_000))
        Assert.assertFalse("$what：显示了“没有题”", device.hasObject(By.textStartsWith("暂时没有能继续的题")))
    }

    @Test fun oldBankCardWithoutPointsStillGivesAQuestion() = runBlocking {
        val server = server()
        val activity = RuntimeSupport.activity()
        try {
            app.settings.saveLlm(RuntimeSupport.config(server.url("/v1").toString()))
            // 旧题包：有【解析】，没有【要点】
            val old = RuntimeSupport.card.copy(points = emptyList(),
                explain = "身份核验是进入站点的前提条件。没有通过核验，就算在开放时间内到达也进不去。")
            app.database.cards().insertCard(CardRow(skill = old.skill.name, difficulty = 1, json = old.toJson(), verified = true,
                source = CardSource.BANK, createdAt = System.currentTimeMillis()))
            connectGate()
            RuntimeSupport.fixture()
            assertQuestionOnScreen("旧题库")
            Assert.assertTrue(device.hasObject(By.textContains("参观者必须先完成哪项操作")))
            RuntimeEvidence.capture("gate-old-bank-question")
            RuntimeSupport.find(By.text("先回桌面")).click()
            eventually { !GateActivity.isShowing }
        } finally { activity.close(); server.shutdown() }
    }

    @Test fun emptyBankStillGivesAQuestionMadeOnTheSpot() = runBlocking {
        val server = server()
        val activity = RuntimeSupport.activity()
        try {
            app.settings.saveLlm(RuntimeSupport.config(server.url("/v1").toString()))
            connectGate()
            RuntimeSupport.fixture()
            assertQuestionOnScreen("题库是空的")
            Assert.assertTrue(device.hasObject(By.textContains("几点出门、怎么去")))
            RuntimeEvidence.capture("gate-fresh-question")
            RuntimeSupport.find(By.text("先回桌面")).click()
            eventually { !GateActivity.isShowing }
            // 后台备好了下一道，下次打开不用等
            eventually(20_000) { java.io.File(app.filesDir, "gate_fresh.json").let { it.exists() && it.readText().contains("几点出门") } }
        } finally { activity.close(); server.shutdown() }
    }

    @Test fun freshQuestionStillNeedsRealAnswerAndTriggerBeforeAPass() = runBlocking {
        val server = server()
        val owner = ViewModelStore()
        val activity = RuntimeSupport.activity()
        try {
            app.settings.saveLlm(RuntimeSupport.config(server.url("/v1").toString()))
            lateinit var vm: GateViewModel
            main { vm = GateViewModel(app); owner.put("gate", vm); vm.start(RuntimeSupport.TARGET, "联通娱乐", "入口测试应用") }
            eventually(20_000) { !vm.state.value.loading && !vm.state.value.busy && vm.probe != null }
            Assert.assertEquals(com.yishou.app.reflex.Main.FRESH, vm.state.value.mode)
            main { vm.showProbe(vm.probe!!) }
            delay(3_200)
            // 糊弄：本机就挡下，不放行
            main { "因为一手".let { a -> for (n in 1..a.length) vm.editProbe(a.take(n)) }; vm.submitProbe() }
            eventually { !vm.state.value.busy }
            Assert.assertNull(vm.review)
            Assert.assertNull(app.database.dao().activePass("联通娱乐", System.currentTimeMillis()))
            // 认真答：过了还要存触发器才放行
            val answer = "按最堵的一个小时算，再留四十分钟值机，八点前出门坐地铁；误机要改签几百块，比打车贵得多。"
            main { for (n in 1..answer.length) vm.editProbe(answer.take(n)); vm.submitProbe() }
            eventually(20_000) { !vm.state.value.busy && vm.review != null }
            Assert.assertTrue("${vm.review?.verdict}", vm.review!!.verdict.passed)
            main { vm.pass() }
            eventually { !vm.state.value.busy }
            Assert.assertNull(app.database.dao().activePass("联通娱乐", System.currentTimeMillis()))
            main { vm.saveTrigger("常堵", "先按最堵的时候算路上用时") }
            eventually { !vm.state.value.busy && vm.triggerSaved }
            main { vm.pass() }
            eventually { vm.state.value.passMinutes == 15 }
            Assert.assertNotNull(app.database.dao().activePass("联通娱乐", System.currentTimeMillis()))
        } finally { main { owner.clear() }; activity.close(); server.shutdown() }
    }
}
