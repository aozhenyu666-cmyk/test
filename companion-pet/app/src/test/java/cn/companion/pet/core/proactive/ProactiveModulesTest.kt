package cn.companion.pet.core.proactive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 情境触发：任何情况下都必须有可说的话题（对应"没有待办也应有闲聊机会"）。 */
class ContextTriggerEngineTest {

    private fun signals(
        minutesSinceUserReply: Long? = null,
        unresolved: Boolean = false,
        minutesUntilAppointment: Long? = null,
        justLeftFocus: Boolean = false,
        justCompleted: Boolean = false,
        idleTopics: Int = 0,
    ) = ContextSignals(
        nowMillis = 0L,
        minutesSinceUserReply = minutesSinceUserReply,
        hasUnresolvedQuestion = unresolved,
        minutesUntilAppointment = minutesUntilAppointment,
        justLeftFocus = justLeftFocus,
        justCompletedTask = justCompleted,
        idleTopicCount = idleTopics,
    )

    @Test
    fun `完全没有信号时仍有闲聊候选`() {
        val c = ContextTriggerEngine.candidates(signals())
        assertTrue("没有任何候选，引擎会静默到像死了", c.isNotEmpty())
        assertTrue("缺少兜底闲聊", c.any { it.trigger == ProactiveTrigger.LIGHT_SMALL_TALK })
    }

    @Test
    fun `七种情境各自能产生候选`() {
        assertTrue(ContextTriggerEngine.candidates(signals(justCompleted = true)).any { it.trigger == ProactiveTrigger.TASK_COMPLETED })
        assertTrue(ContextTriggerEngine.candidates(signals(minutesSinceUserReply = 60)).any { it.trigger == ProactiveTrigger.LONG_SILENCE })
        assertTrue(ContextTriggerEngine.candidates(signals(unresolved = true)).any { it.trigger == ProactiveTrigger.UNRESOLVED_QUESTION })
        assertTrue(ContextTriggerEngine.candidates(signals(minutesUntilAppointment = 5)).any { it.trigger == ProactiveTrigger.APPOINTMENT_DUE })
        assertTrue(ContextTriggerEngine.candidates(signals(idleTopics = 2)).any { it.trigger == ProactiveTrigger.OLD_TOPIC_REVIVAL })
        assertTrue(ContextTriggerEngine.candidates(signals(justLeftFocus = true)).any { it.trigger == ProactiveTrigger.FOCUS_ENDED })
        assertTrue(ContextTriggerEngine.candidates(signals()).any { it.trigger == ProactiveTrigger.LIGHT_SMALL_TALK })
    }

    @Test
    fun `约定只在临近时才提醒`() {
        val far = ContextTriggerEngine.candidates(signals(minutesUntilAppointment = 120))
        assertFalse("还早的约定不该提醒", far.any { it.trigger == ProactiveTrigger.APPOINTMENT_DUE })
    }

    @Test
    fun `高优先级排在前面`() {
        val c = ContextTriggerEngine.candidates(signals(unresolved = true, minutesSinceUserReply = 60))
        assertEquals(ProactiveTrigger.UNRESOLVED_QUESTION, c.first().trigger)
    }
}

/** 自主话题：形式轮换 + 锚点去重 + 无记忆也有话说。 */
class AutonomousConversationEngineTest {

    @Test
    fun `没有记忆时也能产出通用闲聊`() {
        val eng = AutonomousConversationEngine(Random(1))
        val sel = eng.select(ProactiveTrigger.LIGHT_SMALL_TALK, emptyList())
        assertTrue("应当给出指令", sel.instruction.isNotBlank())
        assertEquals("无锚点时应为空", "", sel.anchor)
        assertTrue("无素材时应说明不要问在干嘛", sel.instruction.contains("在干嘛"))
    }

    @Test
    fun `同一个锚点不会连续复用`() {
        val eng = AutonomousConversationEngine(Random(3))
        val anchors = listOf("话题A", "话题B")
        val picked = (1..6).map { eng.select(ProactiveTrigger.OLD_TOPIC_REVIVAL, anchors).anchor }
        // 连续两次不该是同一个锚点
        picked.zipWithNext().forEach { (a, b) ->
            assertFalse("连续复用了同一个锚点 $a", a.isNotBlank() && a == b)
        }
    }

    @Test
    fun `话题形式会轮换不会一直同一种`() {
        val eng = AutonomousConversationEngine(Random(5))
        val shapes = (1..12).map { eng.select(ProactiveTrigger.LIGHT_SMALL_TALK, emptyList()).shape }
        assertTrue("12 次里只出现了 ${shapes.distinct().size} 种形式，太单调", shapes.distinct().size >= 3)
    }

    @Test
    fun `快照恢复后继续避开旧锚点`() {
        val eng = AutonomousConversationEngine(Random(7))
        eng.select(ProactiveTrigger.OLD_TOPIC_REVIVAL, listOf("唯一话题"))
        val snap = eng.snapshot()
        val restored = AutonomousConversationEngine(Random(8))
        restored.restore(snap)
        val sel = restored.select(ProactiveTrigger.OLD_TOPIC_REVIVAL, listOf("唯一话题"))
        assertEquals("恢复后不应立刻复用旧锚点", "", sel.anchor)
    }
}

/** 互动仲裁：退避有地板，预约提醒不被闲聊退避挤掉。 */
class InteractionPolicyTest {

    @Test
    fun `频繁回应会提高机会`() {
        val v = InteractionPolicy.arbitrate(InteractionSignals(repliesLastHour = 8), TalkAvenue.RANDOM)
        assertTrue("回应频繁时应当更活跃（factor=${v.rateFactor}）", v.rateFactor > 1.0)
    }

