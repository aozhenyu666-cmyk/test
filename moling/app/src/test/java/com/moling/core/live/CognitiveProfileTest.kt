package com.moling.core.live

import com.moling.core.mind.BEIJING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 认知画像（W10 T8）：只数真实记录；样本不足就说不足；没看到的不算冷落；规律要他点了才算。 */
class CognitiveProfileTest {
    private fun at(h: Int, mi: Int = 0) = LocalDateTime.of(2026, 10, 22, h, mi).atZone(BEIJING).toInstant().toEpochMilli()

    private fun marks(n: Int, m: Modality, r: Reaction, hour: Int, e: Exposure = Exposure.SHOWN) =
        List(n) { HabitMark(m, at(hour, it), r, exposure = e) }

    @Test fun fewSamplesSayNotEnoughAndProposeNothing() {
        val p = CognitiveProfile.build(HabitBook(marks(9, Modality.PEEK, Reaction.REPLIED, 15)))
        val row = p.byModality.single()
        assertNull(row.tally.replyRate)
        assertTrue(row.line().contains("样本不足（9/10）"))
        assertTrue(p.patterns.isEmpty())
        assertTrue("没有记录的情境写未知", p.unknown.all { it.contains("未知") || it.contains("未记录") })
    }

    @Test fun unobservedAppearancesAreNotSamples() {
        val b = HabitBook(marks(8, Modality.PET, Reaction.IGNORED, 10, Exposure.UNOBSERVED) + marks(3, Modality.PET, Reaction.REPLIED, 10))
        val t = CognitiveProfile.build(b).byModality.single().tally
        assertEquals(8, t.unobserved)
        assertEquals("没被看到的不算他跳过", 0, t.skipped)
        assertEquals(3, t.samples)
    }

    @Test fun enoughSamplesProposeAPatternHeCanAcceptOrDisable() {
        val b = HabitBook(marks(8, Modality.PEEK, Reaction.REPLIED, 15) + marks(2, Modality.PEEK, Reaction.DISMISSED_FAST, 16) + marks(1, Modality.PEEK, Reaction.STOPPED, 16))
        val p = CognitiveProfile.build(b)
        val t = p.byModality.single().tally
        assertEquals(11, t.samples)
        assertEquals(2, t.skipped)
        assertEquals(1, t.refused)
        val pat = p.patterns.single { it.id == "m:PEEK:hi" }
        assertEquals(PatternState.PROPOSED, pat.state)
        assertTrue(pat.text.contains("11 次里回了 8 次"))
        assertEquals("下午这一段也够 10 次", "t:下午（12–18 点）:hi", p.patterns.single { it.id.startsWith("t:") }.id)
        val off = CognitiveProfile.build(b, mapOf("m:PEEK:hi" to PatternState.DISABLED))
        assertEquals(PatternState.DISABLED, off.patterns.single { it.id == "m:PEEK:hi" }.state)
    }

    @Test fun timeSlotsComeFromTheRecordedMoment() {
        assertEquals("上午（7–12 点）", CognitiveProfile.slot(at(9)))
        assertEquals("夜里（0–7 点）", CognitiveProfile.slot(at(3)))
        assertEquals("夜里（23–24 点）", CognitiveProfile.slot(at(23, 30)))
    }
}
