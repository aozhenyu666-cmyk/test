package com.yishou.app.judge

import org.junit.Assert.*
import org.junit.Test

class ProbeRecorderTest {
    @Test fun firstInputAndDeletesAreRecorded() {
        val r = ProbeRecorder { 0 }
        r.shown(1_000); r.edit("", "想法", 1_500); r.edit("想法", "想", 2_000)
        val t = r.submit(3_000)
        assertEquals(1_500L, t.firstInputAt); assertEquals(1, t.deletes)
        r.shown(4_000)
        assertNull(r.submit(5_000).firstInputAt)
    }
    @Test fun pauseBoundaryAndPosition() {
        val r = ProbeRecorder { 0 }; r.shown(0)
        r.edit("", "甲", 100); r.edit("甲", "甲乙", 8_099)
        assertTrue(r.submit(8_100).pauses.isEmpty())
        r.edit("甲乙", "甲乙丙", 16_099)
        assertEquals(listOf(Pause(2, 8_000)), r.submit(16_100).pauses)
    }
    @Test fun pastedRangesFollowInsertionsAndDeletions() {
        val r = ProbeRecorder { 0 }; r.shown(0)
        r.edit("", "abcdefgh", 50)
        assertEquals(listOf(0..7), r.submit(60).pastedRanges)
        r.edit("abcdefgh", "甲abcdefgh", 1_000)
        assertEquals(listOf(1..8), r.submit(1_001).pastedRanges)
        r.edit("甲abcdefgh", "甲fgh", 2_000)
        assertEquals(listOf(1..3), r.submit(2_001).pastedRanges)
    }
    @Test fun slowerBatchIsNotAutomaticallyPasteAndVoiceIsDistinct() {
        val r = ProbeRecorder { 0 }; r.shown(0)
        r.edit("", "abcdefgh", 100)
        assertTrue(r.submit(101).pastedRanges.isEmpty())
        r.shown(200); r.voiceStart(230); r.voiceEdit("", "这是语音识别得到的一句话", 250)
        val t = r.submit(300)
        assertEquals(230L, t.firstInputAt); assertTrue(t.viaVoice); assertTrue(t.pastedRanges.isEmpty())
    }
}