    @Test
    fun `连续不回应会退避但有地板不归零`() {
        val v = InteractionPolicy.arbitrate(InteractionSignals(consecutiveIgnored = 4), TalkAvenue.RANDOM)
        assertTrue("应当退避", v.rateFactor < 1.0)
        assertTrue("退避不能压到 0（否则等于以后都不说了）", v.rateFactor >= InteractionPolicy.MIN_FACTOR)
    }

    @Test
    fun `忽略太多时本轮直接放弃`() {
        val v = InteractionPolicy.arbitrate(InteractionSignals(consecutiveIgnored = 8), TalkAvenue.RANDOM)
        assertFalse(v.allowed)
    }

    @Test
    fun `用户要求安静时一律不许`() {
        val v = InteractionPolicy.arbitrate(InteractionSignals(dismissedRecently = true), TalkAvenue.RANDOM)
        assertFalse("用户明确要求安静时必须闭嘴", v.allowed)
    }

    @Test
    fun `预约提醒不被闲聊退避影响`() {
        val v = InteractionPolicy.arbitrate(
            InteractionSignals(consecutiveIgnored = 5),
            TalkAvenue.APPOINTMENT,
        )
        assertTrue("重要约定不能被随机消息退避挤掉", v.allowed)
    }
}

/** 投递状态机：生成≠送达；通道失败要留痕；重启不补发。 */
class MessageDispatcherTest {

    private class FakeChannel(
        override val name: String,
        private val succeed: Boolean,
    ) : DeliveryChannel {
        var calls = 0
        override suspend fun send(record: DeliveryRecord): Boolean {
            calls++
            return succeed
        }
    }

    @Test
    fun `通知通道成功时达到 PUBLISHED 而不是 DISPLAYED`() = runBlocking {
        val channel = FakeChannel(MessageDispatcher.CHANNEL_NOTIFICATION, succeed = true)
        val d = MessageDispatcher(listOf(channel))
        val r = d.dispatch("你好呀")
        assertEquals(DeliveryState.PUBLISHED, r.state)
        assertTrue("通知发出不等于用户看见", r.state != DeliveryState.DISPLAYED)
    }

    @Test
    fun `气泡通道成功才算 DISPLAYED`() = runBlocking {
        val channel = FakeChannel(MessageDispatcher.CHANNEL_BUBBLE, succeed = true)
        val d = MessageDispatcher(listOf(channel))
        val r = d.dispatch("你好呀")
        assertEquals(DeliveryState.DISPLAYED, r.state)
    }

    @Test
    fun `所有通道失败时标 FAILED 并留痕`() = runBlocking {
        val d = MessageDispatcher(listOf(
            FakeChannel(MessageDispatcher.CHANNEL_NOTIFICATION, succeed = false),
            FakeChannel(MessageDispatcher.CHANNEL_BUBBLE, succeed = false),
        ))
        val r = d.dispatch("你好呀")
        assertEquals(DeliveryState.FAILED, r.state)
        assertTrue("失败原因要留下来", r.note.isNotBlank())
    }

    @Test
    fun `通知失败会退到气泡通道`() = runBlocking {
        val notif = FakeChannel(MessageDispatcher.CHANNEL_NOTIFICATION, succeed = false)
        val bubble = FakeChannel(MessageDispatcher.CHANNEL_BUBBLE, succeed = true)
        val d = MessageDispatcher(listOf(notif, bubble))
        val r = d.dispatch("退路")
        assertEquals(DeliveryState.DISPLAYED, r.state)
        assertTrue("应当尝试过通知", notif.calls > 0)
    }

    @Test
    fun `空正文直接失败不算说出口`() = runBlocking {
        val d = MessageDispatcher(listOf(FakeChannel(MessageDispatcher.CHANNEL_NOTIFICATION, true)))
        val r = d.dispatch("   ")
        assertEquals(DeliveryState.FAILED, r.state)
    }

    @Test
    fun `每条消息的通知 id 不重复`() = runBlocking {
        val d = MessageDispatcher(listOf(FakeChannel(MessageDispatcher.CHANNEL_NOTIFICATION, true)))
        val a = d.dispatch("第一条")
        val b = d.dispatch("第二条")
        assertFalse("通知 id 重复会导致后一条覆盖前一条", a.notificationId == b.notificationId)
    }

    @Test
    fun `重启后未完成的不补发`() = runBlocking {
        var clock = 0L
        val d = MessageDispatcher(listOf(FakeChannel(MessageDispatcher.CHANNEL_NOTIFICATION, true)), now = { clock })
        d.dispatch("已经发出去的")
        val expired = d.expirePendingAfterRestart()
        // 已经 PUBLISHED 但不是终态，应当被标 EXPIRED 且不重发。
        expired.forEach { assertEquals(DeliveryState.EXPIRED, it.state) }
    }

    @Test
    fun `标记已读会升级状态`() = runBlocking {
        val d = MessageDispatcher(listOf(FakeChannel(MessageDispatcher.CHANNEL_NOTIFICATION, true)))
        val r = d.dispatch("读我")
        val read = d.markRead(r.id)
        assertNotNull(read)
        assertEquals(DeliveryState.READ, read!!.state)
    }

    @Test
    fun `失败的消息不会因为标记已读而变成成功`() = runBlocking {
        val d = MessageDispatcher(listOf(FakeChannel(MessageDispatcher.CHANNEL_NOTIFICATION, false)))
        val r = d.dispatch("发不出去")
        val after = d.markRead(r.id)
        assertEquals("失败就是失败，不能被改成已读", DeliveryState.FAILED, after!!.state)
    }
}
