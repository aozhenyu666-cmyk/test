package com.yishou.app.thread

import com.yishou.app.judge.LockedJson
import com.yishou.app.judge.Verdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrandTest {

    private fun made(): Strand {
        val r = StrandEngine.create(
            goal = "周六去爬山，几点出发",
            doneWhen = "定下一个具体的出发时刻，并且所有人都能到",
            items = listOf("山上周六哪个时段会下雨", "每个人最早几点能出门", "回程末班车几点"),
            now = 0,
        )
        return (r as StrandEngine.Created.Ok).strand
    }

    private val pass = Verdict(mapOf("P1" to "x"), offTopic = false, passed = true, note = "过了")
    private val miss = Verdict(mapOf("P1" to null), offTopic = false, passed = false, note = "没过")
    private val off = Verdict(emptyMap(), offTopic = true, passed = false, note = "跑题")
    private val down = Verdict(emptyMap(), offTopic = false, passed = false, note = "不在", unavailable = true)

    private val serveReply = { id: String ->
        """
        【项】$id
        【题目】周六山上的天气预报，雨落在一天里的哪几个小时？
        【要点】
        1. 说出降雨开始和结束的具体钟点 | 看小时预报
        2. 说出这段雨对上山路段的影响 | 路会怎样
        【参考】……
        """.trimIndent()
    }

    private fun serve(s: Strand, reply: String?): Strand {
        val p = runBlocking { StrandEngine.serve(s, { _, _ -> reply }, 1) }
        assertNotNull(p)
        return s.copy(pending = p)
    }

    @Test
    fun createNeedsOwnStandard() {
        assertTrue(StrandEngine.create("周六去爬山几点出发", "看情况", listOf("下雨吗", "人齐吗"), 0) is StrandEngine.Created.Bad)
        assertTrue(StrandEngine.create("周六去爬山几点出发", "周六去爬山几点出发", listOf("山上下不下雨", "大家几点能到"), 0) is StrandEngine.Created.Bad)
        assertTrue(StrandEngine.create("天气", "定下一个具体时刻", listOf("山上下不下雨", "大家几点能到"), 0) is StrandEngine.Created.Bad)
        assertEquals("I1", made().current!!.id)
    }

    @Test
    fun weatherDriftIsPulledBackWithinTwoMoves() {
        // 以前：“今天天气真好”→“雨天不好”→“为什么不好”→……一直往下聊。现在两手没推进就拉回
        var s = serve(made(), serveReply("I1"))
        s = StrandEngine.apply(s, "今天天气真好啊，晴天心情就好", off, 2)
        assertEquals(MoveKind.STEP, StrandEngine.nextKind(s))
        s = serve(s, serveReply("I1"))
        s = StrandEngine.apply(s, "雨天就不好，路上滑，衣服也湿", miss, 3)
        assertEquals(MoveKind.PULL_BACK, StrandEngine.nextKind(s))
        s = serve(s, null) // 拉回是本机出的，不用模型
        assertEquals(MoveKind.PULL_BACK, s.pending!!.kind)
        assertTrue(s.pending!!.q.prompt.contains("山上周六哪个时段会下雨"))
        assertTrue(s.pending!!.q.prompt.contains("雨天就不好"))
        // 拉回没过：还是拉回
        s = StrandEngine.apply(s, "雨天确实不好", miss, 4)
        assertEquals(MoveKind.PULL_BACK, StrandEngine.nextKind(s))
        s = serve(s, null)
        s = StrandEngine.apply(s, "下雨会让石阶路滑，所以要知道雨几点停，停了一小时后再上山", pass, 5)
        assertEquals(MoveKind.STEP, StrandEngine.nextKind(s))
    }

    @Test
    fun serveMustTargetCurrentItem() {
        val s = made()
        // 模型出的题标成了 I2（不是当前项）：两次都不对就不出
        val p = runBlocking { StrandEngine.serve(s, { _, _ -> serveReply("I2") }, 1) }
        assertNull(p)
        // 模型不在
        assertNull(runBlocking { StrandEngine.serve(s, { _, _ -> null }, 1) })
    }

    @Test
    fun serveDoesNotSeeTheWholeChat() {
        var s = serve(made(), serveReply("I1"))
        s = StrandEngine.apply(s, "我昨天看了一部电影，主角也去爬山了，挺好看的", off, 2)
        val user = StrandPrompts.serveUser(s, s.current!!, MoveKind.STEP)
        assertFalse(user.contains("电影"))
    }

    @Test
    fun itemSettlesOnlyByOwnConclusion() {
        var s = made()
        repeat(2) {
            s = serve(s, serveReply("I1"))
            s = StrandEngine.apply(s, "预报说十点到下午两点有雨", pass, 2)
        }
        assertEquals(MoveKind.CLOSE, StrandEngine.nextKind(s))
        s = serve(s, serveReply("I1"))
        s = StrandEngine.apply(s, "雨十点开始，所以要九点前到山顶或者下午三点后再上", pass, 3)
        assertEquals("I2", s.current!!.id)
        assertEquals("雨十点开始，所以要九点前到山顶或者下午三点后再上", s.items[0].conclusion)
    }

    @Test
    fun externalSummaryStillNeedsOwnClose() {
        var s = StrandEngine.startReturn(made(), 1)
        assertTrue(s.pending!!.q.allowPaste)
        s = StrandEngine.apply(s, "结论：九点前登顶\n依据：十点下雨\n还缺：末班车", pass, 2)
        assertEquals(ItemState.OPEN, s.items[0].state)
        assertEquals(MoveKind.CLOSE, StrandEngine.nextKind(s))
    }

    @Test
    fun judgeDownKeepsTheQuestion() {
        val s = serve(made(), serveReply("I1"))
        val after = StrandEngine.apply(s, "预报说十点下雨", down, 2)
        assertEquals(s, after)
    }

    @Test
    fun finalAfterAllSettled() {
        var s = made()
        s = s.copy(items = s.items.map { it.copy(state = ItemState.SETTLED, conclusion = "定了") })
        assertEquals(MoveKind.FINAL, StrandEngine.nextKind(s))
        s = serve(s, null)
        s = StrandEngine.apply(s, "七点半出发，七点在地铁站集合", pass, 9)
        assertTrue(s.done)
    }

    @Test
    fun breakpointSurvivesRestart() {
        val s = serve(made(), serveReply("I1")).copy(id = 7)
        val back = Strand.fromJson(7, s.toJson())
        assertEquals(s, back)
        assertEquals(s.pending!!.q, LockedJson.fromJson(LockedJson.toJson(s.pending!!.q)))
    }

    @Test
    fun handoffAndReturn() {
        val s = made()
        val t = Handoff.text(s)
        assertTrue(t.contains("每次只问我一个问题"))
        assertTrue(t.contains("山上周六哪个时段会下雨"))
        val sum = Handoff.parse("好的。\n结论：九点前登顶\n依据：预报十点下雨\n还缺：末班车")
        assertEquals("九点前登顶", sum!!.conclusion)
        assertNull(Handoff.parse("聊得很开心"))
    }

    @Test
    fun itemsParse() {
        val items = StrandPrompts.parseItems("好的：\n[I1] 山上几点下雨\n[I2] 大家几点能到\n【I3】回程末班车几点\n说明：无")
        assertEquals(3, items.size)
    }
}
