package com.yishou.app.companion

import com.yishou.app.companion.core.AskOutcome
import com.yishou.app.companion.core.AskPlan
import com.yishou.app.companion.core.AttemptMemory
import com.yishou.app.companion.core.Capsule
import com.yishou.app.companion.core.CapsuleState
import com.yishou.app.companion.core.Channel
import com.yishou.app.companion.core.CoachDecision
import com.yishou.app.companion.core.CoachParse
import com.yishou.app.companion.core.CoachPorts
import com.yishou.app.companion.core.CoachInput
import com.yishou.app.companion.core.CompanionSession
import com.yishou.app.companion.core.Conductor
import com.yishou.app.companion.core.ContextSlice
import com.yishou.app.companion.core.InterruptBudget
import com.yishou.app.companion.core.JudgeParse
import com.yishou.app.companion.core.MediaReceipt
import com.yishou.app.companion.core.PauseOutcome
import com.yishou.app.companion.core.Phase
import com.yishou.app.companion.core.Segment
import com.yishou.app.companion.core.ThinkMove
import com.yishou.app.companion.core.Verdict
import com.yishou.app.companion.core.VideoCoach
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一整轮：能不能问、问了没、答了怎么判、判不了算不算错、胶囊说的是不是实话 */
class ConductorTest {

    private var now = 0L

    private class FakePorts(
        var reply: CoachParse,
        var pauseOutcome: PauseOutcome = PauseOutcome.VERIFIED,
        var speakOk: Boolean = true,
        var judged: JudgeParse = JudgeParse.Ok(Verdict.NEAR, "少了抽样条件"),
    ) : CoachPorts {
        var spoken: String? = null
        var recorded: Triple<String, String, Verdict>? = null
        var recordedMove: ThinkMove? = null
        var proposed = 0
        var paused = 0

        override suspend fun propose(plan: AskPlan.Ask): CoachParse {
            proposed++
            return reply
        }

        override suspend fun pause(): MediaReceipt {
            paused++
            return MediaReceipt("pkg/tag", pauseOutcome, 0, 1)
        }

        override suspend fun speak(text: String): Boolean {
            spoken = text
            return speakOk
        }

        override suspend fun judge(question: String, criteria: String, answer: String, material: String): JudgeParse = judged

        override suspend fun record(turnId: Long, question: String, answer: String, delta: String, verdict: Verdict, move: ThinkMove) {
            recorded = Triple(question, answer, verdict)
            recordedMove = move
        }
    }

    private fun slice(to: Long = 150_000) = ContextSlice(
        epoch = 1,
        evidenceIds = listOf(1L, 2L),
        fromMs = 0,
        toMs = to,
        text = "他从样本 A 推出全部人群一定会这样",
        segments = listOf(Segment(1, Channel.VIDEO_AUDIO, 0, to, "他从样本 A 推出全部人群一定会这样", listOf(1L, 2L))),
        gapsMs = 0,
        expiresAtMs = now + 20_000,
    )

    private fun input(to: Long = 150_000, recent: List<AttemptMemory> = emptyList()) =
        CoachInput(now, "弄懂抽样", slice(to), recent, elapsedMs = to)

    private fun ok() = CoachParse.Ok(
        CoachDecision(
            action = com.yishou.app.companion.core.CoachAction.ASK,
            question = "他从样本 A 推到全部人，中间还缺哪个条件？",
            criteria = "缺样本代表性",
            evidenceIds = listOf(1L, 2L),
            reason = "动作：找条件",
            epoch = 1,
            expiresAtMs = now + 20_000,
        ),
    )

    private fun wire(ports: FakePorts): Conductor {
        val session = CompanionSession(sessionId = 1, clock = { now }, answerWaitMs = 15_000)
        val coach = VideoCoach(InterruptBudget()) { now }
        return Conductor(session, coach, ports, { now })
    }

    @Test
    fun `一整轮：问出去、念完、答、判、记`() = runTest {
        val ports = FakePorts(ok())
        val c = wire(ports)
        val out = c.tryAsk(input())
        assertTrue(out is AskOutcome.Asked)
        assertEquals(Phase.WAIT_ANSWER, c.phase())
        assertEquals("他从样本 A 推到全部人，中间还缺哪个条件？", ports.spoken)

        val ans = c.answer("缺样本是不是随机抽的", material = "材料原文")
        assertEquals(Verdict.NEAR, ans!!.verdict)
        assertEquals("少了抽样条件", ans.delta)
        assertEquals(Verdict.NEAR, ports.recorded!!.third)
        assertEquals(Phase.OBSERVE, c.phase())
    }

