package huilu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopTest {
    private val course = Expectation(EnvMode.IN_APPS, setOf("course"))

    /**
     * 判断项目方向的核心标准：声明"接下来十分钟学课程"之后，
     * 程序能陪着这个意图走完十分钟，并在现实偏离时把人拉回判断循环。
     */
    @Test
    fun tenMinutesWithADriftInTheMiddle() {
        val sim = Sim()
        sim.phone.switchTo("course")
        sim.engine.start("学习课程", "明天要交作业", 10, course, strict = false)
        assertEquals(Mode.ACTING, sim.s.mode)
        assertEquals(Sim.T0 + 5 * MIN, sim.s.nextCheckAt)

        // 第 3 分钟打开 B站
        sim.advance(3.0)
        sim.phone.switchTo("bili")
        sim.advance(1.5)
        assertNull("还没到偏离阈值", sim.s.pending)

        // 连续 2 分钟后系统主动重新出现
        sim.advance(0.75)
        val c = sim.s.pending
        assertNotNull(c)
        c!!
        assertEquals(Trigger.DRIFT, c.trigger)
        assertEquals(DevKind.DRIFT, c.deviation.kind)
        assertEquals("bili", c.deviation.topDistractor)
        assertEquals(3, c.deviation.onsetMin)
        assertEquals(Level.NOTIFY, c.level)
        assertTrue(c.question, c.question.contains("B站") && c.question.contains("学习课程"))
        val prompt = sim.prompts.last()
        assertEquals(c.id, prompt.checkIn?.id)
        assertEquals("bili", prompt.intervention.target)

        // 用户回答"偏了，现在回去"，然后真的回去了
        sim.effects += sim.engine.answer(c.id, AnswerKind.RECOMMIT, via = Via.NOTIFICATION)
        assertNull(sim.s.pending)
        assertTrue(sim.effects.any { it is Effect.Dismiss && it.checkInId == c.id })
        sim.phone.switchTo("course")
        sim.advance(3.0)

        // 干预的行为效果被评估：回到了目标
        val ep = Timeline.build(sim.log.events).single()
        val drift = ep.interventions.first { it.intervention.target == "bili" }
        assertEquals(Aftermath.RETURNED, drift.aftermath)

        // 短间隔复查（3 分钟后）发现已经回到正轨
        val second = sim.s.pending
        assertNotNull(second)
        assertEquals(Trigger.SCHEDULED, second!!.trigger)
        assertEquals(DevKind.ON_TRACK, second.deviation.kind)
        sim.engine.answer(second.id, AnswerKind.ON_TRACK)

        // 十分钟到点确认结果
        sim.advance(3.0)
        val end = sim.s.pending!!
        assertEquals(Trigger.END, end.trigger)
        assertTrue(end.question, end.question.contains("B站"))
        sim.engine.answer(end.id, AnswerKind.DONE)
        assertEquals(Mode.IDLE, sim.s.mode)

        val done = Timeline.build(sim.log.events).single()
        assertEquals(Outcome.DONE, done.outcome)
        assertEquals(3, done.checkIns.size)
        assertTrue(done.drifted)
        assertEquals(listOf(AnswerKind.RECOMMIT, AnswerKind.ON_TRACK, AnswerKind.DONE), done.checkIns.map { it.answer })
    }

    @Test
    fun stateSurvivesProcessDeath() {
        val sim = Sim()
        sim.phone.switchTo("course")
        sim.engine.start("学习课程", "", 10, course, strict = false)
        sim.advance(5.25)
        val before = sim.s
        assertNotNull(before.pending)

        sim.restart()
        assertEquals(before, sim.s)
        sim.engine.answer(before.pending!!.id, AnswerKind.ON_TRACK)
        sim.advance(5.0)
        assertEquals(Trigger.END, sim.s.pending?.trigger)
    }

    @Test
    fun sameDriftRunIsNotAskedTwice() {
        val sim = Sim()
        sim.phone.switchTo("course")
        sim.engine.start("刷题", "", 30, course, strict = false)
        sim.phone.switchTo("bili")
        sim.advance(2.25)
        val c = sim.s.pending!!
        sim.engine.answer(c.id, AnswerKind.MUTE)
        sim.advance(10.0)
        assertNull("本轮静音后不再因为同一段偏离打扰", sim.s.pending)
        assertTrue(sim.s.muted)
    }

    @Test
    fun anEffectiveLevelIsKeptInsteadOfEscalating() {
        fun ladder(strict: Boolean, avail: Set<Level>): List<Level> {
            val sim = Sim()
            sim.phone.avail = avail.toMutableSet()
            sim.phone.switchTo("course")
            sim.engine.start("写报告", "", 60, course, strict)
            val levels = mutableListOf<Level>()
            repeat(4) {
                sim.phone.switchTo(if (it % 2 == 0) "bili" else "douyin")
                sim.advance(2.25)
                val c = sim.s.pending!!
                val strongest = sim.prompts.filter { p -> p.intervention.at == c.openedAt }.maxOf { p -> p.intervention.level.rank }
                levels += Level.values().first { l -> l.rank == strongest }
                sim.engine.answer(c.id, AnswerKind.RECOMMIT)
                sim.phone.switchTo("course")
                sim.advance(2.5) // 干预效果评估为 RETURNED 之前先再次偏离会保持强度，这里先让它评估完
                sim.phone.switchTo("wechat")
                sim.advance(0.5)
                sim.s.pending?.let { p -> sim.engine.answer(p.id, AnswerKind.ON_TRACK) }
            }
            return levels
        }
        // 回到目标之后效果是 RETURNED，强度保持不变
        assertEquals(listOf(Level.NOTIFY, Level.NOTIFY, Level.NOTIFY, Level.NOTIFY), ladder(false, Level.values().toSet()))
    }

    @Test
    fun escalatesWhenTheInterventionDidNotWork() {
        fun run(strict: Boolean, avail: Set<Level>): Pair<List<Level>, List<String>> {
            val sim = Sim()
            sim.phone.avail = avail.toMutableSet()
            sim.phone.switchTo("course")
            sim.engine.start("写报告", "", 60, course, strict)
            val levels = mutableListOf<Level>()
            val reasons = mutableListOf<String>()
            repeat(4) { i ->
                // 每次都换一个娱乐 App（行为转移），干预没有真正起作用
                sim.phone.switchTo(if (i % 2 == 0) "bili" else "douyin")
                sim.advance(2.25)
                val c = sim.s.pending!!
                val ivs = sim.prompts.filter { p -> p.intervention.at == c.openedAt }
                levels += ivs.maxByOrNull { it.intervention.level.rank }!!.intervention.level
                reasons += ivs.first().intervention.reason
                sim.engine.answer(c.id, AnswerKind.RECOMMIT)
            }
            return levels to reasons
        }
        assertEquals(listOf(Level.NOTIFY, Level.INTERRUPT, Level.HOME, Level.DEVICE), run(true, Level.values().toSet()).first)
        assertEquals(listOf(Level.NOTIFY, Level.INTERRUPT, Level.HOME, Level.HOME), run(true, Level.values().toSet() - Level.DEVICE).first)
        assertEquals(listOf(Level.NOTIFY, Level.INTERRUPT, Level.INTERRUPT, Level.INTERRUPT), run(false, Level.values().toSet()).first)
        val (lv, why) = run(true, setOf(Level.NOTIFY))
        assertEquals(listOf(Level.NOTIFY, Level.NOTIFY, Level.NOTIFY, Level.NOTIFY), lv)
        assertTrue(why[1], why[1].contains("不可用"))
    }

    @Test
    fun blockModeKeepsSendingHomeAndRecordsEachAttempt() {
        val sim = Sim()
        sim.phone.switchTo("course")
        sim.engine.start("背单词", "", 60, course, strict = true)
        repeat(4) {
            sim.phone.switchTo("course")
            sim.advance(0.25)
            sim.phone.switchTo("bili")
            sim.advance(2.25)
            sim.engine.answer(sim.s.pending!!.id, AnswerKind.RECOMMIT)
        }
        assertTrue(sim.s.blocking)
        val homesBefore = sim.prompts.count { it.intervention.level == Level.HOME }
        sim.phone.switchTo("douyin")
        sim.advance(0.25)
        assertEquals(homesBefore + 1, sim.prompts.count { it.intervention.level == Level.HOME })
        val home = sim.prompts.last().intervention
        sim.engine.interventionResult(home.id, ActResult.FAILED, "无障碍服务未连接")
        val rec = Timeline.build(sim.log.events).single().interventions.first { it.intervention.id == home.id }
        assertEquals(ActResult.FAILED, rec.result)
    }

    @Test
    fun unansweredCheckInsAreSignalsToo() {
        val sim = Sim()
        sim.phone.switchTo("course")
        sim.engine.start("学习课程", "", 30, course, strict = false)
        sim.advance(15.25)
        val c = sim.s.pending!!
        assertEquals(1, c.prompts)
        sim.advance(3.0)
        assertEquals(2, sim.s.pending!!.prompts)
        assertEquals(Level.INTERRUPT, sim.prompts.last().intervention.level)
        sim.advance(7.0)
        val ep = Timeline.build(sim.log.events).single()
        assertEquals(CloseReason.UNANSWERED, ep.checkIns.first().closeReason)
    }

    @Test
    fun unansweredEndIsRecordedAsUnconfirmed() {
        val sim = Sim()
        sim.phone.switchTo("course")
        sim.engine.start("学习课程", "", 4, course, strict = false)
        assertNull("短行动不安排中途检查", sim.s.nextCheckAt)
        sim.advance(4.0)
        assertEquals(Trigger.END, sim.s.pending!!.trigger)
        sim.advance(11.0)
        assertEquals(Mode.IDLE, sim.s.mode)
        assertEquals(Outcome.UNCONFIRMED, Timeline.build(sim.log.events).single().outcome)
    }

    @Test
    fun restPausesTheClockAndCallsYouBack() {
        val sim = Sim()
        sim.phone.switchTo("course")
        sim.engine.start("学习课程", "", 20, course, strict = false)
        sim.advance(10.0)
        val c = sim.s.pending!!
        val endBefore = sim.s.action!!.endAt
        sim.engine.answer(c.id, AnswerKind.REST, minutes = 5)
        assertEquals(Mode.RESTING, sim.s.mode)
        assertEquals(endBefore + 5 * MIN, sim.s.action!!.endAt)
        sim.phone.switchTo("bili")
        sim.advance(4.5)
        assertNull("休息时看 B站 不算偏离", sim.s.pending)
        sim.advance(0.75)
        assertEquals(Trigger.REST_OVER, sim.s.pending!!.trigger)
        sim.engine.answer(sim.s.pending!!.id, AnswerKind.ON_TRACK)
        assertEquals(Mode.ACTING, sim.s.mode)
    }

    @Test
    fun shrinkRevisesTheActionAndChecksSooner() {
        val sim = Sim()
        sim.phone.switchTo("course")
        sim.engine.start("写论文第三章", "", 30, course, strict = false)
        sim.advance(15.0)
        sim.engine.answer(sim.s.pending!!.id, AnswerKind.SHRINK, newText = "只写第三章的第一段", minutes = 4)
        assertEquals("只写第三章的第一段", sim.s.action!!.text)
        assertEquals(sim.clock.t + 2 * MIN, sim.s.nextCheckAt)
    }

    @Test
    fun selfReportThatContradictsObservationIsKept() {
        val sim = Sim()
        sim.phone.switchTo("course")
        sim.engine.start("学习课程", "", 10, course, strict = false)
        sim.advance(1.0)
        sim.phone.switchTo("bili")
        sim.advance(2.25)
        sim.engine.answer(sim.s.pending!!.id, AnswerKind.ON_TRACK)
        assertTrue(sim.s.lastClosed!!.decision!!.contains("自报在做"))
        val ins = Insights.of(Timeline.build(sim.log.events).map { it.copy(outcome = Outcome.DONE) })
        assertEquals(1, ins.optimisticReports)
    }

    @Test
    fun withoutPermissionTheSystemSaysItCannotSee() {
        val sim = Sim()
        sim.phone.state = SensorState.NO_PERMISSION
        sim.engine.start("学习课程", "", 10, course, strict = false)
        sim.advance(5.25)
        val c = sim.s.pending!!
        assertEquals(DevKind.UNKNOWN, c.deviation.kind)
        assertTrue(c.deviation.reality.contains("没有使用情况访问权限"))
    }

    @Test
    fun startingANewActionReplacesTheOld() {
        val sim = Sim()
        sim.phone.switchTo("course")
        sim.engine.start("A", "", 10, course, strict = false)
        sim.advance(1.0)
        sim.engine.start("B", "", 10, course, strict = false)
        val eps = Timeline.build(sim.log.events)
        assertEquals(Outcome.REPLACED, eps[0].outcome)
        assertEquals("B", sim.s.action!!.text)
        assertFalse(eps[1].outcome != null)
    }
}
