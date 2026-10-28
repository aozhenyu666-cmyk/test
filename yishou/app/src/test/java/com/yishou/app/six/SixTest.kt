package com.yishou.app.six

import com.yishou.app.bank.BankItem
import com.yishou.app.bank.BankParser
import com.yishou.app.bank.DocText
import com.yishou.app.data.DrillRow
import com.yishou.app.data.Material
import com.yishou.app.llm.DrillPrompts
import com.yishou.app.llm.Tagged
import com.yishou.app.outside.AiApps
import com.yishou.app.outside.Outsides
import com.yishou.app.think.Classics
import com.yishou.app.think.DrillKey
import com.yishou.app.think.DrillSession
import com.yishou.app.think.DrillStep
import com.yishou.app.think.FakeDrillGen
import com.yishou.app.think.FakePracticeDao
import com.yishou.app.think.FakeThinkDao
import com.yishou.app.think.Lens
import com.yishou.app.think.RawHit
import com.yishou.app.think.Recite
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaggedTest {

    @Test
    fun readsTagsAndTolerance() {
        val text = """
            好的，下面是答案：
            【主干】城市应多种行道树
            【要点】
            1. 能降噪
            2. 能吸尘
            因此：这一行不是标签
            【解析】转折在第三句
        """.trimIndent()
        val o = Tagged.toJson(text, DrillPrompts.NAMES, DrillPrompts.LISTS, DrillPrompts.NUMBERS)
        assertEquals("城市应多种行道树", o.getString("main"))
        assertEquals(3, o.getJSONArray("points").length())
        val k = DrillKey.parse(Lens.GIST, o)
        assertEquals(listOf("城市应多种行道树", "能降噪", "能吸尘", "因此：这一行不是标签"), k.points)
    }

    @Test
    fun jsonStillWorks() {
        val o = Tagged.toJson("```json\n{\"main\":\"a\",\"points\":[\"b\"]}\n```", DrillPrompts.NAMES)
        assertEquals("a", o.getString("main"))
    }

    @Test
    fun numbersAndNewLenses() {
        val est = Tagged.toJson("【题目】北京到上海多少公里\n【参考值】约 1200 公里\n【单位】公里", DrillPrompts.NAMES, DrillPrompts.LISTS, DrillPrompts.NUMBERS)
        assertEquals(1200.0, DrillKey.parse(Lens.ESTIMATE, est).value!!, 0.0)
        val frame = Tagged.toJson("【主题】垃圾分类\n【关系】\n1. 督导 —— 手段 —— 参与率\n2. 积分 —— 激励 —— 居民", DrillPrompts.NAMES, DrillPrompts.LISTS)
        assertEquals(2, DrillKey.parse(Lens.FRAME, frame).points.size)
        val link = Tagged.toJson("【对象】边际成本递减\n【参考】\n1. 像软件复制", DrillPrompts.NAMES, DrillPrompts.LISTS)
        assertEquals("边际成本递减", DrillKey.parse(Lens.LINK, link).prompt)
    }
}

class BankFilesTest {

    @Test
    fun answerKeyAtTheEnd() {
        val doc = """
            一、言语理解
            1. 这段文字意在说明：
            A. 甲
            B. 乙
            C. 丙
            D. 丁
            2. 下列说法正确的是：
            A. 一 B. 二 C. 三 D. 四
            参考答案
            1-2 CB
            1.【答案】C【解析】第一题看结论。
        """.trimIndent()
        val items = BankParser.parse(doc).filterIsInstance<BankItem.Choice>()
        assertEquals(2, items.size)
        assertEquals(2, items[0].answer)
        assertEquals(1, items[1].answer)
        assertEquals("二", items[1].options[1])
        assertEquals("第一题看结论。", items[0].explain)
    }