    @Test
    fun `模型给的问题没依据就不问`() = runTest {
        val ports = FakePorts(CoachParse.Reject("没有能在材料里对上的依据"))
        val c = wire(ports)
        val out = c.tryAsk(input())
        assertTrue(out is AskOutcome.Waited)
        assertEquals(0, ports.paused)   // 连暂停都不该去试
        assertEquals(Phase.OBSERVE, c.phase())
    }

    @Test
    fun `暂停没核实就先让用户按，不念`() = runTest {
        val ports = FakePorts(ok(), pauseOutcome = PauseOutcome.STILL_PLAYING)
        val c = wire(ports)
        val out = c.tryAsk(input())
        assertTrue(out is AskOutcome.NeedManual)
        assertNull(ports.spoken)
        assertEquals(Phase.PAUSE_PENDING, c.phase())

        // 用户按好了，这次才念
        val ready = c.userReady()
        assertTrue(ready is AskOutcome.Asked)
        assertEquals(Phase.WAIT_ANSWER, c.phase())
    }

    @Test
    fun `念到一半被打断就作废，不算问过`() = runTest {
        val ports = FakePorts(ok(), speakOk = false)
        val c = wire(ports)
        val out = c.tryAsk(input())
        assertTrue(out is AskOutcome.Waited)
        assertEquals(Phase.OBSERVE, c.phase())
        // 作废以后可以重新问（预算算不算在 coach 里是另一回事）
        ports.speakOk = true
        assertTrue(c.tryAsk(input()) is AskOutcome.Asked)
    }

    @Test
    fun `判定接口坏了记没判成，不记答错`() = runTest {
        val ports = FakePorts(ok(), judged = JudgeParse.Failed)
        val c = wire(ports)
        c.tryAsk(input())
        val ans = c.answer("随便说了点", material = "材料原文")
        assertEquals(Verdict.NONE, ans!!.verdict)
        assertEquals(Verdict.NONE, ports.recorded!!.third)
        assertTrue(!Verdict.NONE.counted)
    }

    @Test
    fun `手上还有一问时不接新的`() = runTest {
        val ports = FakePorts(ok())
        val c = wire(ports)
        c.tryAsk(input())
        val second = c.tryAsk(input(to = 200_000))
        assertTrue(second is AskOutcome.Busy)
        assertEquals(1, ports.proposed)
    }

    @Test
    fun `没到时机连模型都不叫`() = runTest {
        val ports = FakePorts(ok())
        val c = wire(ports)
        val out = c.tryAsk(input(to = 20_000))
        assertTrue(out is AskOutcome.Waited)
        assertEquals(0, ports.proposed)
    }

    @Test
    fun `没答到点记没接，不算答错`() = runTest {
        val ports = FakePorts(ok())
        val c = wire(ports)
        c.tryAsk(input())
        now += 16_000
        assertTrue(c.tick())
        assertEquals(Phase.OBSERVE, c.phase())
        assertNull(ports.recorded)
    }

    @Test
    fun `停了以后所有动作都不响应`() = runTest {
        val ports = FakePorts(ok())
        val c = wire(ports)
        c.tryAsk(input())
        c.stop()
        assertEquals(Phase.ENDED, c.phase())
        assertNull(c.answer("还有话说", "材料"))
        assertTrue(!c.tick())
        assertTrue(c.tryAsk(input()) is AskOutcome.Busy)
    }

    @Test
    fun `胶囊缺什么就写缺什么`() {
        val text = Capsule.text(
            CapsuleState(sound = false, frames = true, activeMs = 720_000, phase = Phase.OBSERVE, soundNote = "目标 App 静音"),
        )
        assertTrue(text.contains("声音缺失（目标 App 静音）"))
        assertTrue(text.contains("画面有"))
        assertTrue(text.contains("有效 12 分钟"))
        assertTrue(text.contains("在听"))
    }

    @Test
    fun `胶囊没验证过就说未验证，不冒充正常`() {
        val text = Capsule.text(CapsuleState(sound = null, frames = null, activeMs = 0, phase = Phase.OBSERVE))
        assertTrue(text.contains("声音未验证"))
        assertTrue(text.contains("画面未验证"))
        assertTrue(text.contains("有效 0 分钟"))
    }

    @Test
    fun `胶囊对每个阶段都有说法`() {
        Phase.entries.forEach { p ->
            assertTrue("阶段 ${p.name} 没有说法", Capsule.phaseLine(p).isNotBlank())
        }
    }
}
