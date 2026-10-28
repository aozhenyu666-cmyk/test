package com.yishou.app.bank

import com.yishou.app.card.Card
import com.yishou.app.card.CardEngine
import com.yishou.app.card.Conf
import com.yishou.app.card.FakeCardDao
import com.yishou.app.card.FakeCardGen
import com.yishou.app.card.Flaws
import com.yishou.app.card.Round
import com.yishou.app.card.RoundConfig
import com.yishou.app.card.Sentences
import com.yishou.app.card.Step
import com.yishou.app.data.CardSource
import com.yishou.app.data.JobMode
import com.yishou.app.data.JobStep
import com.yishou.app.data.Material
import com.yishou.app.data.StepResult
import com.yishou.app.job.Jobs
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import com.yishou.app.think.DrillKey
import com.yishou.app.think.DrillSession
import com.yishou.app.think.DrillStep
import com.yishou.app.think.FakeDrillGen
import com.yishou.app.think.FakePracticeDao
import com.yishou.app.think.FakeThinkDao
import com.yishou.app.think.Lens
import com.yishou.app.think.RawHit
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val PACK = """
好的，下面是 3 道题：

【题型】言语理解·主旨概括
【材料】行道树能遮阴。有人认为行道树只是装饰。但行道树还能降噪、吸尘。因此城市应多种行道树。
【题目】这段文字意在说明：
A. 行道树能遮阴
B. 城市应多种行道树
C. 行道树只是装饰
D. 所有树都能降噪
【答案】B
【依据】第4句
【解析】“因此”引出结论。
---
【题型】数量关系
【题目】某数加 5 等于 12，这个数是多少？
A、5
B、6
C、7
D、8
答案：C
---
【题型】申论·归纳概括
【材料】某社区推行垃圾分类，起初居民参与率低。后来社区设立督导员、积分兑换，并公开各楼栋数据。
【题目】根据给定资料，概括该社区提高垃圾分类参与率的做法。（不超过 100 字）
【要点】
1. 设立督导员现场指导
2. 积分兑换激励
3. 公开楼栋数据形成比较
【解析】三条做法分别在第二句。
""".trimIndent()

class BankParserTest {

    @Test
    fun parsesMixedPack() {
        val items = BankParser.parse(PACK)
        assertEquals(3, items.size)
        val a = items[0] as BankItem.Choice
        assertEquals("言语理解·主旨概括", a.type)
        assertEquals(1, a.answer)
        assertEquals(setOf(4), a.evidence)
        assertEquals("城市应多种行道树", a.options[1])
        val b = items[1] as BankItem.Choice
        assertEquals(2, b.answer)
        assertEquals("", b.material)
        val c = items[2] as BankItem.Essay
        assertEquals(3, c.points.size)
        assertEquals("设立督导员现场指导", c.points[0])
        assertEquals(100, c.limit)
        assertTrue(BankParser.looksLike(PACK))
        assertFalse(BankParser.looksLike("今天天气不错，我们去公园吧。"))
    }

    @Test
    fun parsesLooseNumberedQuestions() {
        val text = """
            1. 下列说法正确的是：
            (A) 甲
            (B) 乙
            (C) 丙
            (D) 丁
            答案：A
            2. 第二题问的是什么？
            A．一
            B．二
            C．三
            D．四
            正确答案：D
        """.trimIndent()
        val items = BankParser.parse(text).filterIsInstance<BankItem.Choice>()
        assertEquals(2, items.size)
        assertEquals(0, items[0].answer)
        assertEquals(3, items[1].answer)
        assertEquals("第二题问的是什么？", items[1].question)
    }

    @Test
    fun choiceWithoutAnswerIsKept() {
        val items = BankParser.parse("【题目】哪个对？\nA. 1\nB. 2\nC. 3\nD. 4")
        assertNull((items.single() as BankItem.Choice).answer)
    }

