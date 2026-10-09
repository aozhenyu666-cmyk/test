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
}
