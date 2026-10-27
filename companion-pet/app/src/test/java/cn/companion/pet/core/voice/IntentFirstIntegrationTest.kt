package cn.companion.pet.core.voice

import cn.companion.pet.core.moment.Moment
import cn.companion.pet.core.moment.MomentSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** 接线后的核心行为：手里有话时优先说手里的话，而不是走规则临时编。 */
class IntentFirstIntegrationTest {

    private val zone = ZoneOffset.UTC
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun signals(now: Long, typing: Boolean = false, own: Boolean = true) = MomentSignals(
        now = now, ownThoughtReady = own, typing = typing, zone = zone,
    )

    @Test
    fun `有意图时优先取意图，没有时才交给规则`() {
        val q = VoiceIntentQueue()
        q.enqueue("own_life", "我今天读到时间尺度那段", listOf("e1"), now = 0L)
        val ledger = SayingLedger()
        val picked = q.next(now = 1L) { ledger.isTooSimilar(it) }
        assertEquals("应当优先说手里攒的事", "我今天读到时间尺度那段", picked?.summary)
        q.markSpoken(picked!!.id, now = 2L)
        assertNull("说完就没有了", q.next(now = 3L) { ledger.isTooSimilar(it) })
    }

    @Test
    fun `重复的句子进不来`() {
        val ledger = SayingLedger()
        ledger.record("你刚才切了好几个应用，在找什么？", now = 1L)
        val q = VoiceIntentQueue()
        q.enqueue("rule", "你刚才切了好几个应用，在找什么？", listOf("e1"), now = 2L)
        assertNull("与刚说过的太像，不应被挑中", q.next(now = 3L) { ledger.isTooSimilar(it) })
    }

    // ---- 下面三条钉住真正接线的那个决策：意图优先，但闭嘴优先于意图 ----

    @Test
    fun `手里有话且时刻成立时说手里的话`() {
        val now = at("2026-10-26T10:00:00Z")
        // 入队时间必须贴着 now：否则 6 小时 TTL 早就过了，测试会因为「过期」而假绿。
        val q = VoiceIntentQueue().apply { enqueue("own_life", "我刚读到一句挺反直觉的", listOf("e1"), now = now - 1_000L) }
        val plan = VoiceTurnPlanner.plan(now, signals(now), q, SayingLedger())
        assertTrue(plan is TurnPlan.SpeakIntent)
        assertEquals("我刚读到一句挺反直觉的", (plan as TurnPlan.SpeakIntent).text)
        assertTrue("时刻要一起交出去，调用方才能记账", plan.moments.contains(Moment.OWN_THOUGHT_READY))
    }

    @Test
    fun `正在打字时哪怕手里有话也闭嘴`() {
        val now = at("2026-10-26T10:00:00Z")
        val q = VoiceIntentQueue().apply { enqueue("own_life", "我刚读到一句挺反直觉的", listOf("e1"), now = now - 1_000L) }
        val plan = VoiceTurnPlanner.plan(now, signals(now, typing = true), q, SayingLedger())
        assertEquals("闭嘴优先于意图", TurnPlan.StaySilent, plan)
        assertEquals("闭嘴时不许把话划掉，留着下次说", 1, q.all().count { it.spokenAt == null })
    }

    @Test
    fun `没有意图也没有时刻时交回规则，而不是硬说`() {
        val now = at("2026-10-26T10:00:00Z")
        val plan = VoiceTurnPlanner.plan(now, signals(now, own = false), VoiceIntentQueue(), SayingLedger())
        assertEquals(TurnPlan.StaySilent, plan)
    }
}