    @Test
    fun cardFromBankSkipsEvidenceStep() {
        val b = BankParser.parse(PACK)[1] as BankItem.Choice
        val card = BankParser.toCard(b, b.answer!!)
        assertFalse(card.hasEvidence)
        assertEquals(card, Card.fromJson(card.toJson()))
        var r = Round(card, RoundConfig(checks = 1, allowChange = true)).bet(2, Conf.HIGH)
        assertEquals(Step.CHECK, r.step)
        // 没有错因表：看出“有毛病”就算查对
        r = r.check(0, "无中生有")
        assertEquals(1, r.checksOk)
        r = r.change(null)
        assertTrue(r.evidenceOk)
        assertFalse(r.scoreLine().contains("依据"))
    }

    @Test
    fun promptsAndLinks() {
        assertTrue(BankPrompt.build("申论·归纳概括", 3, "真题难度").contains("【要点】"))
        assertTrue(BankPrompt.build("数量关系", 5, "偏难").contains("【答案】"))
        val links = BankPrompt.links(BankPrompt.DEFAULT_LINKS + "\n没有网址的一行\nhttps://example.com/x")
        assertEquals(4, links.size)
        assertEquals("ChatGPT" to "https://chatgpt.com/", links[0])
        assertEquals("example.com", links[3].first)
    }

    @Test
    fun sentencesKeepQuotes() {
        assertEquals(listOf("他说：“好。”", "然后走了。"), Sentences.split("他说：“好。”然后走了。"))
    }

    @Test
    fun tolerantCardJson() {
        val o = JSONObject(
            """{"passage":"第一句。第二句。","question":"问？","options":["A. 甲","B. 乙","C. 丙","D. 丁"],"answer":"选C","evidence":"第2句"}""",
        )
        val c = Card.parse(o)
        assertEquals(listOf("第一句。", "第二句。"), c.sentences)
        assertEquals("甲", c.options[0])
        assertEquals(2, c.answer)
        assertEquals(setOf(2), c.evidence)
        assertTrue(c.flaws.isEmpty())
    }
}

class BankImportTest {

    @Test
    fun importsAndDealsBankFirst() = runTest {
        val cards = FakeCardDao()
        val think = FakeThinkDao()
        val gen = FakeCardGen()
        val engine = CardEngine(cards, gen, clock = { 1_000L })
        val imp = BankImport(engine, think, clock = { 1_000L })
        val r = imp.import(PACK)
        assertEquals(2, r.choices)
        assertEquals(1, r.essays)
        assertTrue(r.line().contains("选择题 2 道"))
        // 再导一次：全是重复
        assertEquals(0, imp.import(PACK).total)

        // 发牌先发题库里的，不调模型
        val d = (engine.deal(null) as LlmResult.Ok).value
        assertEquals(CardSource.BANK, d.row.source)
        assertEquals(0, gen.generated)

        val essay = think.materials.single()
        val key = DrillKey.fromJson(think.keys[essay.id]!!.key)
        assertEquals(Lens.ESSAY, key.lens)
        assertEquals(100.0, key.value!!, 0.0)
    }

    @Test
    fun unverifiedFallbackInsteadOfError() = runTest {
        val cards = FakeCardDao()
        val gen = FakeCardGen()
        val engine = CardEngine(cards, gen, clock = { 1_000L })
        gen.solves += listOf(3, 3)
        val d = engine.deal(null)
        assertTrue(d is LlmResult.Ok)
        assertFalse((d as LlmResult.Ok).value.row.verified)
        // 后台备题不留没核对上的
        gen.solves += listOf(3, 3)
        assertEquals(0, engine.prefetch(1))
    }
}

class EssayDrillTest {

    private val mat = Material(id = 1, title = "申论", text = "材料……【题目】概括做法", source = "bank", createdAt = 0)
    private val key = DrillKey(Lens.ESSAY, "概括做法（不超过 20 字）", listOf("设督导员", "积分兑换", "公开数据"), value = 15.0)

