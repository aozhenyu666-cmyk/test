package io.github.ccch1mneyyy.dsh_app.loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B4 连发的手机侧定案：插件说什么（`extra.burst`）→ 真的分几段、每段多长、隔多久。
 *
 * 这里只测纯函数。按 delayMs 真投递在 `ProactiveRuntime.scheduleBurst`（要 Handler 和窗口，
 * 本机跑不了），所以刻意让分段计划完全可测：段错了、间隔错了、把一句话从中间截断了，
 * 都在这一层红，而不是等真机上听着不连贯才发现。
 */
class BurstPlanTest {

    private fun burst(vararg pairs: Pair<String, Any?>) = mapOf<String, Any?>("extra" to mapOf("burst" to mapOf(*pairs)))

    private fun plan(text: String, vararg pairs: Pair<String, Any?>) = BurstPlan.of(burst(*pairs)["extra"], text)

    @Test fun noBurstBlockMeansOneMessage() {
        assertNull("没有 extra", BurstPlan.of(null, "第一句。第二句。"))
        assertNull("extra 里没有 burst", BurstPlan.of(mapOf("speaker" to "strategist"), "第一句。第二句。"))
        assertNull("burst 不是对象", BurstPlan.of(mapOf("burst" to 3), "第一句。第二句。"))
    }

    @Test fun oneSegmentIsNotABurst() {
        assertNull(plan("第一句。第二句。", "burst" to 1L, "maxChars" to 40L))
        // burst 说 0 或负数：按 1 段算，不排队。
        assertNull(plan("第一句。第二句。", "burst" to 0L))
    }

    @Test fun threeSentencesBecomeThreeSegmentsInOrder() {
        val p = plan("你又跑了。我说了多少次。行吧。", "burst" to 3L, "maxChars" to 40L)!!
        assertEquals(listOf("你又跑了。", "我说了多少次。", "行吧。"), p.segments)
        // 段数 N 就有 N-1 个间隔。
        assertEquals(2, p.delaysMs.size)
    }

    @Test fun segmentsNeverCutASentenceInHalf() {
        val text = "这一句比较长，长到超过上限也不能从中间截断。短句。"
        val p = plan(text, "burst" to 2L, "maxChars" to 20L)!!
        // 第一段整句超长也保住，不许切成"这一句比较长，长到超"。
        assertEquals("这一句比较长，长到超过上限也不能从中间截断。", p.segments[0])
        assertEquals("短句。", p.segments[1])
        assertTrue(p.segments.all { it.isNotEmpty() })
    }

    @Test fun leftOverSentencesAreAbsorbedByTheLastSegment() {
        val p = plan("一。二。三。四。五。", "burst" to 3L, "maxChars" to 200L)!!
        assertEquals(3, p.segments.size)
        // 一句话都不许丢。
        assertEquals("一。二。三。四。五。", p.segments.joinToString(""))
    }

    @Test fun moreSentencesThanSlotsStillKeepsEverything() {
        val p = plan("一。二。三。四。", "burst" to 2L, "maxChars" to 200L)!!
        assertEquals(2, p.segments.size)
        assertEquals("一。二。三。四。", p.segments.joinToString(""))
    }

    @Test fun delaysComeFromPluginAndDefaultWhenMissing() {
        val given = plan("一。二。三。", "burst" to 3L, "delayMs" to listOf(3_000L, 8_000L))!!
        assertEquals(listOf(3_000L, 8_000L), given.delaysMs)

        val short = plan("一。二。三。", "burst" to 3L, "delayMs" to listOf(5_000L))!!
        assertEquals(listOf(5_000L, BurstPlan.DEFAULT_DELAY_MS), short.delaysMs)

        val none = plan("一。二。", "burst" to 2L)!!
        assertEquals(listOf(BurstPlan.DEFAULT_DELAY_MS), none.delaysMs)
    }

    @Test fun nonsenseDelaysAreClampedNotTrusted() {
        val p = plan("一。二。三。", "burst" to 3L, "delayMs" to listOf(-1L, 999_999L))!!
        assertEquals(0L, p.delaysMs[0])
        assertEquals(30_000L, p.delaysMs[1])
        // 垃圾项不至于让整条计划失效。
        val junk = plan("一。二。三。", "burst" to 3L, "delayMs" to listOf("x", null, 4_000L))!!
        assertEquals(listOf(4_000L, BurstPlan.DEFAULT_DELAY_MS), junk.delaysMs)
    }

