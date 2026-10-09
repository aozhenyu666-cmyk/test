package com.zongkong.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FakeNotion(
    var pages: List<NotionPage> = emptyList(),
    var fail: String? = null,
    var schema: NotionSchema = NotionSchema("标题", mapOf("标题" to "title", "类型" to "select", "日期" to "date"), emptyMap()),
) : NotionApi {
    val created = mutableListOf<Pair<String, JsonObject>>()
    var lastSince: String? = null
    override suspend fun me() = fail?.let { ApiResult.Err(it) } ?: ApiResult.Ok("bot")
    override suspend fun createdSince(dbId: String, sinceIso: String): ApiResult<List<NotionPage>> {
        lastSince = sinceIso
        return fail?.let { ApiResult.Err(it) } ?: ApiResult.Ok(pages)
    }
    override suspend fun pageText(pageId: String, maxChars: Int) = ApiResult.Ok("正文 $pageId")
    override suspend fun schema(dbId: String) = ApiResult.Ok(schema)
    override suspend fun createDatabase(parentPage: String, title: String, properties: JsonObject) = ApiResult.Ok("db-$title")
    override suspend fun createPage(dbId: String, properties: JsonObject, children: JsonArray): ApiResult<String> {
        created += dbId to properties
        return ApiResult.Ok("page")
    }
}

class VerifierTest {
    private val date = LocalDate.of(2026, 10, 9)
    private val aiCfg = Config(ai = AiConfig("https://x", "k", "m"))

    private fun llm(reply: String?) = LlmApi { _, _ -> reply?.let { ApiResult.Ok(it) } ?: ApiResult.Err("网络连接失败") }

    @Test
    fun textModeChecksEffectiveChars() = runTest {
        val v = Verifier(llm(null), FakeNotion())
        val g = gate(minChars = 10).copy(template = "主线：")
        val short = v.verify(g, "主线：看书", Config(), date, ZONE, "") as VerifyOutcome.Done
        assertFalse(short.verdict.pass)
        val ok = v.verify(g, "主线：行测言语理解四十题，错题整理", Config(), date, ZONE, "") as VerifyOutcome.Done
        assertTrue(ok.verdict.pass)
    }

    @Test
    fun aiVerdictParsed() = runTest {
        var seenUser = ""
        val api = LlmApi { _, user -> seenUser = user; ApiResult.Ok("""```json
{"pass": false, "score": 55, "feedback": "主线没有完成标准", "missing": ["完成标准"]}
```""") }
        val v = Verifier(api, FakeNotion())
        val g = gate(verify = VerifyMode.AI, minChars = 60).copy(rubric = "有且只有一件主线\n主线写了具体时间段")
        val out = v.verify(g, "主线：行测言语理解四十题，九点到十一点，做完并整理错题本。次要：背成语五十个，申论范文读一篇。风险：上午容易刷手机，对策：手机放客厅充电，总控严管", aiCfg, date, ZONE, "现在 08:00") as VerifyOutcome.Done
        assertFalse(out.verdict.pass)
        assertEquals(55, out.verdict.score)
        assertEquals(listOf("完成标准"), out.verdict.missing)
        assertTrue(seenUser.contains("有且只有一件主线"))
        assertTrue(seenUser.contains("现在 08:00"))
    }

    @Test
    fun aiPassBelowThresholdIsFail() {
        val v = Verifier.parseVerdict("""{"pass": true, "score": 40, "feedback": "勉强"}""")!!
        assertFalse(v.pass)
        assertTrue(Verifier.parseVerdict("""{"pass": "true", "score": "85", "feedback": "好"}""")!!.pass)
        assertEquals(null, Verifier.parseVerdict("我觉得不错"))
    }

    @Test
    fun aiUnavailableOffersDegrade() = runTest {
        val v = Verifier(llm(null), FakeNotion())
        val g = gate(verify = VerifyMode.AI, minChars = 10)
        val out = v.verify(g, "今天的内容写得很充分很具体，有完成标准", aiCfg, date, ZONE, "")
        assertTrue(out is VerifyOutcome.Unavailable && out.canDegrade)
        val d = Verifier.degraded(g, "今天的内容写得很充分很具体，有完成标准", date, "网络")
        assertFalse(d.pass) // 降级要求 max(10,30)*2 = 60 字
        val long = "今天上午九点到十一点做行测言语理解四十题，正确率七成五，错题集中在逻辑填空。下午两点到四点背申论范文两篇，晚上复盘。明天继续资料分析。"
        assertTrue(Verifier.degraded(g, long, date, "网络").pass)
    }

    @Test
    fun aiGateWithoutAiConfigNeedsDoubleChars() = runTest {
        val v = Verifier(llm("""{"pass":true,"score":90,"feedback":"好"}"""), FakeNotion())
        val g = gate(verify = VerifyMode.AI, minChars = 10)
        val out = v.verify(g, "一二三四五六七八九十十一", Config(), date, ZONE, "") as VerifyOutcome.Done
        assertFalse(out.verdict.pass)
        assertTrue(out.verdict.feedback.contains("翻倍"))
        val ok = v.verify(g, "一二三四五六七八九十甲乙丙丁戊己庚辛壬癸", Config(), date, ZONE, "") as VerifyOutcome.Done
        assertTrue(ok.verdict.pass)
    }