    @Test
    fun sharedMaterialForDataQuestions() {
        val doc = """
            根据以下资料，回答 1～2 题。2023 年某市实现地区生产总值 5000 亿元，同比增长 6%，其中第三产业增加值 3000 亿元，同比增长 8%，占比继续提高。
            1. 2023 年该市第三产业占地区生产总值的比重约为：
            A. 50%
            B. 60%
            C. 70%
            D. 80%
            答案：B
            2. 2022 年该市地区生产总值约为多少亿元？
            A. 4500
            B. 4717
            C. 4800
            D. 5300
            答案：B
        """.trimIndent()
        val items = BankParser.parse(doc).filterIsInstance<BankItem.Choice>()
        assertEquals(2, items.size)
        assertTrue(items.all { it.material.contains("5000 亿元") })
    }

    @Test
    fun taggedCardWithFlaws() {
        val text = """
            【材料】行道树能遮阴。有人认为行道树只是装饰。但行道树还能降噪、吸尘。因此城市应多种行道树。
            【题目】这段文字意在说明：
            A. 行道树能遮阴
            B. 城市应多种行道树
            C. 行道树只是装饰
            D. 所有树都能降噪
            【答案】B
            【依据】第 4 句
            【错因】A：范围扩大；C：与原文相反；D：绝对化
            【解析】……
        """.trimIndent()
        val c = BankParser.parse(text).single() as BankItem.Choice
        val card = BankParser.toCard(c, c.answer!!)
        assertTrue(card.hasFlaws)
        assertEquals("绝对化", card.flaws[3])
        assertEquals(setOf(4), card.evidence)
    }

    @Test
    fun docxXml() {
        val xml = "<w:body><w:p><w:r><w:t>1. 题目</w:t></w:r></w:p><w:p><w:r><w:t xml:space=\"preserve\">A. 甲 &amp; 乙</w:t></w:r><w:r><w:tab/><w:t>B. 丙</w:t></w:r></w:p></w:body>"
        assertEquals("1. 题目\nA. 甲 & 乙\tB. 丙", DocText.fromDocumentXml(xml))
        assertEquals("中文", DocText.decode("中文".toByteArray(charset("GBK"))))
        assertEquals("中文", DocText.decode("﻿中文".toByteArray()))
    }
}

class ReciteTest {

    @Test
    fun diffMarksMissAndExtra() {
        val r = Recite.diff("床前明月光，疑是地上霜。", "床前明月光疑是天上霜")
        assertEquals(9, r.matched)
        assertEquals(10, r.total)
        assertEquals(90, r.score)
        assertTrue(r.pieces.any { it.op == Recite.Op.MISS && it.text == "地" })
        assertTrue(r.pieces.any { it.op == Recite.Op.EXTRA && it.text == "天" })
        assertEquals("床前明月光，疑是地上霜。", r.pieces.filter { it.op != Recite.Op.EXTRA }.joinToString("") { it.text })
        assertEquals(100, Recite.diff("学而时习之", "学而时习之").score)
        assertEquals(0, Recite.diff("学而时习之", "").score)
    }

    @Test
    fun skeletonChunksAndTime() {
        assertEquals("床前＿＿＿，疑是＿＿＿。", Recite.skeleton("床前明月光，疑是地上霜。"))
        val chunks = Recite.chunks(Classics.ALL.first { it.title.startsWith("登高") }.text, max = 30)
        assertTrue(chunks.size >= 2)
        assertEquals(15, Recite.readSeconds("短"))
    }

    @Test
    fun dueAfterInterval() {
        val day = 86_400_000L
        fun row(score: Int, at: Long) = DrillRow(
            materialId = null, lens = Lens.RECITE.name, key = DrillKey(Lens.RECITE, "静夜思", listOf("床前明月光")).toJson(),
            answer = "", checks = "[]", score = score, durationMs = 0, createdAt = at,
        )
        // 背好一次：隔 1 天
        assertTrue(Recite.due(listOf(row(95, 0)), day / 2).isEmpty())
        assertEquals(1, Recite.due(listOf(row(95, 0)), day).size)
        // 连续背好两次：隔 2 天
        assertTrue(Recite.due(listOf(row(95, 0), row(95, day)), 2 * day).isEmpty())
        // 没背好：半天后再来
        assertEquals(1, Recite.due(listOf(row(40, 0)), day / 2).size)
    }
}

