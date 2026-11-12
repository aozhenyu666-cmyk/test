package io.github.ccch1mneyyy.dsh_app.loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5 语音的手机侧定案：插件给什么（`extra.voice`）→ 念哪一段、多快、多高。
 *
 * 只测纯函数。真的出声在 `proactive/VoiceOut.kt`（要 TextToSpeech，本机跑不了），所以把
 * 「念什么」全挤到这一层：裁错字、钳错范围、把调度计划丢了，都在这里红，而不是等真机上
 * 听见她念半句话才发现。
 */
class VoicePlanTest {

    private fun voice(vararg pairs: Pair<String, Any?>) =
        mapOf<String, Any?>("extra" to mapOf("voice" to mapOf(*pairs)))

    private fun plan(text: String, vararg pairs: Pair<String, Any?>) = VoicePlan.of(voice(*pairs)["extra"], text)

    @Test fun noVoiceBlockMeansSilent() {
        assertNull("没有 extra", VoicePlan.of(null, "今天晚上有空吗？"))
        assertNull("extra 里没有 voice", VoicePlan.of(mapOf("speaker" to "strategist"), "今天晚上有空吗？"))
        assertNull("voice 不是对象", VoicePlan.of(mapOf("voice" to true), "今天晚上有空吗？"))
    }

    @Test fun blankTextIsSilent() {
        assertNull("空文本", plan("", "rate" to 1.0))
        assertNull("只有空白", plan("   \n  ", "rate" to 1.0))
    }

    @Test fun shortTextIsKeptWhole() {
        val p = plan("你又跑了。", "rate" to 1.15, "pitch" to 0.9)!!
        assertEquals("你又跑了。", p.text)
        assertEquals(1.15, p.rate.toDouble(), 0.0001)
        assertEquals(0.9, p.pitch.toDouble(), 0.0001)
    }

    @Test fun missingRateAndPitchAreNormalVoice() {
        // 插件没给调子不等于"别念"：那是默认的 1.0，不是 0。
        val p = plan("在吗？")!!
        assertEquals(VoicePlan.DEFAULT_RATE.toDouble(), p.rate.toDouble(), 0.0001)
        assertEquals(VoicePlan.DEFAULT_PITCH.toDouble(), p.pitch.toDouble(), 0.0001)
    }

    @Test fun onlyFirstSentenceIsSpokenWhenItIsTooLong() {
        // 超过 60 字才截；截在第一个句读点上，后面那句只上屏。
        val p = plan("你今天几点睡的。" + "我猜又是三点，".repeat(10) + "。", "rate" to 1.0)!!
        assertEquals("你今天几点睡的。", p.text)
    }

    @Test fun longFirstSentenceIsCutAtSixtyChars() {
        val long = "这".repeat(200) + "。"
        val p = plan(long)!!
        assertEquals(VoicePlan.MAX_CHARS, p.text.length)
    }

    @Test fun cutPrefersASentenceBreakOverACleanSixtyCharSlash() {
        // 60 字处是"我说了多少次"中间，往前有句号就截在句号上。
        val head = "一".repeat(30) + "。"
        val tail = "二".repeat(100)
        val p = plan(head + tail)!!
        assertEquals(head, p.text)
    }

    @Test fun rateAndPitchAreClampedBothEnds() {
        // 0 / 负数会让 TextToSpeech 直接静音，太大变花栗鼠。
        val low = plan("在吗？", "rate" to 0.0, "pitch" to -1.0)!!
        assertEquals(0.5, low.rate.toDouble(), 0.0001)
        assertEquals(0.5, low.pitch.toDouble(), 0.0001)
        val high = plan("在吗？", "rate" to 9.0, "pitch" to 99.0)!!
        assertEquals(2.0, high.rate.toDouble(), 0.0001)
        assertEquals(2.0, high.pitch.toDouble(), 0.0001)
    }

    @Test fun junkNumbersFallBackInsteadOfKillingTheWholeBall() {
        val p = plan("在吗？", "rate" to "快一点", "pitch" to null)!!
        assertEquals(VoicePlan.DEFAULT_RATE.toDouble(), p.rate.toDouble(), 0.0001)
        assertEquals(VoicePlan.DEFAULT_PITCH.toDouble(), p.pitch.toDouble(), 0.0001)
        assertTrue(p.text.isNotEmpty())
    }

    @Test fun maxCharsIsSixty() {
        assertEquals(60, VoicePlan.MAX_CHARS)
    }

    @Test fun wireParsesVoiceOutOfTheResultBody() {
        val body = mapOf(
            "v" to 1L,
            "state" to "generated",
            "text" to "你又跑了。我说了多少次。",
            "turn" to 7L,
            "extra" to mapOf(
                "burst" to mapOf("burst" to 2L, "maxChars" to 40L),
                "voice" to mapOf("rate" to 1.15, "pitch" to 0.9, "maxChars" to 60L),
            ),
        )
        val reply = (AgentWire.decode(200, body) as AgentWire.Decoded.Reply).reply as AgentReply.Generated
        val v = reply.voice!!
        assertEquals("你又跑了。我说了多少次。", v.text)
        assertEquals(1.15, v.rate.toDouble(), 0.0001)
        assertEquals(0.9, v.pitch.toDouble(), 0.0001)
        // 连发和语音互不依赖：同一轮可以既分段又念。
        assertNotNull(reply.burst)
        assertEquals(listOf("你又跑了。", "我说了多少次。"), reply.burst!!.segments)
    }

    @Test fun voiceDoesNotNeedABurstPlan() {
        // 每小时连发额度只剩 1 条时插件给的是 burst=null + voice 有值——这条照样要念。
        val body = mapOf(
            "v" to 1L,
            "state" to "generated",
            "text" to "在吗？",
            "turn" to 1L,
            "extra" to mapOf("voice" to mapOf("rate" to 1.0)),
        )
        val reply = (AgentWire.decode(200, body) as AgentWire.Decoded.Reply).reply as AgentReply.Generated
        assertNull(reply.burst)
        assertNotNull(reply.voice)
    }

    @Test fun wireWithoutVoiceStillDecodesAsBefore() {
        val reply = (AgentWire.decode(200, mapOf("v" to 1L, "state" to "generated", "text" to "你好", "turn" to 1L))
            as AgentWire.Decoded.Reply).reply
        // 老 body（没有 extra）解码结果一字没变——B5 不动 B4 和老路径。
        assertEquals(AgentReply.Generated("你好", "turn:1"), reply)
    }

    @Test fun coordinatorCarriesVoiceIntoThePresentCommand() {
        val now = 1_700_000_000_000L
        var seq = 0
        val c = Coordinator(MemorySnapshotStore(), CoordinatorConfig(), { now }, { "id${++seq}" })
        c.recover(Gate(dshTaskActive = true))
        c.submit(EventRequest("a", "TEST", "chat", "a", Priority.NORMAL, now, 600_000, true, "hi"))
        val rq = c.setGate(Gate()).commands.filterIsInstance<Command.RequestAgent>().single()
        val plan = VoicePlan("一。", 1.15f, 0.9f)
        val shown = c.onAgent(rq.requestId, AgentReply.Generated("一。二。", "turn:1", null, plan))
        val presented = shown.commands.filterIsInstance<Command.Present>().single()
        assertEquals(plan, presented.content.voice)
        // 正文仍是全文，念哪一段是投递方式，不是发什么。
        assertEquals("一。二。", presented.content.text)
    }
}