    @Test
    fun notionModeCountsPagesCreatedToday() = runTest {
        val notion = FakeNotion(pages = listOf(NotionPage("1", "国考公告", "", ""), NotionPage("2", "为什么拖延", "", "")))
        val v = Verifier(llm(null), notion)
        val g = gate(verify = VerifyMode.NOTION).copy(notionDb = "db", notionMinPages = 3)
        val out = v.verify(g, "", aiCfg, date, ZONE, "") as VerifyOutcome.Done
        assertFalse(out.verdict.pass)
        assertTrue(out.verdict.feedback.contains("还差 1 条"))
        assertEquals("2026-10-09T04:00:00+08:00", notion.lastSince)

        notion.pages = notion.pages + NotionPage("3", "灵感", "", "")
        assertTrue((v.verify(g, "", aiCfg, date, ZONE, "") as VerifyOutcome.Done).verdict.pass)
    }

    @Test
    fun notionAiSendsPageTextToAi() = runTest {
        var seen = ""
        val notion = FakeNotion(pages = listOf(NotionPage("p1", "问题一", "", "")))
        val v = Verifier({ _, u -> seen = u; ApiResult.Ok("""{"pass":true,"score":80,"feedback":"可以"}""") }, notion)
        val g = gate(verify = VerifyMode.NOTION_AI, minChars = 0).copy(notionDb = "db", notionMinPages = 1)
        val out = v.verify(g, "看库", aiCfg, date, ZONE, "") as VerifyOutcome.Done
        assertTrue(out.verdict.pass)
        assertTrue(seen.contains("正文 p1"))
    }

    @Test
    fun notionErrorOffersDegrade() = runTest {
        val v = Verifier(llm(null), FakeNotion(fail = "Notion 返回 404"))
        val g = gate(verify = VerifyMode.NOTION).copy(notionDb = "db")
        val out = v.verify(g, "", aiCfg, date, ZONE, "")
        assertTrue(out is VerifyOutcome.Unavailable && out.reason.contains("404"))
    }

    @Test
    fun evidenceModeCounts() = runTest {
        val v = Verifier(llm(null), FakeNotion())
        val g = Defaults.gates().first { it.id == "plan_morning" }
        val none = v.verify(g, "", Config(), date, ZONE, "") { 0 to emptyList() } as VerifyOutcome.Done
        assertFalse(none.verdict.pass)
        assertTrue(none.verdict.feedback.contains("还差 1 条"))
        val ok = v.verify(g, "", Config(), date, ZONE, "") { k -> assertEquals("PLANNED_TODAY", k); 2 to listOf("言语 40 题", "改简历") } as VerifyOutcome.Done
        assertTrue(ok.verdict.pass)
        assertTrue(ok.verdict.feedback.contains("言语 40 题"))
    }

    @Test
    fun captureUsesSchema() = runTest {
        val notion = FakeNotion()
        Workspace.capture(notion, "inbox", "为什么总拖到晚上才开始\n可能是早上没部署", "问题", "2026-10-09")
        val props = notion.created.single().second
        assertTrue(props.toString().contains("为什么总拖到晚上才开始"))
        assertTrue(props.toString().contains("\"select\":{\"name\":\"问题\"}"))

        val bare = FakeNotion(schema = NotionSchema("Name", mapOf("Name" to "title"), emptyMap()))
        Workspace.capture(bare, "inbox", "一条信息", "信息", "2026-10-09")
        val p2 = bare.created.single().second.toString()
        assertTrue(p2.contains("【信息】一条信息"))
        assertFalse(p2.contains("日期"))
    }
}

class ThinkSheetTest {
    private val sheet = """
        问题（从信息收集部挑的）：为什么总是晚上才开始学习
        今天的方法卡：五个为什么
        我的初判：早上没有部署
        最有力的反驳或风险（可以让 AI 当反方）：部署了也一样拖
        修正后的结论：起床后第一件事是刷手机，部署被挤掉
        24 小时内的下一步：明早手机放客厅，起床先写晨间部署
        置信度（0–100%）：70%
    """.trimIndent()

    @Test
    fun extractsFields() {
        assertEquals("为什么总是晚上才开始学习", ThinkSheet.question(sheet))
        assertEquals("起床后第一件事是刷手机，部署被挤掉", ThinkSheet.field(sheet, "修正后的结论"))
        assertEquals(0.7, ThinkSheet.confidence(sheet)!!, 1e-9)
    }

    @Test
    fun outboxTargetsClearOneByOne() {
        val st = Engine.evaluate(Config(), DayLog("2026-10-09"), at(20), ZONE).gates.first { it.gate.id == "think_one" }
        check(st.gate.dept == Dept.THINK)
        var day = DayOps.submit(DayLog("2026-10-09"), st, sheet, passVerdict(), at(20))
        val e = day.outbox.single()
        assertEquals(setOf("log", "think"), e.targets)
        day = DayOps.outboxDone(day, e.at, "log")
        assertEquals(setOf("think"), day.outbox.single().targets)
        day = DayOps.outboxDone(day, e.at, "think")
        assertTrue(day.outbox.isEmpty())
    }
}