class SixFaceSessionTest {

    private val mat = Material(id = 1, title = "t", text = "行道树能遮阴。但还能降噪。因此应多种。", source = "typed", createdAt = 0)

    @Test
    fun reciteIsLocalAndCountsLeaves() = runTest {
        var now = 1_000L
        val s = DrillSession(FakeDrillGen(), FakeThinkDao(), FakePracticeDao(), clock = { now })
        s.startRecite(null, "静夜思", "床前明月光，疑是地上霜。", skeleton = false)
        assertTrue(s.state.value.readUntil > now)
        s.left() // 还没收起，不算
        s.hide()
        s.left()
        s.recite("床前明月光，疑是地上霜")
        val st = s.state.value
        assertEquals(DrillStep.REVEAL, st.step)
        assertEquals(100, st.score)
        assertFalse(st.checks.last().ok)
        assertNotNull(st.recite)
    }

    @Test
    fun linkUsesRubricAndQuotes() = runTest {
        val gen = FakeDrillGen()
        val s = DrillSession(gen, FakeThinkDao().apply { materials += mat }, FakePracticeDao(), clock = { 1L })
        gen.keys += com.yishou.app.llm.LlmResult.Ok(DrillKey(Lens.LINK, "多种树降噪"))
        s.start(mat, Lens.LINK)
        gen.rubric = listOf(RawHit(true, "像隔音墙", ""), RawHit(true, "都靠阻挡声波", ""), RawHit(true, "编出来的", ""))
        s.link(listOf("像隔音墙", "都靠阻挡声波传播", "树会落叶，冬天效果差"))
        val st = s.state.value
        assertEquals(DrillStep.REVEAL, st.step)
        assertEquals(listOf(true, true, false), st.checks.map { it.ok })
        assertEquals(66, st.score)
    }

    @Test
    fun echoReusesPreviousKeyAndHides() = runTest {
        val gen = FakeDrillGen()
        val s = DrillSession(gen, FakeThinkDao().apply { materials += mat }, FakePracticeDao(), clock = { 1L })
        s.startEcho(mat, DrillKey(Lens.GIST, Lens.GIST.ask, listOf("应多种行道树", "能降噪")))
        assertTrue(s.state.value.hidden)
        assertEquals(Lens.ECHO, s.state.value.key!!.lens)
        gen.points = listOf(RawHit(true, "应该多种树", ""), RawHit(false, "", ""))
        s.answer("我记得是说应该多种树")
        assertEquals(DrillStep.REVEAL, s.state.value.step)
        assertFalse(s.state.value.hidden)
        assertEquals(60, s.state.value.score)
    }

    @Test
    fun facesGroupLenses() {
        assertEquals(listOf(Lens.GIST, Lens.RECITE), Lens.ofFace(1))
        assertEquals(listOf(Lens.ECHO), Lens.ofFace(6))
        assertFalse(Lens.ECHO in Lens.suggest("随便"))
    }
}

class OutsideTest {

    @Test
    fun reportParsing() {
        val r = Outsides.parse("【科目】资料分析\n【题数】20\n【做对】无\n【正确率】75%\n【用时】18分30秒")
        assertEquals("资料分析", r.subject)
        assertEquals(20, r.questions)
        assertEquals(15, r.correct)
        assertEquals(1110, r.seconds)
        assertEquals(750, Outsides.seconds("00:12:30"))
        assertEquals(750, Outsides.seconds("12:30"))
        assertEquals(3900, Outsides.seconds("用时 1小时5分"))
        assertNull(Outsides.seconds("无"))
    }

    @Test
    fun appLines() {
        val apps = AiApps.parse(AiApps.DEFAULT + "\n只有名字\nhttps://x.com/a")
        assertEquals(6, apps.size)
        assertEquals("com.openai.chatgpt", apps[0].pkg)
        assertEquals("x.com", apps.last().name)
        assertNull(apps.last().pkg)
    }
}
