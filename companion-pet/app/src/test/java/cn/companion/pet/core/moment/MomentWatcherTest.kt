package cn.companion.pet.core.moment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** 时机：认出具体时刻；更重要的是知道什么时候闭嘴。 */
class MomentWatcherTest {
    // 2026-10-25 是周日
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
    private val zone = ZoneOffset.UTC

    @Test
    fun `刚解锁是一个时刻`() {
        val s = MomentSignals(now = at("2026-10-26T10:00:00Z"), screenJustOn = true, zone = zone)
        assertTrue(Moment.JUST_UNLOCKED in MomentWatcher.detect(s))
    }

    @Test
    fun `它自己刚读完一段是最像活人的时刻`() {
        val s = MomentSignals(now = at("2026-10-26T10:00:00Z"), ownThoughtReady = true, zone = zone)
        assertTrue(Moment.OWN_THOUGHT_READY in MomentWatcher.detect(s))
    }

    @Test
    fun `周末与深夜都能被认出来`() {
        val weekend = MomentSignals(now = at("2026-10-25T15:00:00Z"), zone = zone)
        assertTrue(Moment.WEEKEND in MomentWatcher.detect(weekend))
        val late = MomentSignals(now = at("2026-10-26T23:30:00Z"), zone = zone)
        assertTrue(Moment.LATE_NIGHT in MomentWatcher.detect(late))
    }

    @Test
    fun `正在打字时必须闭嘴`() {
        val s = MomentSignals(now = at("2026-10-26T10:00:00Z"), screenJustOn = true, typing = true, zone = zone)
        assertTrue("哪怕刚解锁，打字时也不许说", MomentWatcher.mustStaySilent(s))
    }

    @Test
    fun `看全屏视频时必须闭嘴`() {
        val s = MomentSignals(now = at("2026-10-26T10:00:00Z"), fullscreenVideo = true, zone = zone)
        assertTrue(MomentWatcher.mustStaySilent(s))
    }

    @Test
    fun `刚说过不到三分钟必须闭嘴`() {
        val s = MomentSignals(
            now = at("2026-10-26T10:00:00Z"), screenJustOn = true,
            minutesSinceLastSpoken = 2, zone = zone,
        )
        assertTrue(MomentWatcher.mustStaySilent(s))
    }

    @Test
    fun `什么都没发生的时候不说话`() {
        val s = MomentSignals(now = at("2026-10-26T10:00:00Z"), zone = zone)
        assertTrue("没有可识别的时刻就不该开口", MomentWatcher.detect(s).isEmpty())
    }

    // 以下两条为简报之外补充：闭嘴优先于「有话想说」，以及三分钟边界应当放行。

    @Test
    fun `刚攒出一句话但正在打字也要闭嘴`() {
        val s = MomentSignals(
            now = at("2026-10-26T10:00:00Z"), ownThoughtReady = true,
            typing = true, zone = zone,
        )
        assertTrue("有话想说也不能打断打字", MomentWatcher.mustStaySilent(s))
    }

    @Test
    fun `刚好说够三分钟就该放行`() {
        val s = MomentSignals(
            now = at("2026-10-26T10:00:00Z"), screenJustOn = true,
            minutesSinceLastSpoken = 3, zone = zone,
        )
        assertFalse("规则是小于三分钟才闭嘴，等于三分钟不拦", MomentWatcher.mustStaySilent(s))
    }
}
