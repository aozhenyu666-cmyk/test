package com.yishou.app.companion

import com.yishou.app.companion.core.AudioSignal
import com.yishou.app.companion.core.PauseOutcome
import com.yishou.app.companion.core.PauseProof
import com.yishou.app.companion.core.Pcm
import com.yishou.app.companion.core.PlayState
import com.yishou.app.companion.core.ProbeVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 有没有声音、有没有真的停住——两件最容易被「指令发出去了」糊过去的事。 */
class SignalTest {

    @Test
    fun `全零的 PCM 是静音`() {
        assertEquals(0.0, Pcm.rmsOf(ShortArray(64)), 0.0001)
        assertEquals(0, Pcm.peakOf(ShortArray(64)))
    }

    @Test
    fun `方波的均方根等于幅度`() {
        val pcm = ShortArray(100) { if (it % 2 == 0) 1000 else -1000 }
        assertEquals(1000.0, Pcm.rmsOf(pcm), 0.0001)
        assertEquals(1000, Pcm.peakOf(pcm))
    }

    @Test
    fun `帧时长按采样率换算`() {
        assertEquals(1_000L, Pcm.durationMs(16_000, 16_000))
        assertEquals(0L, Pcm.durationMs(100, 0))
    }

    @Test
    fun `一直是静音时不算听到声音`() {
        val s = AudioSignal(threshold = 200.0, minActiveMs = 400)
        repeat(10) { s.feed(0.0, 100) }
        assertFalse(s.heard())
        assertTrue(s.summary().contains("静音"))
        assertEquals(1_000L, s.totalMs)
    }

    @Test
    fun `响够了才算听到`() {
        val s = AudioSignal(threshold = 200.0, minActiveMs = 400)
        s.feed(900.0, 200)
        assertFalse(s.heard())
        s.feed(900.0, 200)
        assertTrue(s.heard())
        assertTrue(s.summary().contains("收到声音"))
    }

    @Test
    fun `发过指令但还在放不算暂停`() {
        val outcome = PauseProof.judge(sent = true, before = PlayState.PLAYING, after = PlayState.PLAYING)
        assertEquals(PauseOutcome.STILL_PLAYING, outcome)
        assertEquals(ProbeVerdict.UNKNOWN, outcome.verdict)
    }

    @Test
    fun `看到状态变成暂停才算核实`() {
        assertEquals(PauseOutcome.VERIFIED, PauseProof.judge(true, PlayState.PLAYING, PlayState.PAUSED))
        assertEquals(PauseOutcome.VERIFIED, PauseProof.judge(true, PlayState.PLAYING, PlayState.STOPPED))
        assertEquals(ProbeVerdict.OK, PauseOutcome.VERIFIED.verdict)
    }

    @Test
    fun `没有会话就是不支持`() {
        val outcome = PauseProof.judge(sent = false, before = PlayState.UNKNOWN, after = PlayState.UNKNOWN)
        assertEquals(PauseOutcome.UNSUPPORTED, outcome)
    }

    @Test
    fun `看不到状态时不猜`() {
        assertEquals(PauseOutcome.NO_EVIDENCE, PauseProof.judge(true, PlayState.PLAYING, PlayState.UNKNOWN))
    }

    @Test
    fun `本来没在放时不冒充按了暂停`() {
        assertEquals(PauseOutcome.WAS_IDLE, PauseProof.judge(true, PlayState.PAUSED, PlayState.PAUSED))
    }

    @Test
    fun `只有自己停的、还是同一路会话、用户没插手才恢复`() {
        assertTrue(PauseProof.canResume(PauseOutcome.VERIFIED, stillSameSession = true, userTouched = false))
        assertFalse(PauseProof.canResume(PauseOutcome.VERIFIED, stillSameSession = false, userTouched = false))
        assertFalse(PauseProof.canResume(PauseOutcome.VERIFIED, stillSameSession = true, userTouched = true))
        assertFalse(PauseProof.canResume(PauseOutcome.STILL_PLAYING, stillSameSession = true, userTouched = false))
    }

    @Test
    fun `播放状态整数翻译认不出的一律不猜`() {
        assertEquals(PlayState.PLAYING, PlayState.of(3))
        assertEquals(PlayState.PAUSED, PlayState.of(2))
        assertEquals(PlayState.STOPPED, PlayState.of(1))
        assertEquals(PlayState.UNKNOWN, PlayState.of(6))
        assertEquals(PlayState.UNKNOWN, PlayState.of(7))
    }
}
