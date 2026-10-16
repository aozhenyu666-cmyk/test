package com.yishou.app.endgame

import com.yishou.app.brake.Brake
import com.yishou.app.card.Card
import com.yishou.app.card.Skill
import com.yishou.app.data.Endgame
import com.yishou.app.data.EndgameSource
import com.yishou.app.line.Ask
import com.yishou.app.line.Lines
import com.yishou.app.llm.BrakePrompts
import com.yishou.app.llm.EndgamePrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndgamesTest {

    private val source = "某小区上月发文，严禁电动车飞线充电，同时在楼下建了一排充电桩。充电桩每度电收费一块五，比家里贵三倍。物业称，此举能彻底消除火灾隐患。居民大多上班族，晚上十点后才回家。"

    private fun e(text: String, crux: String, id: Long = 1) =
        Endgame(id = id, text = text, crux = crux, ask = "", aim = "", shot = "s", source = EndgameSource.MATERIAL, sourceKey = "m:$id", createdAt = 0)

    @Test
    fun segmentsKeepPunctuation() {
        val segs = Endgames.segments("甲，乙。丙！")
        assertEquals(listOf("甲，", "乙。", "丙！"), segs.map { it.text })
        assertEquals(0, segs[0].start)
        assertEquals(6, segs.last().end)
        // 结尾的引号跟着上一句
        assertEquals(listOf("他说：", "“好。”", "然后走了"), Endgames.segments("他说：“好。”然后走了").map { it.text })
    }

    @Test
    fun hitAndNarrow() {
        val g = e(source, "能彻底消除火灾隐患")
        val segs = Endgames.segments(g.text)
        val c = Endgames.cruxSegs(g, segs)
        assertEquals(1, c.size)
        assertTrue(Endgames.isHit(g, segs, c.first()))
        assertFalse(Endgames.isHit(g, segs, 0))
        val dim = Endgames.dimmed(g, segs, setOf(0))
        assertTrue(0 in dim)
        assertFalse(c.first() in dim)
        // 只留三句亮着
        assertEquals(3, segs.indices.count { it !in dim })
        // 窗口位置按编号错开，不总是正中间
        val windows = (0L..2L).map { id -> segs.indices.filter { it !in Endgames.dimmed(g.copy(id = id), segs, setOf(0)) } }
        assertTrue(windows.toSet().size > 1)
    }

    @Test
    fun scheduleAndPick() {
        val now = 10_000_000_000L
        val hit = Endgames.after(e(source, "彻底", 1), firstHit = true, now = now)
        assertEquals(1, hit.hits)
        assertTrue(hit.dueAt - now >= 86_400_000L)
        val miss = Endgames.after(e(source, "彻底", 2), firstHit = false, now = now)
        assertTrue(miss.dueAt - now < 86_400_000L)
        val fresh = e(source, "彻底", 3)
        val later = now + 2 * 86_400_000L
        // 上次没中的先回来，其次没见过的
        assertEquals(2L, Endgames.pick(listOf(hit, fresh, miss), later)?.id)
        assertEquals(3L, Endgames.pick(listOf(hit, fresh), later)?.id)
        assertNull(Endgames.pick(listOf(hit), now))
    }

    @Test
    fun cardBecomesEndgameLocally() {
        val card = Card(
            Skill.DETAIL, 2,
            listOf("近年县城咖啡店增加很快。", "多数店开在新区商业街。", "调查显示，新区居民以返乡青年为主。", "老城区的店半年内关了一半。", "店主普遍认为租金是主因。"),
            "最能解释新区咖啡店存活更久的是？",
            listOf("租金低", "返乡青年有喝咖啡的习惯", "新区交通好", "老城区人少"),
            1, setOf(3), emptyMap(), "依据第 3 句，客群决定存活。",
        )
        val g = Endgames.fromCard(7, card, 0)
        assertNotNull(g)
        g!!
        assertTrue(g.text.contains("返乡青年为主"))
        assertEquals("调查显示，新区居民以返乡青年为主。", g.crux)
        assertTrue(g.text.length <= Endgames.MAX_TEXT)
        assertTrue(g.shot.startsWith("答案 B"))
        assertEquals("card:7", g.sourceKey)
        // 没有依据句的题不变
        assertNull(Endgames.fromCard(8, card.copy(evidence = emptySet()), 0))
    }

    @Test
    fun draftsMustBeOriginalText() {
        val ok = Endgames.Draft("严禁电动车飞线充电，同时在楼下建了一排充电桩。充电桩每度电收费一块五，比家里贵三倍。", "比家里贵三倍", "看钱", "贵三倍，居民会绕开：凌晨拎电池上楼。", "堵了前门 + 利润仍在 = 必走后门")
        assertNull(Endgames.problem(source, ok))
        val made = ok.copy(text = "小区规定所有住户必须在指定地点充电，否则罚款五百元，居民怨声载道，物业坚持不改。", crux = "罚款五百元")
        assertEquals("残局不是原文", Endgames.problem(source, made))
        assertEquals("破绽不在残局里", Endgames.problem(source, ok.copy(crux = "罚款五百")))
        val glued = ok.copy(text = "严禁电动车飞线充电，同时在楼下建了一排充电桩。……物业称，此举能彻底消除火灾隐患。", crux = "能彻底消除火灾隐患")
        assertNull(Endgames.problem(source, glued))
    }

    @Test
    fun parseTaggedEndgames() {
        val text = """
            【残局】严禁电动车飞线充电，同时在楼下建了一排充电桩。充电桩每度电收费一块五，比家里贵三倍。
            【破绽】比家里贵三倍
            【准星】看谁在付钱
            【底牌】贵三倍，居民会改在凌晨偷充
            【铁律】堵前门 + 利润在 = 必走后门
            ---
            【残局】只有一句
            【破绽】「一句」
        """.trimIndent()
        val ds = EndgamePrompts.parse(text)
        assertEquals(2, ds.size)
        assertEquals("比家里贵三倍", ds[0].crux)
        assertEquals("一句", ds[1].crux)
        assertEquals("堵前门 + 利润在 = 必走后门", ds[0].law)
    }
}

