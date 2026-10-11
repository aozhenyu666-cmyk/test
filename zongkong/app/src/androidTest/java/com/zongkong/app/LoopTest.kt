package com.zongkong.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zongkong.app.ui.MainActivity
import com.zongkong.core.ApiResult
import com.zongkong.core.Config
import com.zongkong.core.NotionClient
import com.zongkong.core.NotionConfig
import com.zongkong.core.NotionSim
import com.zongkong.core.work.F
import com.zongkong.core.work.NotionMap
import com.zongkong.core.work.Work
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 端到端：在模拟器上用真实界面走一遍完整闭环，Notion 用本机模拟服务（行为同真 Notion API）。
 *
 *  GPT 制定任务写进 Notion → 总控拉取并显示 → 用户开始、做完、写结果 → 结果写回 Notion
 *  → GPT 读到结果、写新判断和新行动 → 总控拉取，“查看结果”里显示 GPT 的更新
 *  另外：GPT 不能写 Notion 时，交接块分享给总控 → 预览 → 导入 → 写进 Notion
 *
 * 每一步截图存到 /sdcard/Android/data/com.zongkong.app/files/shots/。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LoopTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var sim: NotionSim
    private lateinit var app: ZkApp
    private lateinit var gpt: NotionClient
    private var scenario: ActivityScenario<MainActivity>? = null
    private val zone = ZoneId.systemDefault()
    private val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    @Before
    fun setUp() {
        sim = NotionSim().start()
        app = ApplicationProvider.getApplicationContext()
        app.store.updateWork { Work() }
        app.store.updateConfig {
            Config(onboarded = true, silenceHours = 0, dailyQuotaMin = 0, notion = NotionConfig(token = "secret_test", baseUrl = sim.baseUrl))
        }
        gpt = NotionClient({ "secret_gpt" }, base = sim.baseUrl)
        val msg = runBlocking { app.actions.work.setup(sim.rootPage) }
        assertTrue(msg, app.store.config.value.notion.workReady)
    }

    @After
    fun tearDown() {
        scenario?.close()
        sim.shutdown()
    }

    private fun shot(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(400)
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(app.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun gptWrite(db: String, fields: Map<String, String>, threadId: String? = null): String = runBlocking {
        val schema = (gpt.schema(db) as ApiResult.Ok).value.props
        val all = fields + (F.SOURCE to "GPT") + (if (threadId != null) mapOf(F.THREAD to "x") else emptyMap())
        val p = NotionMap.toProps(all, all.keys, schema) { threadId }
        NotionMap.fromPage((gpt.createPageObject(db, p.json, JsonArray(emptyList())) as ApiResult.Ok).value).id
    }

    private fun gptUpdate(pageId: String, db: String, fields: Map<String, String>) = runBlocking {
        val schema = (gpt.schema(db) as ApiResult.Ok).value.props
        val all = fields + (F.SOURCE to "GPT")
        val p = NotionMap.toProps(all, all.keys, schema) { null }
        assertTrue(gpt.updatePage(pageId, p.json) is ApiResult.Ok)
    }

    /** 让动画走完（测试时钟只在等待时前进）。 */
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
    }

    private fun waitText(text: String, timeout: Long = 10_000) = waitFor(hasText(text, substring = true), timeout, "文字“$text”")

    /** 等不到时把当时的状态带进失败信息：本机记录、同步结果、屏幕上的内容。 */
    private fun waitFor(m: androidx.compose.ui.test.SemanticsMatcher, timeout: Long, what: String) {
        try {
            compose.waitUntilAtLeastOneExists(m, timeout)
        } catch (e: Throwable) {
            shot("fail-${System.currentTimeMillis()}")
            val w = app.store.work.value
            val recs = w.recs.joinToString("\n") { "  ${it.kind} ${it.title} [${it[F.STATUS]}] plan=${it[F.PLAN]} thread=${it.threadKey} sync=${it.sync} ${it.syncError}" }
            val tree = runCatching { compose.onRoot(useUnmergedTree = false).printToString(maxDepth = 30) }.getOrDefault("?")
            throw AssertionError(
                "等不到$what。\n同步：ok=${w.lastSyncOk} ${w.lastSyncMessage} at=${w.lastSyncAt}\n记录：\n$recs\n" +
                    "当前：${com.zongkong.core.work.Focus.current(w, System.currentTimeMillis(), zone).let { "${it.why} ${it.action?.title}" }}\n屏幕：\n${tree.take(4000)}",
                e,
            )
        }
    }

    @Test
    fun fullLoop() {
        val dbs = app.store.config.value.notion
        // 1. GPT（在 ChatGPT 里通过 Notion 连接）定下事项和一个行动
        val now = ZonedDateTime.now(zone).withSecond(0).withNano(0)
        val threadId = gptWrite(dbs.threadsDb, mapOf(
            F.TITLE to "改简历并投 3 家", F.STATUS to "待行动", F.WHY to "下周要投递",
            F.JUDGE to "项目经历太虚，先重写第二段", F.NEXT to "重写项目经历第二段",
        ))
        val actionId = gptWrite(dbs.actionsDb, mapOf(
            F.TITLE to "重写项目经历第二段", F.STATUS to "待做", F.CRITERIA to "用 STAR 写完并读一遍",
            F.PLAN to "${now.minusMinutes(5).format(fmt)}|${now.plusMinutes(55).format(fmt)}",
        ), threadId)

        // 2. 打开总控，同步，首页任务卡显示这一步
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasTestTag("sync-now"), 15_000)
        compose.onNodeWithTag("sync-now").performScrollTo().performClick()
        waitFor(hasTestTag("task-title") and hasText("重写项目经历第二段"), 15_000, "首页任务卡")
        waitText("用 STAR 写完并读一遍")
        shot("e2e-01-home-task-from-gpt")
        val code = app.store.work.value.byNotion(threadId)!![F.CODE]
        assertTrue(code.startsWith("ZK-"))
        assertEquals(code, sim.text(threadId, F.CODE)) // 编号写回了 Notion

        // 3. 去 GPT：带上前情
        compose.onNodeWithTag("task-gpt").performClick()
        waitText("项目经历太虚，先重写第二段")
        waitText(code)
        shot("e2e-02-gpt-context")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { }
        androidx.test.espresso.Espresso.pressBack()

        // 4. 开始 → 计时 → 做完 → 写结果
        compose.onNodeWithTag("task-start").performClick()
        compose.onNodeWithTag("start-confirm").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("session-done"), 10_000)
        shot("e2e-03-session")
        compose.onNodeWithTag("session-done").performScrollTo().performClick()
        compose.onNodeWithTag("result-field").performTextInput("写完了，用了 STAR，数据还缺两个")
        compose.onNodeWithTag("next-field").performTextInput("找项目数据")
        compose.onNodeWithTag("complete-save").performScrollTo().performClick()
        waitText("结果已记下")
        shot("e2e-04-result-saved")

        // 5. 结果写回 Notion
        val r = runBlocking { app.actions.work.sync() }
        assertTrue(r, app.store.work.value.lastSyncOk)
        assertEquals("完成", sim.text(actionId, F.STATUS))
        assertTrue(sim.text(actionId, F.RESULT).contains("数据还缺两个"))
        assertEquals("找项目数据", sim.text(threadId, F.NEXT))
        assertTrue(sim.text(threadId, F.BREAK).contains("重写项目经历第二段"))
        val resultNote = sim.pagesIn(dbs.notesDb).first { sim.text(it.id, F.TYPE) == "结果" }
        assertEquals(threadId, sim.text(resultNote.id, F.THREAD))

        // 6. GPT 读到结果（从 Notion），写新判断和新行动
        assertTrue(sim.text(actionId, F.RESULT).isNotBlank())
        gptUpdate(threadId, dbs.threadsDb, mapOf(F.JUDGE to "第二段可以了，缺的数据从周报里找", F.STATUS to "推进中"))
        val tomorrow = now.plusDays(1).withHour(20).withMinute(0)
        gptWrite(dbs.actionsDb, mapOf(
            F.TITLE to "从周报里补两个数据", F.STATUS to "待做", F.CRITERIA to "两个数字写进第二段",
            F.PLAN to "${tomorrow.format(fmt)}|${tomorrow.plusHours(1).format(fmt)}",
        ), threadId)

        // 7. 总控拉取，“查看结果”显示 GPT 的更新；首页接下来有新行动
        compose.onNode(hasText("回首页")).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("sync-now"), 10_000)
        compose.onNodeWithTag("sync-now").performScrollTo().performClick()
        waitText("从周报里补两个数据", 15_000)
        shot("e2e-05-home-after-gpt")
        assertEquals("第二段可以了，缺的数据从周报里找", app.store.work.value.byNotion(threadId)!![F.JUDGE])
        assertTrue(app.store.work.value.changes.any { it.by == "GPT" && it.what.startsWith("当前判断") })
    }

    @Test
    fun handoffBlockImport() {
        val dbs = app.store.config.value.notion
        val block = """
            讨论完了，交接如下：
            【总控交接】
            事项：国考行测提到 75 分
            为什么：11 月底笔试
            当前判断：资料分析是最大短板，先练增长率
            不确定：要不要报班
            行动：
            - 增长率专项 20 题 | 明天 20:00-21:00 | 完成依据：正确率记录
            - 整理错题 | 周六 10:00 | 错题本拍照
            问题：
            - 报班还是自学
            【交接结束】
        """.trimIndent()
        val intent = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, block)
        scenario = ActivityScenario.launch<MainActivity>(intent)
        waitText("新建事项「国考行测提到 75 分」")
        waitText("增长率专项 20 题")
        shot("e2e-06-import-preview")
        compose.onNodeWithTag("import-confirm").performScrollTo().performClick()
        waitText("资料分析是最大短板")
        shot("e2e-07-thread-after-import")
        val r = runBlocking { app.actions.work.sync() }
        assertTrue(r, app.store.work.value.lastSyncOk)
        val threads = sim.pagesIn(dbs.threadsDb)
        assertEquals(1, threads.size)
        assertEquals("国考行测提到 75 分", sim.text(threads[0].id, F.TITLE))
        val actions = sim.pagesIn(dbs.actionsDb)
        assertEquals(2, actions.size)
        assertTrue(actions.all { sim.text(it.id, F.THREAD) == threads[0].id })
        assertTrue(sim.pagesIn(dbs.notesDb).any { sim.text(it.id, F.TYPE) == "结论" })
    }

    /** 模拟 AI 接口：按顺序回放工具调用，最后一句话。 */
    private fun mockLlm(vararg bodies: String): okhttp3.mockwebserver.MockWebServer {
        val server = okhttp3.mockwebserver.MockWebServer()
        bodies.forEach { server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(it)) }
        server.start()
        return server
    }

    private fun toolTurn(vararg calls: Pair<String, String>): String {
        val arr = calls.mapIndexed { i, (name, args) ->
            val escaped = args.replace("\\", "\\\\").replace("\"", "\\\"")
            """{"id":"call_${name}_$i","type":"function","function":{"name":"$name","arguments":"$escaped"}}"""
        }.joinToString(",")
        return """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[$arr]}}]}"""
    }

    private fun sayTurn(text: String): String {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"choices":[{"message":{"role":"assistant","content":"$escaped"}}]}"""
    }

    /**
     * 参谋：一句话进去，它调用工具建事项、排行动、加快捷按钮、打开统筹规划部，最后给选项按钮。
     */
    @Test
    fun agentLoop() {
        val llm = mockLlm(
            toolTurn("create_thread" to """{"title":"准备周五一面","why":"周五上午一面","next":"列 5 个常见问题"}"""),
            toolTurn("add_action" to """{"thread":"准备周五一面","title":"列 5 个常见问题并写要点","time":"明天 20:00-20:45","criteria":"5 个问题各有 3 条要点"}"""),
            toolTurn(
                "add_shortcut" to """{"label":"面试复盘","kind":"ask","payload":"帮我复盘今天的面试准备","dept":"统筹规划部"}""",
                "open_page" to """{"page":"dept","target":"统筹规划部"}""",
            ),
            sayTurn("排好了：明天 20:00 列 5 个常见问题，做到每个问题 3 条要点。统筹规划部加了一个“面试复盘”按钮。\n【选项】现在就开始｜明天再说"),
        )
        app.store.updateConfig { it.copy(ai = com.zongkong.core.AiConfig(llm.url("/v1").toString(), "sk-test", "mock-model")) }
        app.chat.clear()
        val intent = Intent(app, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, "chat")
        scenario = ActivityScenario.launch<MainActivity>(intent)
        waitFor(hasTestTag("chat-input"), 10_000, "参谋输入框")
        shot("e2e-08-chat-empty")
        compose.onNodeWithTag("chat-input").performTextInput("周五一面，帮我把准备排到明晚八点")
        compose.onNodeWithTag("chat-send").performClick()
        // 说完以后自动跳到统筹规划部
        waitText("今天的时间线", 20_000)
        waitText("面试复盘", 5_000)
        settle()
        shot("e2e-10-dept-plan-after-agent")
        val w = app.store.work.value
        val t = w.threads().single { it.title == "准备周五一面" }
        val a = w.actionsOf(t.key).single()
        assertEquals("5 个问题各有 3 条要点", a[F.CRITERIA])
        assertTrue(a[F.PLAN], a[F.PLAN].contains("T20:00"))
        assertTrue(app.store.config.value.shortcuts.any { it.label == "面试复盘" && it.dept == com.zongkong.core.Dept.PLAN })
        // 回到对话：工具步骤折叠显示、选项按钮
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitText("现在就开始", 10_000)
        waitText("已执行", 5_000)
        shot("e2e-09-chat-after-tools")
        // 模型收到了工具定义和工具结果
        val first = llm.takeRequest().body.readUtf8()
        assertTrue(first.contains("\"create_thread\""))
        llm.takeRequest()
        val third = llm.takeRequest().body.readUtf8()
        assertTrue(third, third.contains("\"tool_call_id\""))
        llm.shutdown()
    }

    /** 晨间部署向导：新的一件事 → 第一步 → 时间 → 完成依据 → 盖章。 */
    @Test
    fun planWizard() {
        val intent = Intent(app, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, "wizard/plan")
        scenario = ActivityScenario.launch<MainActivity>(intent)
        waitFor(hasTestTag("wiz-new-thread"), 10_000, "向导第一步")
        shot("e2e-11-wizard-1")
        compose.onNodeWithTag("wiz-new-thread").performTextInput("整理论文文献")
        compose.onNode(hasText("下一步")).performScrollTo().performClick()
        waitFor(hasTestTag("wiz-title"), 5_000, "向导第二步")
        compose.onNodeWithTag("wiz-title").performTextInput("读完两篇综述并标重点")
        shot("e2e-12-wizard-2")
        compose.onNode(hasText("下一步")).performScrollTo().performClick()
        waitText("明早 9:00", 5_000)
        shot("e2e-13-wizard-3")
        compose.onNode(hasText("明早 9:00")).performScrollTo().performClick()
        waitFor(hasTestTag("wiz-criteria"), 5_000, "向导第四步")
        compose.onNodeWithTag("wiz-criteria").performTextInput("两篇各写 3 条笔记")
        compose.onNodeWithTag("wiz-save").performScrollTo().performClick()
        waitText("已部署", 5_000)
        settle()
        shot("e2e-14-wizard-done")
        val w = app.store.work.value
        val t = w.threads().single { it.title == "整理论文文献" }
        val a = w.actionsOf(t.key).single()
        assertEquals("读完两篇综述并标重点", a.title)
        assertEquals("两篇各写 3 条笔记", a[F.CRITERIA])
        assertTrue(a[F.PLAN], a[F.PLAN].contains("T09:00"))
    }

    /** 新首页和四个部门页、复盘向导：截图看样子。 */
    @Test
    fun screensTour() {
        val now = ZonedDateTime.now(zone).withSecond(0).withNano(0)
        app.store.updateWork { w0 ->
            var w = w0
            val (w1, t) = com.zongkong.core.work.WorkOps.create(w, com.zongkong.core.work.Kind.THREAD, mapOf(F.TITLE to "国考行测提到 75 分", F.WHY to "11 月底笔试", F.JUDGE to "资料分析最弱", F.NEXT to "增长率专项 20 题"), System.currentTimeMillis(), kotlin.random.Random(1))
            w = w1
            w = com.zongkong.core.work.WorkOps.create(w, com.zongkong.core.work.Kind.ACTION, mapOf(F.TITLE to "增长率专项 20 题", F.THREAD to t.key, F.CRITERIA to "正确率记下来", F.PLAN to "${now.plusMinutes(30).format(fmt)}|${now.plusMinutes(90).format(fmt)}"), System.currentTimeMillis(), kotlin.random.Random(2)).first
            w = com.zongkong.core.work.WorkOps.create(w, com.zongkong.core.work.Kind.NOTE, mapOf(F.TITLE to "粉笔的增长率口诀", F.TYPE to "材料", F.THREAD to t.key), System.currentTimeMillis(), kotlin.random.Random(3)).first
            w = com.zongkong.core.work.WorkOps.create(w, com.zongkong.core.work.Kind.THREAD, mapOf(F.TITLE to "要不要报班", F.STATUS to "思考中", F.UNSURE to "自学够不够"), System.currentTimeMillis(), kotlin.random.Random(4)).first
            w
        }
        val routes = listOf("home" to "e2e-20-today", "depts" to "e2e-21-depts", "dept/INFO" to "e2e-22-dept-info", "dept/THINK" to "e2e-23-dept-think",
            "dept/PLAN" to "e2e-24-dept-plan", "dept/ACT" to "e2e-25-dept-act", "wizard/review" to "e2e-26-review", "stuckpick" to "e2e-27-stuck", "settings" to "e2e-28-me")
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        routes.forEach { (route, name) ->
            scenario!!.onActivity { it.startActivity(Intent(it, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, route).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            settle()
            shot(name)
        }
        waitFor(hasTestTag("tab-home"), 5_000, "底部标签")
    }

    /**
     * 两个 App 真的连起来：总控读一手的状态、把一步行动交给一手，再读回来对上。
     * 一手没装（本地跑测试时）就跳过。
     */
    @Test
    fun yishouLink() = runBlocking {
        org.junit.Assume.assumeTrue("一手没装", com.zongkong.app.link.YishouClient.installed(app))
        val before = com.zongkong.app.link.YishouClient.status(app)
        assertTrue(before.toString(), before is com.zongkong.app.link.YishouClient.Result.Ok)
        // 建一步行动，交给一手
        val tk = app.actions.work.createThread("行测资料分析", why = "国考")
        val (ak, err) = app.actions.work.addAction(tk, "增长率 10 题", "", "正确率 70% 以上")
        assertTrue(err ?: "", ak != null)
        val order = com.zongkong.core.Link.taskOrder(app.store.work.value, ak!!)!!
        val r = com.zongkong.app.link.YishouClient.setTask(app, order)
        assertTrue(r.toString(), r is com.zongkong.app.link.YishouClient.Result.Ok)
        val after = (com.zongkong.app.link.YishouClient.status(app) as com.zongkong.app.link.YishouClient.Result.Ok).value
        assertEquals("增长率 10 题", after.taskTitle)
        assertEquals("正确率 70% 以上", after.taskGoal)
        assertEquals(ak, after.actionKey)
        // 再交一次：不重复新建
        val again = com.zongkong.app.link.YishouClient.setTask(app, order)
        assertEquals("已经是一手的当前任务", (again as com.zongkong.app.link.YishouClient.Result.Ok).value)
        // 行为管理部页面显示一手的状态
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, "dept/ACT"))
        waitText("在练：增长率 10 题", 10_000)
        settle()
        shot("e2e-15-dept-act-linked")
    }
}