    @Test
    fun essayScoresByPointsAndLimit() = runTest {
        val gen = FakeDrillGen()
        val s = DrillSession(gen, FakeThinkDao().apply { materials += mat }, FakePracticeDao(), clock = { 1L })
        s.startLocked(mat, key)
        assertEquals(DrillStep.ANSWER, s.state.value.step)
        gen.points = listOf(RawHit(true, "设督导员", ""), RawHit(true, "积分兑换", ""), RawHit(false, "", "没写公开数据"))
        s.answer("设督导员现场指导，积分兑换鼓励参与，效果明显")
        val st = s.state.value
        assertEquals(DrillStep.REVEAL, st.step)
        // 两条/三条 = 66，超字数扣 10
        assertEquals(56, st.score)
        assertFalse(st.checks.last().ok)
    }

    @Test
    fun judgeFailureFallsBackToSelfCheck() = runTest {
        val gen = object : com.yishou.app.llm.DrillGen by FakeDrillGen() {
            override suspend fun judgePoints(key: DrillKey, answer: String): LlmResult<List<RawHit?>> = LlmResult.Err(LlmError.Timeout)
        }
        val s = DrillSession(gen, FakeThinkDao(), FakePracticeDao(), clock = { 1L })
        s.startLocked(mat, key.copy(value = null))
        s.answer("设督导员，积分兑换，公开数据")
        assertEquals(DrillStep.SELF, s.state.value.step)
        assertNotNull(s.state.value.error)
        s.selfMark(listOf(true, true, true))
        assertEquals(DrillStep.REVEAL, s.state.value.step)
        assertEquals(100, s.state.value.score)
    }

    @Test
    fun essayIsNotSuggestedForMaterials() {
        assertFalse(Lens.ESSAY in Lens.suggest("随便一段话。"))
    }
}

class JobsTest {

    @Test
    fun planModes() {
        assertEquals(10 to "道", Jobs.count("今天完成 10 道题"))
        assertEquals(3 to "篇", Jobs.count("写三篇申论"))
        assertNull(Jobs.count("整理房间"))
        assertEquals(JobMode.COUNT, Jobs.suggestMode("做 10 道资料分析"))
        assertEquals(JobMode.OPEN, Jobs.suggestMode("整理房间"))
        assertEquals(10, Jobs.plan(JobMode.COUNT, "完成10道题", "").size)
        assertEquals("第 1 道", Jobs.plan(JobMode.COUNT, "完成10道题", "").first())
        assertEquals(listOf("读材料", "列要点", "写答案"), Jobs.plan(JobMode.LIST, "", "1. 读材料\n② 列要点\n\n- 写答案"))
        assertEquals(listOf("先找桌面上的书"), Jobs.plan(JobMode.OPEN, "", "", "先找桌面上的书"))
    }

    @Test
    fun summaryCountsTime() {
        val m = 60_000L
        val steps = listOf(
            JobStep(id = 1, jobId = 1, idx = 0, text = "1", startedAt = 0, doneAt = 2 * m, result = StepResult.OK),
            JobStep(id = 2, jobId = 1, idx = 1, text = "2", startedAt = 2 * m, doneAt = 8 * m, result = StepResult.MISS),
            JobStep(id = 3, jobId = 1, idx = 2, text = "3", startedAt = 8 * m, doneAt = 10 * m, result = StepResult.STUCK),
            JobStep(id = 4, jobId = 1, idx = 3, text = "4"),
        )
        assertEquals(steps[3], Jobs.current(steps))
        assertEquals(0.75f, Jobs.progress(steps), 0.001f)
        val s = Jobs.summary(steps, estimateMinutes = 8)
        assertEquals(3, s.done)
        assertEquals(1, s.ok)
        assertEquals(10 * m, s.spentMs)
        assertEquals("2", s.slowest?.text)
        assertEquals(25, s.overrun)
        assertTrue(Jobs.line(s).contains("比估计多 25%"))
        assertEquals("3分20秒", Jobs.showDuration(200_000))
    }
}