    @Test fun charLimitIsClampedToSomethingUsable() {
        // 上限写 1 会把每句都撑成一段，钳到 12 后仍然按句切。
        val p = plan("一二三四五六七八九十。十一十二。", "burst" to 2L, "maxChars" to 1L)!!
        assertEquals(2, p.segments.size)
        assertEquals("一二三四五六七八九十。", p.segments[0])
    }

    @Test fun channelDoesNotGateBursting() {
        // 插件把火气最高的三档（hot/blaze/eager）标成 voice——按「voice 不连发」拦会让 B4 的核心
        // 场景（火气高 → 连发 2–3 条）永远不触发。voice 只表示"还要念出来"（B5），这里照样分段。
        assertEquals(3, plan("一。二。三。", "burst" to 3L, "channel" to "voice")!!.segments.size)
        assertEquals(3, plan("一。二。三。", "burst" to 3L, "channel" to "text")!!.segments.size)
        // 插件真实会给出的形状：blaze = 3 段 + voice + [3000,5000]。
        val blaze = plan(
            "你又跑了。我说了多少次。行吧。",
            "tone" to "blaze", "burst" to 3L, "maxChars" to 24L,
            "delayMs" to listOf(3_000L, 5_000L), "channel" to "voice",
        )!!
        assertEquals(3, blaze.segments.size)
        assertEquals(listOf(3_000L, 5_000L), blaze.delaysMs)
    }

    @Test fun cannotSplitMeansNoBurst() {
        assertNull("只有一句", plan("就一句没标点", "burst" to 3L))
        assertNull("空文本", plan("", "burst" to 3L))
        assertNull("只有标点", plan("。。。", "burst" to 3L))
    }

    @Test fun maxSegmentsIsThree() {
        val p = plan("一。二。三。四。五。六。", "burst" to 9L, "maxChars" to 200L)!!
        assertEquals(BurstPlan.MAX_SEGMENTS, p.segments.size)
    }

    @Test fun wireParsesThePlanOutOfTheResultBody() {
        val body = mapOf(
            "v" to 1L,
            "state" to "generated",
            "text" to "你又跑了。我说了多少次。",
            "turn" to 7L,
            "extra" to mapOf(
                "speaker" to "strategist",
                "burst" to mapOf("tone" to "hot", "burst" to 2L, "maxChars" to 40L, "delayMs" to listOf(3_000L), "channel" to "text"),
            ),
        )
        val reply = (AgentWire.decode(200, body) as AgentWire.Decoded.Reply).reply as AgentReply.Generated
        val p = reply.burst!!
        assertEquals(listOf("你又跑了。", "我说了多少次。"), p.segments)
        assertEquals(listOf(3_000L), p.delaysMs)
        assertEquals("turn:7", reply.turnId)
    }

    @Test fun wireWithoutBurstStillDecodesAsBefore() {
        val reply = (AgentWire.decode(200, mapOf("v" to 1L, "state" to "generated", "text" to "你好", "turn" to 1L)) as AgentWire.Decoded.Reply).reply
        // 老 body（没有 extra）解码结果一字没变。
        assertEquals(AgentReply.Generated("你好", "turn:1"), reply)
    }

    @Test fun coordinatorCarriesThePlanIntoThePresentCommand() {
        // 计划要能穿过 coordinator 到投递那一层：解码处对了、Present 里丢了，真机上还是一整段。
        val now = 1_700_000_000_000L
        var seq = 0
        val c = Coordinator(MemorySnapshotStore(), CoordinatorConfig(), { now }, { "id${++seq}" })
        c.recover(Gate(dshTaskActive = true))
        c.submit(EventRequest("a", "TEST", "chat", "a", Priority.NORMAL, now, 600_000, true, "hi"))
        val rq = c.setGate(Gate()).commands.filterIsInstance<Command.RequestAgent>().single()
        val plan = BurstPlan(listOf("一。", "二。"), listOf(1_000L))
        val shown = c.onAgent(rq.requestId, AgentReply.Generated("一。二。", "turn:1", plan))
        val presented = shown.commands.filterIsInstance<Command.Present>().single()
        assertEquals(plan, presented.content.burst)
        // 正文仍是全文，分段只是"怎么发"，不是"发什么"。
        assertEquals("一。二。", presented.content.text)
    }
}
