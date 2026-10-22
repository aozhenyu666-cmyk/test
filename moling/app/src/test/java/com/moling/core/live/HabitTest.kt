package com.moling.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R3：明确停止 → 安静期；曝光区分；没被看到不计冷落。 */
class HabitTest {
    private val t0 = 3_000_000L

    @Test fun oldBookDefaultsToNoQuietPeriod() {
        val b = HabitBook()
        assertEquals(0L, b.quietUntil)
        assertFalse(Habits.isQuiet(b, t0))
    }

    @Test fun explicitStopEntersQuietPeriodAndRestoreClearsIt() {
        var b = Habits.shown(HabitBook(), Modality.PEEK, t0)
        b = Habits.explicitStop(b, t0 + 1_000, Modality.PEEK)
        assertTrue(Habits.isQuiet(b, t0 + 2_000))
        assertEquals(t0 + 1_000 + Habits.QUIET_MS, b.quietUntil)
        assertTrue(Habits.quietLine(b, t0 + 2_000).contains("安静期"))
        // 明确停止不算“他不理我”
        assertEquals(Reaction.STOPPED, b.marks.last().reaction)
        assertEquals(0, b.marks.count { it.reaction!!.bad })
        // 两小时后自己结束
        assertFalse(Habits.isQuiet(b, t0 + 1_000 + Habits.QUIET_MS + 1))
        // 他可以恢复
        val restored = Habits.restore(b)
        assertFalse(Habits.isQuiet(restored, t0 + 2_000))
        assertEquals("", Habits.quietLine(restored, t0 + 2_000))
    }

    @Test fun explicitStopWithoutThisAppearanceDoesNotFabricateOne() {
        // 设置页“先安静”没有对应的一次出现：只进安静期，不补一条假记录。
        val b = Habits.explicitStop(HabitBook(), t0)
        assertEquals(0, b.marks.size)
        assertTrue(Habits.isQuiet(b, t0))
        // 有上一次出现、但这次不是它：找不到这一次就不记。
        val other = Habits.explicitStop(Habits.shown(HabitBook(), Modality.PEEK, t0), t0 + 1, Modality.STARE)
        assertEquals(1, other.marks.size)
        assertNull(other.marks.single().reaction)
        assertTrue(Habits.isQuiet(other, t0 + 2))
    }

    @Test fun explicitStopOnlyMarksThisAppearance() {
        var b = Habits.shown(HabitBook(), Modality.PEEK, t0)
        b = Habits.shown(b, Modality.PET, t0 + 10)
        b = Habits.explicitStop(b, t0 + 20, Modality.PET)
        assertEquals(Reaction.STOPPED, b.marks.last().reaction)
        assertNull("别的出现不跟着记 STOPPED", b.marks.first().reaction)
    }

    @Test fun unobservedExposureDoesNotCountAsIgnored() {
        var b = HabitBook()
        // 两次“没被看到”的坏回应（被系统拦了 / 没连耳机）不能让她歇着
        repeat(2) {
            b = Habits.shown(b, Modality.WHISPER, t0, Exposure.UNOBSERVED)
            b = Habits.react(b, Modality.WHISPER, Reaction.IGNORED, 0, t0 + 1)
        }
        assertFalse("没被看到不计冷落", Habits.cooling(b, Modality.WHISPER, t0 + 2))
        assertEquals(0.5, Habits.works(b, Modality.WHISPER), 0.0001)
        // 真的显示过的两次坏回应才冷却 48 小时
        repeat(2) {
            b = Habits.shown(b, Modality.WHISPER, t0 + 10, Exposure.SHOWN)
            b = Habits.react(b, Modality.WHISPER, Reaction.DISMISSED_FAST, 500, t0 + 11)
        }
        assertTrue(Habits.cooling(b, Modality.WHISPER, t0 + 20))
    }

    @Test fun lastExposureIsReadableForCallers() {
        var b = Habits.shown(HabitBook(), Modality.PET, t0, Exposure.SEEN)
        assertEquals(Exposure.SEEN, Habits.lastExposure(b, Modality.PET))
        b = Habits.shown(b, Modality.PET, t0 + 1, Exposure.UNOBSERVED)
        assertEquals(Exposure.UNOBSERVED, Habits.lastExposure(b, Modality.PET))
        org.junit.Assert.assertNull(Habits.lastExposure(b, Modality.STARE))
    }

    @Test fun summaryIncludesQuietLine() {
        val b = Habits.explicitStop(HabitBook(), t0)
        assertTrue(Habits.summary(b, t0 + 1_000).first().contains("安静期"))
    }

    @Test fun coolingStillWorksForSeenMarks() {
        var b = Habits.shown(HabitBook(), Modality.PEEK, t0)
        b = Habits.react(b, Modality.PEEK, Reaction.IGNORED, 0, t0 + 1)
        b = Habits.shown(b, Modality.PEEK, t0 + 2)
        b = Habits.react(b, Modality.PEEK, Reaction.IGNORED, 0, t0 + 3)
        assertTrue(Habits.cooling(b, Modality.PEEK, t0 + 4))
    }
}