class BrakeTest {

    @Test
    fun localSplitSeparatesFantasy() {
        val s = Brake.localSplit("他今天没回我消息，他肯定是故意的，看不起我，我要马上去问清楚")
        assertEquals(listOf("他今天没回我消息"), s.facts)
        assertEquals(3, s.fantasies.size)
        assertTrue(Brake.biggest(s, "").contains("故意") || Brake.biggest(s, "").contains("看不起") || Brake.biggest(s, "").contains("问清楚"))
        assertFalse(Brake.worstOk("嗯"))
        assertTrue(Brake.worstOk("吵起来删好友"))
    }

    @Test
    fun parseCoachCut() {
        val c = BrakePrompts.parse("【事实】\n他没回消息\n【脑补】\n1. 他是故意的\n2. 他看不起我\n【证据】他亲口说过不想理你")
        assertNotNull(c)
        assertEquals(listOf("他没回消息"), c!!.split.facts)
        assertEquals(listOf("他是故意的", "他看不起我"), c.split.fantasies)
        assertEquals("他亲口说过不想理你", c.proof)
        assertNull(BrakePrompts.parse("好的"))
    }

    @Test
    fun tallyLine() {
        fun i(d: String, h: Boolean?) = com.yishou.app.data.Impulse(raw = "x", decision = d, createdAt = 0, happened = h)
        val t = Brake.tally(listOf(i("hold", null), i("go", true), i("go", false)))
        assertEquals(1, t.held)
        assertEquals(2, t.checked)
        assertTrue(Brake.line(t).contains("1/2"))
    }
}

class ParrotTest {
    @Test
    fun copyingTheTitleDoesNotCount() {
        assertTrue(Lines.parrot("资料分析", "资料分析", "资料分析", Ask.SMALLER, 6))
        assertTrue(Lines.parrot("资料分析", "资料分析怎么提速", "资料分析", Ask.GUESS, 1))
        assertFalse(Lines.parrot("速算", "资料分析怎么提速", "资料分析", Ask.SMALLER, 6))
        // “哪个词最拿不准”本来就要从标题里挑
        assertFalse(Lines.parrot("提速", "资料分析怎么提速", "资料分析", Ask.SMALLER, 4))
    }
}
