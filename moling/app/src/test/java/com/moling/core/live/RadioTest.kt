package com.moling.core.live

import com.moling.core.workspace.Focus
import com.moling.core.workspace.Material
import com.moling.core.workspace.PortResult
import com.moling.core.workspace.WorkbenchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R1：电台状态机、停止后迟到回调、重复 start、暂停/跳过/预算。 */
class RadioTest {
    private val t0 = 1_000_000L

    private fun seg(id: String, ms: Int = 30_000, text: String = "这一段讲一个点。") =
        RadioSegment(id, SegmentKind.QUOTE, text, listOf("m-1"), ms)

    private fun enginePlaying(now: Long = t0): Pair<RadioEngine, RadioSession> {
        val e = RadioEngine()
        val r = e.start("f-1", listOf("m-1"), listOf(seg("s1"), seg("s2"), seg("s3")), now)
        return e to (r as PortResult.Ok).value
    }

    @Test fun startsPreparingThenPlaying() {
        val e = RadioEngine()
        val pre = (e.start("f-1", listOf("m-1"), emptyList(), t0) as PortResult.Ok).value
        assertEquals(RadioState.PREPARING, pre.state)
        assertEquals(t0 + Radio.PREPARE_TIMEOUT_MS, pre.segmentDeadline)
        assertTrue(e.onPrepared(pre.id, pre.generation, listOf(seg("s1")), t0 + 100))
        val s = e.current!!
        assertEquals(RadioState.PLAYING, s.state)
        assertEquals("s1", s.segmentId)
        assertEquals(t0 + 100 + 30_000L, s.segmentDeadline)
        assertTrue(s.endsAt <= t0 + 100 + Radio.MAX_SESSION_MS)
    }

    @Test fun repeatedStartIsIdempotent() {
        val (e, s) = enginePlaying()
        e.tick(t0 + 40_000) // 已经在第二段
        val again = (e.start("f-1", listOf("m-1"), listOf(seg("s1"), seg("s2"), seg("s3")), t0 + 41_000) as PortResult.Ok).value
        assertEquals("同一件、同一来源重复 start 不换会话", s.id, again.id)
        assertEquals(1, again.index)
    }

    @Test fun pauseFreezesAndResumeContinues() {
        val (e, s) = enginePlaying()
        val paused = (e.pause(s.id, t0 + 10_000) as PortResult.Ok).value
        assertEquals(RadioState.PAUSED, paused.state)
        assertEquals(20_000L, paused.pausedRemainingMs)
        // 暂停期间 tick 不往下走（还停在第一段）
        e.tick(t0 + 100_000)
        assertEquals(0, e.current!!.index)
        // 重复 pause 幂等
        assertEquals(RadioState.PAUSED, (e.pause(s.id, t0 + 120_000) as PortResult.Ok).value.state)
        val resumed = (e.resume(s.id, t0 + 120_000) as PortResult.Ok).value
        assertEquals(RadioState.PLAYING, resumed.state)
        assertEquals(t0 + 140_000L, resumed.segmentDeadline)
        // 暂停时间不占 5 分钟
        assertTrue(resumed.endsAt > s.endsAt)
        assertTrue((e.resume(s.id, t0 + 150_000) as PortResult.Ok).value.state == RadioState.PLAYING)
    }

    @Test fun skipAdvancesAndFinishesAtEnd() {
        val (e, s) = enginePlaying()
        val one = (e.skip(s.id, t0 + 1_000) as PortResult.Ok).value
        assertEquals(1, one.index)
        val two = (e.skip(s.id, t0 + 2_000) as PortResult.Ok).value
        assertEquals(2, two.index)
        val done = (e.skip(s.id, t0 + 3_000) as PortResult.Ok).value
        assertEquals(RadioState.FINISHED, done.state)
        assertTrue(e.skip(s.id, t0 + 4_000) is PortResult.Failed)
    }

    @Test fun stopClearsQueueAndRejectsLateCallbacks() {
        val (e, s) = enginePlaying()
        e.tick(t0 + 35_000) // 第二段
        val stopped = (e.stop(s.id, t0 + 36_000) as PortResult.Ok).value
        assertEquals(RadioState.STOPPED, stopped.state)
        assertEquals("只留已经讲过的段，待播清空", 2, stopped.segments.size)
        assertEquals(0L, stopped.segmentDeadline)
        // 迟到的 TTS 回调：这一段 / 上一段都不能再开播
        assertFalse(e.onSegmentDone(s.id, "s2", s.generation, t0 + 40_000))
        assertFalse(e.onSegmentDone(s.id, "s1", s.generation, t0 + 40_000))
        // 迟到的“内容准备好了”也不能重播
        assertFalse(e.onPrepared(s.id, s.generation, listOf(seg("late")), t0 + 41_000))
        // 再 tick 也不会复活
        assertEquals(RadioState.STOPPED, e.tick(t0 + 100_000)!!.state)
        // 重复 stop 幂等
        assertEquals(RadioState.STOPPED, (e.stop(s.id, t0 + 101_000) as PortResult.Ok).value.state)
    }

