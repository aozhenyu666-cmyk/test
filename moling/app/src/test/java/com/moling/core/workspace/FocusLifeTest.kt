package com.moling.core.workspace

import com.moling.core.mind.MindStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 焦点两小时到期变“可续上”（v13 W10 T8）：不删东西、续上是同一件事、在做的代工不打断。 */
class FocusLifeTest {
    private class MemStore : MindStore {
        var json: String? = null
        override fun load() = json
        override fun save(json: String) { this.json = json }
    }

    private val h = 3_600_000L

    @Test fun expiresAfterTwoQuietHoursAndResumesTheSameFocus() = runTest {
        val w = Workbench(MemStore(), null)
        val f = w.start("申论开头", "开头最容易卡。", 0)
        w.prework(f.id, JobKind.OUTLINE, 1_000) // 离线骨架也算一份稿子
        assertFalse("还没到两小时", w.expire(h))
        assertTrue(w.expire(1_000 + 2 * h))
        val paused = w.state().focus(f.id)!!
        assertEquals(FocusStatus.PAUSED, paused.status)
        assertEquals(FocusLife.EXPIRED, paused.pauseReason)
        assertNull(w.state().active())
        assertEquals("稿子、材料都还在", 1, w.state().artifactsOf(f.id).size)
        assertEquals(1, paused.materialIds.size)
        assertTrue(FocusLife.resumeLine(w.state(), paused, 1_000 + 3 * h).contains("两小时没动"))

        val back = (w.switchTo(f.id, 5 * h) as PortResult.Ok).value
        assertEquals("续上是同一件事", f.id, back.id)
        assertEquals(FocusStatus.ACTIVE, back.status)
        assertEquals(1, w.state().focuses.size)
        assertEquals(1, w.state().artifactsOf(f.id).size)
        assertFalse("续上之后重新算两小时", w.expire(6 * h))
        assertTrue(w.expire(7 * h))
    }

    @Test fun touchingTheFocusKeepsItAlive() = runTest {
        val w = Workbench(MemStore(), null)
        val f = w.start("申论开头", "开头最容易卡。", 0)
        w.addMaterial(f.id, "再补一句", 90 * 60_000L)
        assertFalse("补了材料，从那时起再算", w.expire(2 * h + 60_000L))
        assertTrue(w.expire(90 * 60_000L + 2 * h))
    }

    @Test fun doesNotExpireWhileHerJobIsRunning() {
        val f = Focus("f-1", "q", startedAt = 0, expiresAt = 2 * h)
        val job = PreworkJob("j-1", JobKind.OUTLINE, "f-1", 1, "纲要", emptyList(), "x", JobStatus.RUNNING, 2 * h - 1, 3 * h, 1)
        val st = WorkbenchState(focuses = listOf(f), jobs = listOf(job))
        assertTrue("她还在做，不收起", FocusLife.expire(st, 2 * h + 1) === st)
        assertEquals(FocusStatus.PAUSED, FocusLife.expire(st.copy(jobs = emptyList()), 2 * h + 1).focuses.single().status)
    }

    @Test fun oldRecordsWithoutExpiryUseTheirLastActivity() {
        val f = Focus("f-1", "q", startedAt = 0, materialIds = listOf("m-1"))
        val st = WorkbenchState(focuses = listOf(f), materials = listOf(Material("m-1", "paste", "t", "x", 3 * h)))
        assertTrue(FocusLife.expire(st, 4 * h) === st)
        assertEquals(FocusStatus.PAUSED, FocusLife.expire(st, 5 * h).focuses.single().status)
    }

    @Test fun switchingAwayParksTheOtherAsSwitched() = runTest {
        val w = Workbench(MemStore(), null)
        val a = w.start("一", "材料一", 0)
        w.start("二", "材料二", 10)
        assertEquals(FocusLife.SWITCHED, w.state().focus(a.id)!!.pauseReason)
        assertTrue(FocusLife.resumeLine(w.state(), w.state().focus(a.id)!!, 20).startsWith("收起"))
    }
}
