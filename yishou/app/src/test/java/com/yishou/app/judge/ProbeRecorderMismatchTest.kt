package com.yishou.app.judge

import org.junit.Assert.*
import org.junit.Test

class ProbeRecorderMismatchTest {
    @Test fun `输入回填次序错了也不崩`() {
        val r = ProbeRecorder { 0L }
        r.shown(0)
        r.edit("", "先写一句", 1_000)
        r.edit("过期的文字", "后来的一整段文字有具体判断", 2_000)
        r.voiceEdit("也不是当前文字", "语音回填", 3_000)
        assertEquals(1_000L, r.submit(4_000).firstInputAt)
        assertTrue(r.submit(4_000).viaVoice)
        r.edit("语音回填", "语音回填再补一句", 5_000)
        assertTrue(r.submit(6_000).pastedRanges.isEmpty())
    }
    @Test fun `旧值与新值相同但和内部对不上也按替换处理`() {
        val r = ProbeRecorder { 0L }
        r.shown()
        r.edit("", "当前文字", 1_000)
        r.edit("别的值", "别的值", 2_000)
        r.edit("别的值", "别的值又改了", 3_000)
        assertEquals(3_000L, r.submit(3_000).submitAt)
    }
}