    @Test fun latePreparedAfterStopIsDroppedWhileStillPreparing() {
        val e = RadioEngine()
        val pre = (e.start("f-1", emptyList(), emptyList(), t0) as PortResult.Ok).value
        assertEquals(RadioState.PREPARING, pre.state)
        e.stop(pre.id, t0 + 1_000)
        assertFalse(e.onPrepared(pre.id, pre.generation, listOf(seg("s1")), t0 + 2_000))
        assertEquals(RadioState.STOPPED, e.current!!.state)
    }

    @Test fun oldSessionCallbacksAreRejectedAfterSwitch() {
        val (e, s1) = enginePlaying()
        val s2 = (e.start("f-2", listOf("m-2"), listOf(seg("x1")), t0 + 5_000) as PortResult.Ok).value
        assertTrue(s2.generation > s1.generation)
        assertFalse(e.onSegmentDone(s1.id, "s1", s1.generation, t0 + 6_000))
        assertTrue(e.onSegmentDone(s2.id, "x1", s2.generation, t0 + 6_000))
        assertEquals(RadioState.FINISHED, e.current!!.state)
    }

    @Test fun budgetEndsAtFiveMinutes() {
        val e = RadioEngine()
        val many = (1..8).map { seg("s$it", 90_000) }
        val s = (e.start("f-1", emptyList(), many, t0) as PortResult.Ok).value
        assertEquals(Radio.MAX_SEGMENTS, s.segments.size)
        val ended = e.tick(t0 + Radio.MAX_SESSION_MS + 1)!!
        assertEquals(RadioState.FINISHED, ended.state)
        assertTrue(ended.stopReason.contains("5 分钟"))
        // 每段夹在 30–90 秒
        assertTrue(s.segments.all { it.ms in Radio.MIN_SEGMENT_MS..Radio.MAX_SEGMENT_MS })
    }

    @Test fun prepareTimeoutEndsInsteadOfWaitingForever() {
        val e = RadioEngine()
        val s = (e.start("f-1", emptyList(), emptyList(), t0) as PortResult.Ok).value
        val ended = e.tick(t0 + Radio.PREPARE_TIMEOUT_MS)!!
        assertEquals(RadioState.FINISHED, ended.state)
        assertEquals("准备超时", ended.stopReason)
    }

    @Test fun quietBlocksOnlyNonExplicitStart() {
        val e = RadioEngine()
        val denied = e.start("f-1", emptyList(), listOf(seg("s1")), t0, explicit = false, quiet = true)
        assertTrue(denied is PortResult.Failed)
        assertEquals("QUIET", (denied as PortResult.Failed).error.code)
        val ok = e.start("f-1", emptyList(), listOf(seg("s1")), t0 + 1, explicit = true, quiet = true)
        assertTrue(ok is PortResult.Ok)
    }

    @Test fun progressAndRemainingStayInRange() {
        val (e, s) = enginePlaying()
        val first = Radio.progress(s, t0 + 1_000)
        assertTrue("刚开始也在 0..1 之间", first in 0f..0.05f)
        assertEquals(30_000L - 1_000L, Radio.remainingMs(s, t0 + 1_000))
        val mid = e.tick(t0 + 15_000)!!
        val p = Radio.progress(mid, t0 + 15_000)
        assertTrue(p in 0f..1f)
        assertTrue(p > 0f)
    }

    @Test fun contentPlansOnlyExistingMaterialsAndKeepsSources() {
        val focus = Focus("f-1", "怎样把一段材料讲清楚", startedAt = t0, materialIds = listOf("m-1"))
        val ws = WorkbenchState(
            focuses = listOf(focus),
            materials = listOf(Material("m-1", "paste", "一段材料", "这是用户给的材料正文。它只在这里，不在网上。", t0)),
        )
        val segs = RadioContent.fromWorkbench(ws, focus, t0)
        assertTrue(segs.isNotEmpty())
        assertTrue("材料原文要带来源 id", segs.any { it.kind == SegmentKind.QUOTE && it.sourceIds.contains("m-1") })
        assertTrue("先接上上次", segs.first().text.contains("上次"))
        assertTrue(segs.size <= Radio.MAX_SEGMENTS)
        assertTrue(segs.all { it.text.length <= RadioContent.MAX_CHARS })
        assertEquals("不联网：材料以外的 id 不出现", setOf("m-1"), segs.flatMap { it.sourceIds }.toSet())
    }

    @Test fun contentWithNoMaterialFinishesWithReadableMessage() {
        val focus = Focus("f-2", "", startedAt = t0)
        val ws = WorkbenchState(focuses = listOf(focus))
        val segs = RadioContent.fromWorkbench(ws, focus, t0)
        assertTrue(segs.isEmpty())
        val e = RadioEngine()
        val s = (e.start("f-2", emptyList(), segs, t0) as PortResult.Ok).value
        assertEquals(RadioState.PREPARING, s.state)
        // App 拿到空内容时补一次 onPrepared，给一句能看懂的结束原因，不卡在“准备中”
        assertTrue(e.onPrepared(s.id, s.generation, segs, t0 + 1))
        assertEquals(RadioState.FINISHED, e.current!!.state)
        assertEquals("没有可讲的材料", e.current!!.stopReason)
    }

    @Test fun engineTickOnEmptySessionDoesNotCrash() {
        val e = RadioEngine()
        assertNull(e.tick(t0))
        assertNull(e.pause("nope", t0).valueOrNull())
        assertNotNull((e.start("f-1", emptyList(), listOf(seg("s1")), t0) as PortResult.Ok).value)
    }
}
