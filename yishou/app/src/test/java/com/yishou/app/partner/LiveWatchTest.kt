package com.yishou.app.partner

import com.yishou.app.partner.LiveWatch.Act
import com.yishou.app.partner.LiveWatch.Seen
import com.yishou.app.partner.LiveWatch.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWatchTest {

    private val gemini = Seen("com.google.android.apps.bard", inGemini = true, mic = true)
    private val douyin = Seen("com.ss.android.ugc.aweme")
    private val keyboard = Seen("com.sohu.inputmethod.sogou", neutral = true)

    /** 从 from 到 to 每秒走一步，收集动作 */
    private fun LiveWatch.run(from: Long, to: Long, s: Seen): List<Act> =
        (from..to step 1_000).map { step(it, s) }.filter { it != Act.None }

    @Test
    fun liveOnIsFine() {
        val w = LiveWatch(live = true, pull = true)
        assertTrue(w.run(0, 120_000, gemini).isEmpty())
        assertEquals(State.ON, w.state)
        assertEquals("Live 在", w.label(120_000))
    }

    @Test
    fun liveClosedThenScrollingGetsNudgedThenPulled() {
        val w = LiveWatch(live = true, pull = true)
        w.run(0, 10_000, gemini)
        val acts = w.run(11_000, 70_000, douyin)
        assertTrue(acts[0] is Act.Nudge)
        assertTrue((acts[0] as Act.Nudge).text.contains("Live 断了"))
        assertEquals(Act.Pull, acts[1])
        assertEquals(2, acts.size)
        assertEquals(1, w.pulls)
        // 拉回来以后 Live 没开：提醒点 Live，不再拉
        val back = w.run(71_000, 120_000, gemini.copy(mic = false))
        assertEquals(1, back.size)
        assertTrue((back[0] as Act.Nudge).text.contains(Opening.TRIGGER))
        assertEquals(State.IDLE, w.state)
    }

    @Test
    fun liveWithScreenShareInMaterialAppIsFine() {
        val w = LiveWatch(live = true, pull = true)
        assertTrue(w.run(0, 120_000, douyin.copy(material = true, mic = true)).isEmpty())
        // Live 开着，但去了没登记的应用：只提醒，不拉
        val acts = w.run(121_000, 300_000, douyin.copy(pkg = "com.tencent.mm", mic = true))
        assertEquals(1, acts.size)
        assertTrue(acts[0] is Act.Nudge)
        assertEquals(State.OFF_TRACK, w.state)
    }

    @Test
    fun keyboardAndShadeDoNotCount() {
        val w = LiveWatch(live = true, pull = true)
        w.run(0, 5_000, gemini)
        assertTrue(w.run(6_000, 200_000, keyboard).isEmpty())
        assertEquals(State.ON, w.state)
    }

    @Test
    fun pullsAtMostThreeTimes() {
        val w = LiveWatch(live = true, pull = true, strict = true)
        var t = 0L
        repeat(5) {
            w.run(t, t + 1_000, gemini)
            t += 2_000
            w.run(t, t + 60_000, douyin)
            t += 61_000
        }
        assertEquals(LiveWatch.MAX_PULLS, w.pulls)
        assertTrue(w.label(t)!!.contains("拉回 3 次"))
    }

    @Test
    fun noPullWhenOffAndTextModeIgnoresMic() {
        val w = LiveWatch(live = false, pull = false)
        assertTrue(w.run(0, 60_000, gemini.copy(mic = false)).isEmpty())
        assertTrue(w.run(61_000, 70_000, douyin.copy(material = true)).isEmpty())
        val acts = w.run(71_000, 200_000, douyin)
        assertEquals(1, acts.size)
        assertTrue((acts[0] as Act.Nudge).text.contains("回 Gemini"))
        assertEquals(0, w.pulls)
    }

    @Test
    fun unknownResets() {
        val w = LiveWatch(live = true, pull = true)
        w.run(0, 30_000, douyin)
        assertTrue(w.run(31_000, 100_000, Seen(null)).isEmpty())
        assertEquals(State.UNKNOWN, w.state)
    }

    @Test
    fun spokenLineAndSavedInfo() {
        val plan = PartnerPlan(Material.VIDEO, "县城咖啡店", 25, Gear.STRICT)
        val line = Opening.spoken(plan, "做两道削弱题")
        assertTrue(line.startsWith(Opening.TRIGGER))
        assertTrue(line.contains("严"))
        assertTrue(line.contains("做两道削弱题"))
        assertTrue(Opening.savedInfo().contains(Opening.STOP_WORD))
    }
}
