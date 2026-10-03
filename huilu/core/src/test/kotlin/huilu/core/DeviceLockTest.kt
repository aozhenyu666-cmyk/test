package huilu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设备级暂停（Shizuku）：只有平台确认才算暂停；不再需要时必须解除，没确认就一直要求。 */
class DeviceLockTest {
    private val course = Expectation(EnvMode.IN_APPS, setOf("course"))

    private fun Sim.driftTimes(n: Int) = repeat(n) {
        phone.switchTo("course")
        advance(0.25)
        phone.switchTo("bili")
        advance(2.25)
        engine.answer(s.pending!!.id, AnswerKind.RECOMMIT)
    }

    @Test
    fun fourthDriftPausesDistractorsWhenShizukuIsAvailable() {
        val sim = Sim()
        sim.phone.avail = Level.values().toMutableSet()
        sim.phone.switchTo("course")
        sim.engine.start("背单词", "", 60, course, strict = true)
        sim.driftTimes(4)
        val device = sim.prompts.filter { it.intervention.level == Level.DEVICE }
        assertEquals(1, device.size)
        assertEquals("bili", device.single().intervention.target)
        assertTrue(device.single().intervention.reason.contains("暂停娱乐 App"))
        assertTrue(sim.s.blocking)
        assertTrue("平台确认之前不算暂停", sim.s.locked.isEmpty())

        sim.engine.locked(setOf("bili", "douyin"))
        assertEquals(setOf("bili", "douyin"), sim.s.locked)
        assertTrue(sim.s.lockWanted)

        // 本轮结束：必须要求解除
        val fx = sim.engine.stop(Outcome.DONE)
        assertEquals(Effect.Release(setOf("bili", "douyin")), fx.filterIsInstance<Effect.Release>().single())
        assertEquals("轮次结束后暂停状态仍然保留，直到平台确认解除", setOf("bili", "douyin"), sim.s.locked)
        assertTrue(sim.engine.nextWakeAt()!! <= sim.clock.t + 30_000)

        // 平台没确认：30 秒内不重复要求，之后每次都要求
        assertTrue(sim.engine.tick().none { it is Effect.Release })
        sim.clock.t += 31_000
        assertTrue(sim.engine.tick().any { it is Effect.Release })

        // 进程重启后照样要求
        sim.restart()
        assertEquals(setOf("bili", "douyin"), sim.s.locked)
        assertTrue(sim.engine.tick().any { it is Effect.Release })

        // 只解除了一部分：剩下的继续要求
        val rest = sim.engine.unlocked(setOf("bili"))
        assertEquals(setOf("douyin"), sim.s.locked)
        assertEquals(Effect.Release(setOf("douyin")), rest.filterIsInstance<Effect.Release>().single())
        sim.engine.unlocked(setOf("douyin"))
        assertTrue(sim.s.locked.isEmpty())
        assertTrue(sim.engine.tick().none { it is Effect.Release })
    }

    @Test
    fun restAndMuteReleaseThePause() {
        for (k in listOf(AnswerKind.REST, AnswerKind.MUTE)) {
            val sim = Sim()
            sim.phone.avail = Level.values().toMutableSet()
            sim.phone.switchTo("course")
            sim.engine.start("背单词", "", 60, course, strict = true)
            sim.driftTimes(4)
            sim.engine.locked(setOf("bili"))
            sim.engine.checkNow()
            val fx = sim.engine.answer(sim.s.pending!!.id, k)
            assertTrue("$k 之后应解除暂停", fx.any { it is Effect.Release })
        }
    }

    @Test
    fun withoutShizukuTheFourthDriftFallsBackToBlockMode() {
        val sim = Sim()
        sim.phone.switchTo("course")
        sim.engine.start("背单词", "", 60, course, strict = true)
        sim.driftTimes(4)
        assertTrue(sim.prompts.none { it.intervention.level == Level.DEVICE })
        val last = sim.prompts.last { it.intervention.level == Level.INTERRUPT }.intervention
        assertTrue(last.reason, last.reason.contains("「暂停娱乐 App」当前不可用，降为「本轮屏蔽娱乐 App」"))
        assertTrue(sim.s.blocking)
    }

    @Test
    fun nonStrictRoundsNeverPause() {
        val sim = Sim()
        sim.phone.avail = Level.values().toMutableSet()
        sim.phone.switchTo("course")
        sim.engine.start("背单词", "", 60, course, strict = false)
        sim.driftTimes(5)
        assertTrue(sim.prompts.none { it.intervention.level.rank > Level.INTERRUPT.rank })
        assertFalse(sim.s.blocking)
    }
}
