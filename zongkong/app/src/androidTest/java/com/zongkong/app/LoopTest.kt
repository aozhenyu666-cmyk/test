package com.zongkong.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
            Config(
                onboarded = true, silenceHours = 0, dailyQuotaMin = 0, notion = NotionConfig(token = "secret_test", baseUrl = sim.baseUrl),
                // 老的场景从“今天”页开始；0.14 的对话优先测试自己打开
                startOnChat = false,
                // 测试期间不让每日总控时间、失联升级、参谋主动找人插进来
                hq = com.zongkong.core.hq.HqConfig(enabled = false, escalation = false, proactive = false, vibrate = false),
                // 老的场景不受“挣来的娱乐 / 打开就答题”影响；0.10 的测试自己打开
                earn = com.zongkong.core.EarnConfig(enabled = false),
                watch = com.zongkong.core.WatchConfig(openGate = false),
            )
        }
        // 今天交过的关卡、娱乐时长清零：每个测试从干净的一天开始（不然前一个测试交过的“今日部署”会带过来）
        app.store.updateToday { com.zongkong.core.DayLog(it.date) }
        app.store.updateHq { com.zongkong.core.hq.HqBook() }
        app.store.updateDossier { com.zongkong.core.boss.Dossier() }
        app.store.updateVein { com.zongkong.core.vein.Vein() }
        app.store.updateIntake { com.zongkong.core.info.IntakeBook() }
        app.store.touch()
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

    /** 收起键盘，免得挡住下面的按钮。 */
    private fun hideKeyboard() {
        runCatching { androidx.test.espresso.Espresso.closeSoftKeyboard() }
        settle()
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
        compose.onNodeWithTag("task-gpt").performScrollTo().performClick()
        waitText("项目经历太虚，先重写第二段")
        waitText(code)
        shot("e2e-02-gpt-context")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { }
        androidx.test.espresso.Espresso.pressBack()

        // 4. 开始 → 计时 → 做完 → 写结果
        compose.onNodeWithTag("task-start").performScrollTo().performClick()
        compose.onNodeWithTag("start-confirm").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("session-done"), 10_000)
        shot("e2e-03-session")
        compose.onNodeWithTag("session-done").performScrollTo().performClick()
        compose.onNodeWithTag("result-field").performTextInput("写完了，用了 STAR，数据还缺两个")
        compose.onNodeWithTag("next-field").performTextInput("找项目数据")
        // 键盘会挡住“记下结果”，点之前先收起
        hideKeyboard()
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
        // 工具步骤在最后一条回复上面：对话已经滚到底，先滚回去再找
        compose.onNodeWithTag("chat-list").performScrollToNode(hasText("已执行", substring = true))
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
        hideKeyboard()
        compose.onNode(hasText("下一步")).performScrollTo().performClick()
        waitFor(hasTestTag("wiz-title"), 5_000, "向导第二步")
        compose.onNodeWithTag("wiz-title").performTextInput("读完两篇综述并标重点")
        hideKeyboard()
        shot("e2e-12-wizard-2")
        compose.onNode(hasText("下一步")).performScrollTo().performClick()
        waitText("明早 9:00", 5_000)
        shot("e2e-13-wizard-3")
        compose.onNode(hasText("明早 9:00")).performScrollTo().performClick()
        waitFor(hasTestTag("wiz-criteria"), 5_000, "向导第四步")
        compose.onNodeWithTag("wiz-criteria").performTextInput("两篇各写 3 条笔记")
        hideKeyboard()
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
            "dept/PLAN" to "e2e-24-dept-plan", "plan" to "e2e-29-plan-empty", "dept/ACT" to "e2e-25-dept-act", "wizard/review" to "e2e-26-review", "stuckpick" to "e2e-27-stuck", "settings" to "e2e-28-me")
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        routes.forEach { (route, name) ->
            scenario!!.onActivity { it.startActivity(Intent(it, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, route).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            settle()
            shot(name)
        }
        waitFor(hasText("今天"), 5_000, "底部标签")
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
        // 总控让一手开一局陪练窗口
        val w = com.zongkong.app.link.YishouClient.startWindow(app)
        assertTrue(w.toString(), w is com.zongkong.app.link.YishouClient.Result.Ok)
        assertTrue((com.zongkong.app.link.YishouClient.status(app) as com.zongkong.app.link.YishouClient.Result.Ok).value.windowActive)
        // 再交一次：不重复新建
        val again = com.zongkong.app.link.YishouClient.setTask(app, order)
        assertEquals("已经是一手的当前任务", (again as com.zongkong.app.link.YishouClient.Result.Ok).value)
        // 行为管理部页面显示一手的状态
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, "dept/ACT"))
        waitText("在练：增长率 10 题", 10_000)
        settle()
        shot("e2e-15-dept-act-linked")
    }

    /**
     * 计划模式：回看 → 选方向 → 交出什么 → 节奏 → 预演 → 立项；立项后事项下有行动，定主线，首页按主线推。
     * 周日定的是下周（那天首页不推）。
     */
    @Test
    fun planMode() {
        val now = System.currentTimeMillis()
        app.store.updatePlans { com.zongkong.core.plan.PlanBook() }
        app.store.updateWork { w0 ->
            com.zongkong.core.work.WorkOps.create(w0, com.zongkong.core.work.Kind.THREAD, mapOf(F.TITLE to "国考行测", F.WHY to "11 月底笔试", F.NEXT to "资料分析专项"), now, kotlin.random.Random(5)).first
        }
        val sunday = com.zongkong.core.DayClock.weekday(now, zone) == 7
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, "plan"))
        waitFor(hasTestTag("plan-start"), 10_000, "计划页")
        settle()
        shot("e2e-30-plan-none")
        compose.onNodeWithTag("plan-start").performScrollTo().performClick()
        waitFor(hasTestTag("pm-begin"), 5_000, "计划模式第一步")
        compose.onNodeWithTag("pm-begin").performScrollTo().performClick()
        waitFor(hasTestTag("pm-goal-0"), 5_000, "选方向")
        compose.onNodeWithTag("pm-goal-0").performScrollTo().performClick()
        settle()
        shot("e2e-31-plan-goals")
        compose.onNodeWithTag("pm-next").performScrollTo().performClick()
        waitFor(hasTestTag("pm-outcome-0"), 5_000, "交出什么")
        compose.onNodeWithTag("pm-outcome-0").performTextInput("资料分析做完 3 套")
        hideKeyboard()
        compose.onNodeWithTag("pm-add-outcome-0").performScrollTo().performClick()
        settle()
        shot("e2e-32-plan-outcomes")
        compose.onNodeWithTag("pm-next").performScrollTo().performClick()
        waitText("1.5 小时", 5_000)
        compose.onNode(hasText("1.5 小时")).performScrollTo().performClick()
        compose.onNodeWithTag("pm-next").performScrollTo().performClick()
        waitText("晚上刷手机停不下来", 5_000)
        compose.onNode(hasText("晚上刷手机停不下来")).performScrollTo().performClick()
        settle()
        shot("e2e-33-plan-traps")
        compose.onNodeWithTag("pm-next").performScrollTo().performClick()
        waitFor(hasTestTag("pm-approve"), 5_000, "确认")
        shot("e2e-34-plan-confirm")
        compose.onNodeWithTag("pm-approve").performScrollTo().performClick()
        waitText("有参照了", 5_000)
        settle()
        shot("e2e-35-plan-approved")

        val book = app.store.plans.value
        val p = (if (sunday) com.zongkong.core.plan.Plans.nextActive(book, now, zone) else com.zongkong.core.plan.Plans.active(book, now, zone))!!
        assertEquals(1, p.goals.size)
        assertEquals(90, p.dailyMinutes)
        assertEquals("晚上刷手机停不下来", p.ifThens.single().trigger)
        val o = p.outcomes.single()
        val action = app.store.work.value.rec(o.actionKey)!!
        assertEquals("资料分析做完 3 套", action.title)
        assertEquals(p.goals[0].threadKey, action.threadKey)
        // 立项时记了一条“计划”记录（会同步到 Notion）
        assertTrue(app.store.work.value.notes().any { it.noteType == com.zongkong.core.work.NoteType.PLAN })
        if (sunday) return

        // 定今天的主线 → 首页按主线推
        compose.onNode(hasText("定今天的主线")).performScrollTo().performClick()
        waitFor(hasTestTag("plan-main"), 5_000, "计划页主线")
        compose.onAllNodes(hasText("资料分析做完 3 套"))[0].performScrollTo().performClick()
        settle()
        assertEquals(o.actionKey, app.store.mainKey())
        shot("e2e-36-plan-page")
        scenario!!.onActivity { it.startActivity(Intent(it, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, "home").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        waitFor(hasTestTag("task-title") and hasText("资料分析做完 3 套"), 10_000, "首页按主线推")
        waitText("计划内", 5_000)
        settle()
        shot("e2e-37-home-main")
    }

    /** 跳过环节的最短时间（测试里不真的等几分钟）。[whole] 连整段时间一起跳过。 */
    private fun fastForward(whole: Boolean = false) {
        app.store.updateHq { b ->
            val s = b.active ?: return@updateHq b
            b.put(s.copy(stepStartedAt = s.stepStartedAt - 30 * 60_000L, startedAt = if (whole) s.startedAt - 2 * 60 * 60_000L else s.startedAt))
        }
        settle()
    }

    private fun next(expect: String) {
        fastForward()
        waitFor(hasTestTag("hq-next") and androidx.compose.ui.test.isEnabled(), 5_000, "“下一环节”可以点")
        compose.onNodeWithTag("hq-next").performClick()
        waitFor(hasTestTag("hq-step") and hasText(expect, substring = true), 5_000, "环节“$expect”")
        settle()
    }

    /**
     * 总控时间：进入 → 静 → 收（清收集箱）→ 谋（没接 AI：四问自己憋）→ 筹（定明天主线）→ 行（对账）→ 封。
     * 进行中按返回键不走；封存后记一条验收记录、解锁。
     */
    @Test
    fun hqSession() {
        val now = System.currentTimeMillis()
        val thread = app.actions.work.createThread("国考行测", why = "11 月底笔试")
        app.actions.work.addAction(thread, "资料分析专项 1 套", "", "")
        app.actions.work.capture("看到一篇讲资料分析速算的文章", com.zongkong.core.work.NoteType.IDEA, "资料分析速算文章", "", "", false)
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, "hq"))
        waitFor(hasTestTag("hq-start"), 10_000, "总控时间入口")
        settle()
        settle()
        Thread.sleep(800)
        shot("e2e-40-hq-entry")
        compose.onNodeWithTag("hq-start").performScrollTo().performClick()
        waitFor(hasTestTag("hq-step") and hasText("静", substring = true), 5_000, "第一环节")
        assertTrue(app.store.hq.value.active != null)
        assertTrue("总控时间里严管", app.store.status().strict)
        settle()
        shot("e2e-41-hq-settle")
        // 进行中按返回键不离开
        androidx.test.espresso.Espresso.pressBackUnconditionally()
        settle()
        waitFor(hasTestTag("hq-step"), 3_000, "按返回键后还在总控时间")

        next("收")
        waitFor(hasTestTag("triage-card"), 5_000, "收集箱卡片")
        shot("e2e-42-hq-triage")
        compose.onAllNodes(hasText("国考行测"))[0].performScrollTo().performClick()
        waitText("收集箱清空了", 5_000)
        assertEquals(thread, app.store.work.value.notes().first { it.title == "资料分析速算文章" }.threadKey)

        next("谋")
        waitFor(hasTestTag("think-topic-0"), 5_000, "挑一件来想")
        shot("e2e-43-hq-think-pick")
        compose.onNodeWithTag("think-topic-0").performScrollTo().performClick()
        waitFor(hasTestTag("think-first"), 5_000, "四问")
        compose.onNodeWithTag("think-first").performTextInput("资料分析是最容易提分的")
        compose.onNodeWithTag("think-fixed").performTextInput("先把速算练熟，再刷套题")
        compose.onNodeWithTag("think-next").performTextInput("明早做一套资料分析")
        hideKeyboard()
        compose.onNodeWithTag("think-save").performScrollTo().performClick()
        waitText("记下了", 5_000)
        shot("e2e-44-hq-think")
        assertEquals("先把速算练熟，再刷套题", app.store.work.value.rec(thread)!![F.JUDGE])

        next("筹")
        waitFor(hasTestTag("tomorrow-0"), 5_000, "明天的候选")
        compose.onNodeWithTag("tomorrow-0").performScrollTo().performClick()
        compose.onNodeWithTag("tomorrow-save").performScrollTo().performClick()
        settle()
        shot("e2e-45-hq-tomorrow")
        val tomorrow = com.zongkong.core.DayClock.date(now, zone).plusDays(1).toString()
        assertTrue(com.zongkong.core.plan.Plans.day(app.store.plans.value, tomorrow)?.mainActionKey?.isNotBlank() == true)

        next("行")
        compose.onNodeWithTag("ledger-none").performScrollTo().performClick()
        settle()
        shot("e2e-46-hq-ledger")

        next("封")
        settle()
        shot("e2e-47-hq-seal-early")
        fastForward(whole = true)
        waitFor(hasTestTag("hq-seal") and androidx.compose.ui.test.isEnabled(), 5_000, "可以封存")
        compose.onNodeWithTag("hq-seal").performClick()
        waitText("手机解锁了", 15_000)
        settle()
        shot("e2e-48-hq-done")
        assertTrue(app.store.hq.value.active == null)
        val s = app.store.hq.value.sessions.last()
        assertTrue(!s.early && s.endedAt > 0)
        assertTrue(s.notes["INFO"].orEmpty().contains("处理 1 条"))
        assertTrue(app.store.work.value.notes().any { it.noteType == com.zongkong.core.work.NoteType.REVIEW && it[F.RAW].contains("总控时间") })
    }

    /**
     * 总控者不吃“我做了 5 小时饭”：先查时间线（抖音 2 小时），记陈述判“对不上”，记一条约定；
     * 对话页顶上信用掉到 58（审慎），点开档案能看到判定和约定。
     */
    @Test
    fun bossInterrogation() {
        val now = System.currentTimeMillis()
        val douyin = "com.ss.android.ugc.aweme"
        app.store.updateConfig { it.copy(blocked = setOf(douyin)) }
        app.store.logSwitch(now - 6 * 3_600_000L, com.zongkong.core.boss.Timeline.OFF)
        app.store.logSwitch(now - 270 * 60_000L, douyin)
        app.store.logSwitch(now - 150 * 60_000L, com.zongkong.core.boss.Timeline.OFF)
        val llm = mockLlm(
            toolTurn("device_timeline" to """{"from":"5小时前","to":"现在"}"""),
            toolTurn(
                "record_claim" to """{"text":"下午做了 5 小时饭","from":"5小时前","to":"现在","verdict":"对不上","evidence":"这 5 小时里抖音 2 小时，最长一段连续 2 小时"}""",
                "commit" to """{"what":"做饭前先报一句做什么、几点结束","due":"2小时后","check":"看对话记录和时间线"}""",
            ),
            sayTurn("这 5 小时里手机亮了 2 小时，全在抖音上。做饭的时间最多 3 小时。我记成“对不上”，信用 58。下次做饭前先报一句，我记成约定了。\n【选项】知道了｜我要解释"),
        )
        app.store.updateConfig { it.copy(ai = com.zongkong.core.AiConfig(llm.url("/v1").toString(), "sk-test", "mock-model")) }
        app.chat.clear()
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, "chat"))
        waitFor(hasTestTag("chat-input"), 10_000, "总控输入框")
        waitFor(hasTestTag("stance-line") and hasText("信用 70", substring = true), 5_000, "信用 70")
        compose.onNodeWithTag("chat-input").performTextInput("我下午做了5小时饭，也不知道干了什么")
        compose.onNodeWithTag("chat-send").performClick()
        waitText("我要解释", 20_000)
        waitFor(hasTestTag("stance-line") and hasText("信用 58", substring = true), 5_000, "信用掉到 58")
        settle()
        shot("e2e-50-boss-chat")
        // 时间线真的发给了模型：抖音 2 小时
        llm.takeRequest()
        val second = llm.takeRequest().body.readUtf8()
        assertTrue(second, second.contains("娱乐合计 2 小时"))
        val d = app.store.dossier.value
        assertEquals(com.zongkong.core.boss.ClaimVerdict.CONTRADICTED, d.claims.single().verdict)
        assertEquals("做饭前先报一句做什么、几点结束", d.commitments.single().what)
        assertEquals(com.zongkong.core.boss.Stance.WARY, app.store.stance())
        // 点信用那一行进档案
        compose.onNodeWithTag("stance-line").performClick()
        waitFor(hasTestTag("dossier-head"), 5_000, "档案页")
        waitText("对不上", 5_000)
        settle()
        Thread.sleep(1_200)
        shot("e2e-51-dossier")
        compose.onNodeWithTag("charter").performScrollTo()
        settle()
        shot("e2e-52-dossier-eyes")
        llm.shutdown()
    }

    private fun go(route: String) {
        scenario!!.onActivity { it.startActivity(Intent(it, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, route).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    }

    /**
     * 脉络：示例体系 → 节点 → 打包提问（模拟 AI 结构先行的回答）→ 建议新增进候选 → 录入一段材料让 AI 拆 → 接受候选。
     */
    @Test
    fun veinFlow() {
        app.actions.vein.seed()
        val answer = "核心判断：更像是单步成本高。\n结构：疲劳 → 停止信号提前 → 第一反应停止\n另一种可能：其实是没有下一问\n如果我错了，最可能错在哪：把累当成了原因\n把握：六成\n验证：记一周停下时的疲劳分\n建议新增：\n导致｜睡眠不足｜睡不够时每步更累｜睡眠、疲劳、认知负荷"
        val split = """{"nodes":[{"tmp_id":"t1","title":"提问清单","type":"规则","claim":"卡住前先写下一个问题。","keywords":["提问","清单","下一问"],"status":"假设","confidence":60,"verify":"记一周用清单后停下的次数","parent":"根因"}],"links":[{"from":"t1","to":"n-0006","type":"支撑","why":"有下一问就不会停"}]}"""
        val llm = mockLlm(sayTurn(answer), sayTurn(split))
        app.store.updateConfig { it.copy(ai = com.zongkong.core.AiConfig(llm.url("/v1").toString(), "sk-test", "mock-model")) }
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, "vein"))
        waitFor(hasTestTag("vein-tree"), 10_000, "脉络结构树")
        settle()
        Thread.sleep(1_500)
        shot("e2e-60-vein")
        compose.onNodeWithTag("vein-node-n-0006").performScrollTo().performClick()
        waitFor(hasTestTag("vein-title") and hasText("下一问没有产生"), 5_000, "节点页")
        settle()
        shot("e2e-61-vein-node")
        compose.onNode(hasText("提问") and androidx.compose.ui.test.hasClickAction()).performScrollTo().performClick()
        waitFor(hasTestTag("ask-pack"), 5_000, "打包预览")
        compose.onNodeWithTag("ask-q").performTextInput("两个根因哪个更像真的？")
        hideKeyboard()
        waitText("请说明怎样区分这两个解释", 5_000)
        shot("e2e-62-vein-pack")
        compose.onNodeWithTag("ask-send").performScrollTo().performClick()
        waitFor(hasTestTag("ask-answer"), 15_000, "回答")
        waitText("更像是单步成本高", 5_000)
        settle()
        shot("e2e-63-vein-answer")
        compose.onNodeWithTag("ask-suggest").performScrollTo().performClick()
        settle()
        assertEquals("睡眠不足", app.store.vein.value.candidates.single().title)
        val sent = llm.takeRequest().body.readUtf8()
        assertTrue(sent, sent.contains("【节点】下一问没有产生") && sent.contains("对立·竞争解释"))
        // 录入：贴材料 → AI 拆 → 候选
        go("vein/capture")
        waitFor(hasTestTag("vein-text"), 5_000, "录入页")
        compose.onNodeWithTag("vein-text").performTextInput("卡住之前先写下一个问题，就不容易停下来。")
        hideKeyboard()
        compose.onNodeWithTag("vein-split").performScrollTo().performClick()
        waitFor(hasTestTag("cand-all"), 15_000, "候选卡片页")
        waitText("提问清单", 5_000)
        settle()
        shot("e2e-64-vein-cands")
        assertEquals(2, app.store.vein.value.candidates.size)
        compose.onAllNodes(hasTestTag("cand-accept"))[1].performScrollTo().performClick()
        settle()
        val v = app.store.vein.value
        assertEquals(8, v.nodes.size)
        assertEquals("支撑", v.byTitle("提问清单")!!.links.single().type)
        go("vein/health")
        waitText("个节点", 5_000)
        settle()
        shot("e2e-65-vein-health")
        go("vein/review")
        waitFor(hasTestTag("review-card"), 5_000, "温故")
        compose.onNodeWithTag("review-card").performClick()
        settle()
        shot("e2e-66-vein-review")
        llm.shutdown()
    }

    /** 三个部门的新体系：信息收集部的漏斗（提炼一条）、统筹规划部的估时和精力地图、谋划思考部的脉络卡；对讲页、动效画廊。 */
    @Test
    fun deptsTour() {
        app.actions.work.createThread("国考行测")
        app.actions.work.capture("https://www.bilibili.com/video/BV1 资料分析讲解", com.zongkong.core.work.NoteType.MATERIAL, "B站：资料分析讲解", "https://www.bilibili.com/video/BV1", "", false)
        app.actions.work.capture("增长率可以用错位加减估", com.zongkong.core.work.NoteType.IDEA, "增长率错位加减", "", "", false)
        app.actions.vein.seed()
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, "dept/INFO"))
        waitFor(hasTestTag("intake-funnel"), 10_000, "漏斗")
        settle()
        Thread.sleep(1_200)
        shot("e2e-70-dept-info")
        compose.onNode(hasText("B站：资料分析讲解")).performScrollTo().performClick()
        waitFor(hasTestTag("distill-gist"), 5_000, "提炼")
        compose.onNodeWithTag("distill-gist").performTextInput("资料分析先看讲解里的速算口诀")
        compose.onNode(hasText("国考行测")).performClick()
        settle()
        val key = app.store.work.value.notes().first { it.title == "B站：资料分析讲解" }.key
        assertEquals("资料分析先看讲解里的速算口诀", app.store.intake.value.metas[key]!!.gist)
        assertTrue(app.store.work.value.rec(key)!!.threadKey.isNotBlank())
        go("dept/PLAN")
        waitFor(hasTestTag("plan-instruments"), 5_000, "估时和精力地图")
        settle()
        Thread.sleep(1_200)
        shot("e2e-71-dept-plan")
        go("dept/THINK")
        waitFor(hasTestTag("vein-card"), 5_000, "脉络卡")
        settle()
        Thread.sleep(1_200)
        shot("e2e-72-dept-think")
        go("voice")
        waitFor(hasTestTag("voice-hold"), 5_000, "对讲")
        compose.onNode(hasText("语音设置")).performClick()
        settle()
        shot("e2e-73-voice")
        go("gallery")
        waitText("潮汐", 5_000)
        settle()
        Thread.sleep(800)
        shot("e2e-74-gallery")
    }

    /**
     * 0.6.1 小修：底栏点哪去哪（以前点“今天/四部”会跳到总控/事项）、远方的月亮、事项页的星图和行动快捷键、
     * 记录读 Notion 正文、“总控怎么运转”、三个宠物形象。
     */
    @Test
    fun smallFixes() {
        val dbs = app.store.config.value.notion
        val now = System.currentTimeMillis()
        val tk = app.actions.work.createThread("秋招")
        val (a1, _) = app.actions.work.addAction(tk, "改简历", "", "发给两个朋友看过")
        app.actions.work.addAction(tk, "投 3 家", "", "")
        app.actions.work.complete(a1!!, "改完发出去了", com.zongkong.core.work.WorkOps.Met.YES, "投 3 家")
        val tk2 = app.actions.work.createThread("科目一")
        val (b1, _) = app.actions.work.addAction(tk2, "刷 50 题", "", "")
        app.actions.work.complete(b1!!, "刷完了", com.zongkong.core.work.WorkOps.Met.YES, "")
        runBlocking { app.actions.work.sync() }
        // GPT 在 Notion 里写了一条记录，长内容在页面正文里
        val threadNotion = app.store.work.value.rec(tk)!!.notionId
        runBlocking {
            val schema = (gpt.schema(dbs.notesDb) as ApiResult.Ok).value.props
            val f = mapOf(F.TITLE to "面经整理", F.TYPE to "材料", F.SOURCE to "GPT", F.THREAD to "x")
            val p = NotionMap.toProps(f, f.keys, schema) { threadNotion }
            assertTrue(gpt.createPageObject(dbs.notesDb, p.json, NotionClient.paragraphs("群面先定框架，再分工；最后一分钟留给总结。")) is ApiResult.Ok)
            app.actions.work.sync()
        }
        val beijing = ZoneId.of("Asia/Shanghai")
        app.store.updatePlans { b0 ->
            var b = com.zongkong.core.plan.Aims.set(b0, "", "秋招拿到 offer", "毕业前有着落", java.time.LocalDate.now(beijing).plusDays(40), listOf(tk), now - 20 * 86_400_000L) { "aim1" }.getOrThrow()
            b = com.zongkong.core.plan.Aims.set(b, "", "考过科目一", "", java.time.LocalDate.now(beijing).plusDays(5), listOf(tk2), now - 30 * 86_400_000L) { "aim2" }.getOrThrow()
            b
        }
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        waitFor(hasTestTag("aims"), 10_000, "远方")
        waitText("还剩 5 天", 5_000)
        settle()
        Thread.sleep(1_000)
        shot("e2e-80-home-aims")

        // 底栏：总控 → 事项 → 今天 → 四部，每次都到点的那一页
        compose.onNodeWithTag("tab-chat").performClick()
        waitFor(hasTestTag("flow-open"), 5_000, "总控页")
        compose.onNodeWithTag("tab-threads").performClick()
        waitFor(hasTestTag("star-map"), 5_000, "事项页的星图")
        waitText("本周星图：2 颗", 5_000)
        settle()
        Thread.sleep(800)
        shot("e2e-81-threads-stars")
        compose.onNodeWithTag("tab-home").performClick()
        waitFor(hasTestTag("aims"), 5_000, "回到今天")
        compose.onNodeWithTag("tab-depts").performClick()
        waitText("四个部门", 5_000)
        compose.onNodeWithTag("tab-chat").performClick()
        waitFor(hasTestTag("flow-open"), 5_000, "总控页")
        compose.onNodeWithTag("tab-depts").performClick()
        waitText("四个部门", 5_000)
        compose.onNodeWithTag("tab-home").performClick()
        waitFor(hasTestTag("aims"), 5_000, "回到今天")

        // 事项：行动上直接“开始 / 做完”，记录读 Notion 正文
        go("thread/$tk")
        waitText("✓ 做完", 5_000)
        waitText("面经整理", 10_000)
        compose.onNode(hasText("面经整理")).performScrollTo().performClick()
        compose.onNode(hasText("读 Notion 正文")).performScrollTo().performClick()
        waitText("群面先定框架", 10_000)
        settle()
        shot("e2e-82-thread-actions-notes")

        // 总控怎么运转
        go("flow")
        waitFor(hasTestTag("flow-today"), 5_000, "流程页")
        settle()
        Thread.sleep(1_000)
        shot("e2e-83-flow")
        compose.onNode(hasText("行动怎么用？做完要怎么标？")).performScrollTo().performClick()
        settle()
        shot("e2e-84-flow-faq")

        // 宠物形象：换成团子，马上生效
        go("voice/settings")
        waitFor(hasTestTag("pet-kinds"), 5_000, "形象")
        compose.onNodeWithTag("pet-kinds").performScrollTo()
        settle()
        shot("e2e-85-pets")
        compose.onNode(hasText("团子")).performClick()
        settle()
        assertEquals("CAT", app.store.config.value.boss.petKind)
        compose.onNodeWithTag("voice-now").performScrollTo()
        settle()
        shot("e2e-86-voice-now")
        go("gallery")
        waitText("月相", 5_000)
        compose.onNode(hasText("月相")).performScrollTo()
        settle()
        Thread.sleep(800)
        shot("e2e-87-gallery-new")
    }

    /** 手机 ↔ 电脑：建传话的库、复制配对码、读到电脑的状态、发锁机命令。 */
    @Test
    fun pcLink() {
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).putExtra(com.zongkong.app.ui.MainActivity.EXTRA_ROUTE, "settings/pc"))
        waitFor(hasTestTag("pc-setup"), 10_000, "建立连接")
        compose.onNodeWithTag("pc-setup").performClick()
        waitFor(hasTestTag("pc-copy"), 15_000, "配对码")
        val db = app.store.config.value.notion.devicesDb
        assertTrue(db.isNotBlank())
        compose.onNodeWithTag("pc-copy").performClick()
        val code = app.actions.device.pairingCode(false)
        assertEquals(db, com.zongkong.core.device.DeviceLink.parsePairing(code)!!.devicesDb)
        // 电脑写自己那一行
        val now = System.currentTimeMillis()
        val pcSide = com.zongkong.core.device.PcSide(
            at = now, version = "电脑版 0.1", todayMinutes = 334, funMinutes = 72, blocks = 3,
            top = listOf(com.zongkong.core.device.UsageLine("VS Code", 140, "学习工作"), com.zongkong.core.device.UsageLine("B站", 52, "娱乐")),
            session = "写论文第二章", sessionUntil = now + 40 * 60_000L, credibility = 64, stance = "正常",
            msgs = listOf(com.zongkong.core.device.DeviceMsg("m1", now, "他在电脑上申请了 B站 15 分钟")),
        )
        runBlocking { com.zongkong.core.device.DeviceLink.write(gpt, db, com.zongkong.core.device.DeviceLink.PC, com.zongkong.core.device.DeviceLink.encodePc(pcSide), null) }
        // 手机发一条锁机命令，同步
        app.actions.device.command("lock", 30, "测试")
        runBlocking { app.actions.device.sync() }
        waitText("今天用了 5 小时 34 分", 10_000)
        settle()
        shot("e2e-90-pc")
        // 电脑发来的话进了对话
        assertTrue(app.chat.log.value.messages.any { it.content.contains("他在电脑上申请了 B站") })
        // 手机那一行里有锁机命令、主线和信用
        val rows = runBlocking { (com.zongkong.core.device.DeviceLink.readAll(gpt, db) as ApiResult.Ok).value }
        val phone = com.zongkong.core.device.DeviceLink.decodePhone(rows[com.zongkong.core.device.DeviceLink.PHONE]!!.data)!!
        assertEquals("lock", phone.cmds.single().kind)
        assertEquals(listOf("m1"), phone.gotMsgs)
    }

    /** 0.7：信用掉光自动进入战友模式；首页的电脑栏和战友栏；统筹规划部开一场大统筹。 */
    @Test
    fun partnerAndCampaign() {
        val now = System.currentTimeMillis()
        app.store.updateDossier { d0 ->
            (1..5).fold(d0) { d, i -> com.zongkong.core.boss.Boss.addClaim(d, "c$i", "说做了 2 小时作业", 0, 0, com.zongkong.core.boss.ClaimVerdict.CONTRADICTED, "时间线里抖音 1 小时 40 分", now - i * 60_000L) }
        }
        assertEquals(com.zongkong.core.boss.Stance.STRICT, app.store.stance())
        val llm = mockLlm(sayTurn("【阶段 1/6 · 盘点】离“秋招拿到 offer”还差简历定稿和 20 家投递；这周电脑上 B站 6 小时，主线只动了 40 分钟。我们先把里程碑倒推出来。\n【选项】好，倒推｜先看这周｜改天"))
        app.store.updateConfig { it.copy(ai = com.zongkong.core.AiConfig(llm.url("/v1").toString(), "sk-test", "mock-model")) }
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        // 0.12：信用和强度在“总控”页顶上（首页不再重复放）
        waitFor(hasTestTag("day-plan"), 10_000, "今天的安排")
        go("chat")
        waitFor(hasTestTag("stance-line"), 10_000, "总控页的信用")
        settle()
        shot("e2e-91-chat-stance")
        go("dept/PLAN")
        waitFor(hasTestTag("campaign-GRAND_PLAN"), 5_000, "大统筹")
        settle()
        Thread.sleep(800)
        shot("e2e-92-grand-plan-card")
        compose.onNodeWithTag("campaign-go-GRAND_PLAN").performScrollTo().performClick()
        waitFor(hasTestTag("campaign-banner"), 10_000, "战役进行中")
        waitText("阶段 1/6", 15_000)
        settle()
        shot("e2e-93-grand-plan-chat")
        assertEquals("GRAND_PLAN", app.chat.log.value.campaign)
        val sent = llm.takeRequest().body.readUtf8()
        assertTrue(sent, sent.contains("正在进行的战役：大统筹") && sent.contains("战友"))
        go("dossier")
        waitFor(hasTestTag("intensity"), 5_000, "强度")
        settle()
        shot("e2e-94-dossier-partner")
        go("dept/THINK")
        waitFor(hasTestTag("campaign-GRAND_THINK"), 5_000, "大谋划")
        settle()
        shot("e2e-95-grand-think-card")
        llm.shutdown()
    }

    /**
     * 0.8：今天的清单（照记录列）、很薄的事项提议立项、预案（写 → 定稿）、记一下、账本、无障碍自动修复卡片。
     */
    @Test
    fun dayListPlaybookCaptureLedger() {
        val now = System.currentTimeMillis()
        val bj = app.store.zone
        val rnd = kotlin.random.Random(8)
        fun at(min: Long) = fmt.format(java.time.Instant.ofEpochMilli(now + min * 60_000L).atZone(bj))
        var thin = ""
        var done = ""
        app.store.updateWork { w0 ->
            val (w1, t1) = com.zongkong.core.work.WorkOps.create(w0, com.zongkong.core.work.Kind.THREAD, mapOf(F.TITLE to "国考计划"), now - 3 * 3600_000L, rnd)
            val (w2, t2) = com.zongkong.core.work.WorkOps.create(w1, com.zongkong.core.work.Kind.THREAD, mapOf(F.TITLE to "行测提分", F.WHY to "笔试", F.CRITERIA to "75 分"), now, rnd)
            val (w3, a) = com.zongkong.core.work.WorkOps.create(w2, com.zongkong.core.work.Kind.ACTION, mapOf(F.TITLE to "去查报名时间", F.THREAD to t1.key, F.PLAN to com.zongkong.core.work.Plan(at(-30), at(-10)).encode()), now, rnd)
            val (w4, _) = com.zongkong.core.work.WorkOps.create(w3, com.zongkong.core.work.Kind.ACTION, mapOf(F.TITLE to "资料分析 20 题", F.THREAD to t2.key, F.PLAN to com.zongkong.core.work.Plan(at(20), at(60)).encode()), now, rnd)
            thin = t1.key
            done = a.key
            w4
        }
        app.actions.work.complete(done, "11 月初报名", com.zongkong.core.work.WorkOps.Met.YES, "")
        app.store.updatePlans { com.zongkong.core.plan.PlanBook() }
        app.store.updateLedger { com.zongkong.core.ledger.LedgerBook() }
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        waitFor(hasTestTag("today-line"), 10_000, "首页清单栏")
        go("today")
        waitFor(hasTestTag("today-list"), 5_000, "今天的清单")
        waitText("做完 1/2", 5_000)
        waitText("11 月初报名", 5_000)
        settle()
        shot("e2e-96-today-list")
        val report = com.zongkong.core.plan.DayReport.text(app.actions.dayReport())
        assertTrue(report, report.contains("○ 资料分析 20 题") && report.contains("✓ 去查报名时间"))

        go("thread/$thin")
        waitFor(hasTestTag("thread-project"), 5_000, "很薄的事项")
        settle()
        shot("e2e-97-thread-thin")

        go("playbook")
        waitFor(hasTestTag("script-text"), 5_000, "预案输入")
        compose.onNodeWithTag("script-text").performTextInput("09:00-10:30 资料分析两套 | B: 下雨就在家做一套\n14:00-15:00 申论大作文 | B: 只写提纲")
        hideKeyboard()
        compose.onNodeWithTag("script-lock").performScrollTo().performClick()
        waitText("下雨就在家做一套", 5_000)
        settle()
        shot("e2e-98-playbook")
        assertTrue(app.actions.plan.script()!!.locked)
        assertEquals(2, app.actions.plan.script()!!.blocks.size)

        go("quick")
        waitFor(hasTestTag("quick-text"), 5_000, "记一下")
        compose.onNodeWithTag("quick-text").performTextInput("国考要不要报外省的岗位？")
        hideKeyboard()
        compose.onNodeWithTag("quick-save").performScrollTo().performClick()
        waitText("收好了", 5_000)
        settle()
        shot("e2e-99-quick")
        assertTrue(app.store.work.value.notes().any { it[F.RAW].contains("外省的岗位") })

        go("ledger")
        waitFor(hasTestTag("ledger-amount"), 5_000, "账本")
        compose.onNodeWithTag("ledger-amount").performTextInput("18.5")
        compose.onNodeWithTag("ledger-what").performTextInput("午饭 黄焖鸡")
        hideKeyboard()
        compose.onNodeWithTag("ledger-add").performScrollTo().performClick()
        waitText("记了", 5_000)
        settle()
        shot("e2e-100-ledger")
        assertEquals(1850L, com.zongkong.core.ledger.Ledger.total(app.store.ledger.value.items))
        assertEquals("吃饭", app.store.ledger.value.items.single().cat)

        go("settings/perm")
        waitFor(hasTestTag("perm-selfheal"), 5_000, "自动修复")
        compose.onNodeWithTag("perm-selfheal").performScrollTo()
        settle()
        shot("e2e-101-selfheal")

        go("home")
        waitFor(hasTestTag("script-line"), 5_000, "首页预案栏")
        settle()
        shot("e2e-102-home-08")
    }

    /**
     * 0.10：早上总控替你排好的不会自动放行，要你点确认；主线过了点没开始就欠着（严管），开始做就解开；
     * 挣来的娱乐：底数用完就严管，做完一步再挣。
     */
    @Test
    fun earnAndDebt() {
        val now = System.currentTimeMillis()
        val bj = app.store.zone
        val rnd = kotlin.random.Random(10)
        fun at(min: Long) = fmt.format(java.time.Instant.ofEpochMilli(now + min * 60_000L).atZone(bj))
        // 只留早上的部署这一道关：别的关卡在深夜（比如 0 点多）会先占住首页的严管卡，这里只测欠账
        app.store.updateConfig { it.copy(earn = com.zongkong.core.EarnConfig(), dailyQuotaMin = 120, gates = it.gates.filter { g -> g.id == "plan_morning" }) }
        app.store.updatePlans { com.zongkong.core.plan.PlanBook() }
        var key = ""
        app.store.updateWork { w0 ->
            val (w1, t) = com.zongkong.core.work.WorkOps.create(w0, com.zongkong.core.work.Kind.THREAD, mapOf(F.TITLE to "行测提分", F.WHY to "笔试", F.CRITERIA to "75 分"), now, rnd)
            val (w2, a) = com.zongkong.core.work.WorkOps.create(w1, com.zongkong.core.work.Kind.ACTION, mapOf(F.TITLE to "资料分析 20 题", F.THREAD to t.key, F.CRITERIA to "做完对答案", F.PLAN to com.zongkong.core.work.Plan(at(-50), at(-10)).encode()), now, rnd)
            key = a.key
            w2
        }
        // 后台自动验收不再替你把早上的部署打勾
        runBlocking { app.actions.autoVerify() }
        assertTrue(app.store.status().gates.any { it.gate.id == "plan_morning" && !it.done })
        val st = app.store.status()
        assertTrue(st.reasons.toString(), st.reasons.any { it is com.zongkong.core.Reason.Debt })
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        waitFor(hasTestTag("guide-confirm-plan"), 10_000, "确认今天的部署")
        settle()
        shot("e2e-103-confirm-plan")
        compose.onNodeWithTag("guide-confirm-plan").performClick()
        compose.waitUntil(5_000) { app.store.status().gates.any { it.gate.id == "plan_morning" && it.done } }
        // 还欠着主线：首页是严管卡
        waitText("过了点还没开始", 5_000)
        settle()
        shot("e2e-104-debt")
        // 开始做就解开
        app.store.updateWork { com.zongkong.core.work.WorkOps.start(it, key, System.currentTimeMillis(), 0) }
        assertTrue(app.store.status().reasons.none { it is com.zongkong.core.Reason.Debt })
        // 底数 20 分钟用完：严管；做完这一步 +20 就又能玩
        app.store.updateWork { com.zongkong.core.work.WorkOps.complete(it, key, "做完了", com.zongkong.core.work.WorkOps.Met.YES, "", System.currentTimeMillis(), bj, rnd) }
        app.store.updateToday(System.currentTimeMillis()) { it.copy(playSeconds = mapOf("tv.danmaku.bili" to 30 * 60L)) }
        assertTrue(app.store.status().reasons.none { it is com.zongkong.core.Reason.Budget })
        app.store.updateToday(System.currentTimeMillis()) { it.copy(playSeconds = mapOf("tv.danmaku.bili" to 45 * 60L)) }
        assertTrue(app.store.status().reasons.toString(), app.store.status().reasons.any { it is com.zongkong.core.Reason.Budget })
        go("home")
        waitText("娱乐用完了", 5_000)
        settle()
        shot("e2e-105-budget")
    }

    /**
     * 0.12：说一句就排上 → 时间线 → 延期（改到新时间、照样盯）→ 待安排点一下排进去 → GPT 排好一天的多个交接块一次导入。
     */
    @Test
    fun dayPlan() {
        app.store.updateConfig { it.copy(earn = com.zongkong.core.EarnConfig()) }
        val now = System.currentTimeMillis()
        val bj = app.store.zone
        val h = java.time.Instant.ofEpochMilli(now).atZone(bj).hour
        // 一个没排时间的待办（像从 Notion 同步来的）
        val rnd = kotlin.random.Random(12)
        var loose = ""
        app.store.updateWork { w0 ->
            val (w1, t) = com.zongkong.core.work.WorkOps.create(w0, com.zongkong.core.work.Kind.THREAD, mapOf(F.TITLE to "国考", F.WHY to "上岸"), now, rnd)
            val (w2, a) = com.zongkong.core.work.WorkOps.create(w1, com.zongkong.core.work.Kind.ACTION, mapOf(F.TITLE to "下载职位表", F.THREAD to t.key), now, rnd)
            loose = a.key
            w2
        }
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        waitFor(hasTestTag("day-plan"), 10_000, "今天的安排")
        // 没排时间的不再当“现在这一步”反复追问，在待安排里
        waitFor(hasTestTag("plan-loose"), 5_000, "待安排")
        // 说一句排上（一小时后开始，保证还在今天）
        val start = (h + 1).coerceAtMost(22)
        compose.onNodeWithTag("quick-plan").performTextInput("${start}:00-${start + 1}:00 刷二十道资料分析")
        hideKeyboard()
        compose.onNodeWithTag("quick-plan-add").performClick()
        waitText("排上了", 5_000)
        val added = app.store.work.value.actions().first { it.title.contains("资料分析") }
        assertEquals(com.zongkong.core.plan.QuickPlan.DAILY, app.store.work.value.rec(added.threadKey)!!.title)
        settle()
        shot("e2e-106-day-plan")
        // 延期：点开这一段 → 延到明天，改到新的时间（不是取消）
        compose.onNodeWithTag("plan-row-${added.key}").performScrollTo().performClick()
        waitFor(hasTestTag("plan-actions-${added.key}"), 5_000, "这一段的按钮")
        settle()
        shot("e2e-107-plan-row-open")
        val before = java.time.ZonedDateTime.parse(app.store.work.value.rec(added.key)!!.plan!!.start)
        compose.onNode(hasText("延到明天")).performScrollTo().performClick()
        waitText("到点再叫你", 5_000)
        val moved = app.store.work.value.rec(added.key)!!
        // 延到明天 = 原来那一段的第二天同一时间（按日历日，深夜 0 点多也对）
        assertEquals(before.plusDays(1).toInstant(), java.time.ZonedDateTime.parse(moved.plan!!.start).toInstant())
        // 待安排：点“下午 / 今晚”就排进去
        compose.onNodeWithTag("plan-loose").performScrollTo()
        compose.onAllNodes(hasText("今晚")).onFirst().performClick()
        compose.waitUntil(5_000) { app.store.work.value.rec(loose)!!.plan?.hasTime == true }
        // GPT 排好的一天：两个交接块一起导入
        InstrumentationRegistry.getInstrumentation().runOnMainSync { com.zongkong.app.ui.Gpt.copy(app, "【总控交接】\n事项：日常安排\n行动：\n- 跑步 | 今天 ${start}:30-${start + 1}:00 | 完成依据：3 公里\n【交接结束】\n【总控交接】\n事项：国考\n行动：\n- 查报名时间 | 今天 ${start + 1}:00-${start + 1}:30 | 完成依据：写进事项\n【交接结束】") }
        go("import")
        waitFor(hasTestTag("import-all"), 10_000, "全部导入")
        settle()
        shot("e2e-108-import-day")
        compose.onNodeWithTag("import-all").performScrollTo().performClick()
        compose.waitUntil(5_000) { app.store.work.value.actions().any { it.title == "查报名时间" } }
        assertTrue(app.store.work.value.actions().any { it.title == "跑步" })
        assertEquals(1, app.store.work.value.threads().count { it.title == com.zongkong.core.plan.QuickPlan.DAILY })
        waitFor(hasTestTag("day-plan"), 10_000, "回到今天")
        settle()
        shot("e2e-109-home-after-import")
    }

    /**
     * 交付（0.13）：一张照片当证据 → 总控看（部分达到，让补一样）→ 补一句再看（达到）→ 按结论记下；
     * 交付记录存在本机，行动页能看到。
     */
    @Test
    fun deliveryWithPhotoReview() {
        val llm = mockLlm(
            sayTurn("""{"met":"部分达到","reason":"照片里只看到前 10 题","ask":"拍一下后 10 题，或者写一句正确率"}"""),
            sayTurn("""{"met":"达到","reason":"20 题做完、对过答案，正确率 85%"}"""),
        )
        app.store.updateConfig { it.copy(ai = com.zongkong.core.AiConfig(llm.url("/v1").toString(), "sk-test", "mock-model")) }
        val now = System.currentTimeMillis()
        val rnd = kotlin.random.Random(21)
        var key = ""
        app.store.updateWork { w0 ->
            val (w1, t) = com.zongkong.core.work.WorkOps.create(w0, com.zongkong.core.work.Kind.THREAD, mapOf(F.TITLE to "国考行测", F.WHY to "上岸"), now, rnd)
            val (w2, a) = com.zongkong.core.work.WorkOps.create(
                w1, com.zongkong.core.work.Kind.ACTION,
                mapOf(F.TITLE to "刷 20 道资料分析", F.THREAD to t.key, F.CRITERIA to "做完并对答案"), now, rnd,
            )
            key = a.key
            w2
        }
        // 一张“照片”（模拟分享进来的截图：没有拍摄时间）
        val img = File(app.cacheDir, "proof-test.jpg")
        val bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(240, 236, 226)) }
        img.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        app.proofInbox = listOf(img.absolutePath)

        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        waitFor(hasTestTag("day-plan"), 10_000, "首页")
        go("complete/$key")
        waitFor(hasTestTag("proof-photo-0"), 10_000, "交付页上的照片")
        compose.onNodeWithTag("result-field").performTextInput("前 10 题做完了")
        hideKeyboard()
        shot("e2e-110-delivery-proof")
        compose.onNodeWithTag("complete-review").performScrollTo().performClick()
        waitText("照片里只看到前 10 题", 15_000)
        waitText("补一下就够")
        shot("e2e-111-delivery-partial")
        // 补一句，再看一次（最后一次）
        compose.onNodeWithTag("result-field").performTextInput("，后 10 题也做完了，对过答案 17/20")
        hideKeyboard()
        compose.onNodeWithTag("complete-review").performScrollTo().performClick()
        waitText("正确率 85%", 15_000)
        // 第二轮之后不再让补
        assertTrue(compose.onAllNodes(hasTestTag("complete-review")).fetchSemanticsNodes().isEmpty())
        shot("e2e-112-delivery-pass")
        compose.onNodeWithTag("complete-save").performScrollTo().performClick()
        waitText("结果已记下")
        waitFor(hasTestTag("delivery-summary") and hasText("总控看过：达到", substring = true), 5_000, "交付小结")
        shot("e2e-113-delivery-saved")

        val a = app.store.work.value.rec(key)!!
        assertEquals(com.zongkong.core.work.ActionStatus.DONE, a.actionStatus)
        assertTrue(a[F.RESULT], a[F.RESULT].contains("照片 1 张"))
        val d = app.proofs.of(key)!!
        assertEquals("ai", d.source)
        assertEquals(2, d.rounds.size)
        assertEquals(1, d.items.count { it.kind == com.zongkong.core.work.ProofItem.PHOTO })
        // 同一张照片不能再交给别的事
        assertTrue(com.zongkong.core.work.Proof.duplicate(d.items.first(), app.proofs.usedPhotos()))

        // 行动页看得到交付
        go("action/$key")
        waitFor(hasTestTag("action-delivery"), 10_000, "行动页的交付")
        shot("e2e-114-action-delivery")
        llm.shutdown()
    }

    /**
     * 0.14 对话为中心：打开就是总控对话，顶上看得到今天；说“排今天” → 总控排成时段 → 用户说“好” → 今日部署过了。
     */
    @Test
    fun chatRunsTheDay() {
        val bj = app.store.zone
        // 十分钟后开始、做一小时（不跨过午夜），任何时候跑都还在今天、还没结束
        val z = java.time.ZonedDateTime.now(bj).withSecond(0).withNano(0).plusMinutes(10)
        val e = minOf(z.plusMinutes(60), z.withHour(23).withMinute(59))
        val span = "%02d:%02d-%02d:%02d".format(z.hour, z.minute, e.hour, e.minute)
        val llm = mockLlm(
            toolTurn("add_action" to """{"title":"刷 20 道资料分析","time":"今天 $span","criteria":"做完并对答案"}"""),
            sayTurn("排上了：今天 $span 刷 20 道资料分析。就这样？\n【选项】好｜再改改"),
            toolTurn("confirm_plan" to "{}"),
            sayTurn("今日部署过了。到点我叫你。"),
        )
        app.store.updateConfig { it.copy(startOnChat = true, ai = com.zongkong.core.AiConfig(llm.url("/v1").toString(), "sk-test", "mock-model")) }
        // 前面的测试留下的对话清掉：不然这一段满了会先做一次交接摘要，用掉一条模拟回答
        app.chat.clear()
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        // 打开就是对话，顶上是今天
        waitFor(hasTestTag("chat-today"), 10_000, "对话顶上的今天")
        waitFor(hasTestTag("chat-input"), 5_000, "对话输入框")
        shot("e2e-120-chat-landing")
        compose.onNodeWithTag("chat-input").performTextInput("帮我把今天排一下")
        compose.onNodeWithTag("chat-send").performClick()
        waitText("就这样？", 20_000)
        val a = app.store.work.value.actions().single { it.title == "刷 20 道资料分析" }
        assertEquals(com.zongkong.core.plan.QuickPlan.DAILY, app.store.work.value.rec(a.threadKey)!!.title)
        // 顶上的今天跟着变：下一段就是它
        // 文字在顶栏的子节点上（顶栏本身不合并子节点）
        waitFor(hasText("刷 20 道资料分析", substring = true) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("chat-today")), 10_000, "顶栏显示下一段")
        settle()
        shot("e2e-121-chat-planned")
        compose.onNode(hasText("好")).performScrollTo().performClick()
        // 等这一轮说完；等不到就把对话原样带进失败信息（看清是哪一步卡住）
        try {
            compose.waitUntil(30_000) { !app.chat.busy.value && app.chat.log.value.messages.any { it.content.contains("今日部署过了") } }
        } catch (e: Throwable) {
            val dump = app.chat.log.value.messages.takeLast(10).joinToString("\n") { m ->
                "${m.role}${if (m.error) "(错)" else ""}: ${m.content.take(160)} ${m.summary} ${m.calls.joinToString { it.name }}"
            }
            throw AssertionError("等不到“今日部署过了”。busy=${app.chat.busy.value} 模型被请求了 ${llm.requestCount} 次\n$dump", e)
        }
        waitText("今日部署过了", 5_000)
        settle()
        shot("e2e-122-chat-confirmed")
        val gate = app.store.status().gates.firstOrNull { it.gate.id == com.zongkong.app.data.Actions.PLAN_GATE }
        if (gate != null && gate.phase != com.zongkong.core.GatePhase.NOT_OPEN) assertTrue("今日部署应已通过：${gate.phase}", gate.done)
        llm.shutdown()
    }
}
