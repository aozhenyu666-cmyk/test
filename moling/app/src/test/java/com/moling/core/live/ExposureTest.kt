package com.moling.core.live

import com.moling.core.Brain
import com.moling.core.ZkPort
import com.moling.core.flow.Flow
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkReceipt
import com.moling.core.link.ZkStatus
import com.moling.core.mind.Ask
import com.moling.core.mind.BEIJING
import com.moling.core.mind.Facts
import com.moling.core.mind.FactsSource
import com.moling.core.mind.MindStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDateTime

/**
 * F11-5：浮层被系统 / 厂商拦了、没戴耳机、窗口没加上——那一次其实没被他看到，
 * 记 UNOBSERVED；之后他就算没理，也不算“他不理我”，也不让她歇着。
 */
class ExposureTest {
    private fun at(h: Int, mi: Int = 0) = LocalDateTime.of(2026, 10, 21, h, mi).atZone(BEIJING).toInstant().toEpochMilli()

    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private class Zk : ZkPort {
        override fun status() = ZkStatus()
        override fun send(command: ZkCommand) = ZkReceipt(true, "收下了", 0)
    }

    private val phone = FactsSource { Facts(now = it, minute = Flow.minuteOf(it)) }
    private fun brain(ask: Ask?) = Brain(phone, ask, MemStore(), MemStore(), Zk())
    private val desktop = Activity("launcher", "桌面", "OTHER", 0)

    @Test fun unobservedAppearanceIsNotCountedAsIgnored() = runTest {
        val b = brain(null)
        val t = b.live(Trigger.AUTOPILOT, desktop, at(15), roll = 0.9)!!
        val m = t.modality
        // App 那边发现这次其实没显示出来（被系统 / 厂商拦了）
        b.markUnobserved(m, at(15))
        assertEquals(Exposure.UNOBSERVED, Habits.lastExposure(b.state().habits, m))
        // 12 秒没人碰：不算“他不理我”
        b.react(m, Reaction.IGNORED, 12_000, at(15, 1))
        assertEquals(0, b.state().spirit.ignored)
        assertFalse(Habits.cooling(b.state().habits, m, at(15, 2)))
        assertEquals(0.5, Habits.works(b.state().habits, m), 0.0001)
    }

    @Test fun observedBadReactionStillCounts() = runTest {
        // 真的显示过的那一次，他不理照旧算冷落（F11-5 不能把这条规矩也松掉）
        val b = brain(null)
        val t = b.live(Trigger.AUTOPILOT, desktop, at(15), roll = 0.9)!!
        assertEquals(Exposure.SHOWN, Habits.lastExposure(b.state().habits, t.modality))
        b.react(t.modality, Reaction.IGNORED, 12_000, at(15, 1))
        assertEquals(1, b.state().spirit.ignored)
    }

    @Test fun markUnobservedWithoutThisAppearanceChangesNothing() = runTest {
        val b = brain(null)
        val logBefore = b.state().log.size
        b.markUnobserved(Modality.PET, at(15))
        assertEquals(0, b.state().habits.marks.size)
        assertEquals("没这一次出现就不写日志", logBefore, b.state().log.size)
    }
}
