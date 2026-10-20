package com.zongkong.core.moling

import com.zongkong.core.agent.FakeEnv
import com.zongkong.core.agent.ZkTools
import com.zongkong.core.plan.Plans
import com.zongkong.core.work.NoteType
import com.zongkong.core.work.TimeParse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MolingTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 11, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun cmd(vararg kv: Pair<String, String>) = mapOf(*kv) + mapOf("v" to "1", "reason" to "墨灵的水道")

    private fun order(f: Map<String, String>, now: Long = at(9, 30)): MolingOrder {
        val (b, r) = Moling.receive(MolingBook(), f, now)
        assertTrue(r.message, r.ok)
        return b.orders.single { it.id == r.id }
    }

    // ---------------------------------------------------------------- 收

    @Test fun refusesWhatZongkongShouldNotDo() {
        fun refused(f: Map<String, String>) = !Moling.receive(MolingBook(), f, at(10)).second.ok
        assertTrue(refused(cmd("cmd" to "tighten", "kind" to "block_app", "value" to "com.android.phone")))
        assertTrue(refused(cmd("cmd" to "tighten", "kind" to "block_app", "value" to "com.zongkong.app")))
        assertTrue(refused(cmd("cmd" to "tighten", "kind" to "cut_play", "value" to "30")))
        assertTrue(refused(cmd("cmd" to "request_loosen", "kind" to "pause_all", "value" to "1")))
        assertTrue(refused(cmd("cmd" to "start_focus", "minutes" to "600", "title" to "x")))
        assertTrue(refused(cmd("cmd" to "uninstall")))
        assertTrue(refused(mapOf("cmd" to "set_main", "title" to "写申论", "v" to "9")))
        assertTrue(refused(mapOf("cmd" to "set_main", "title" to "写申论")))
        assertFalse(refused(cmd("cmd" to "tighten", "kind" to "block_app", "value" to "com.ss.android.ugc.aweme")))
    }

    @Test fun samePendingOrderIsNotQueuedTwice() {
        val f = cmd("cmd" to "start_focus", "minutes" to "25", "title" to "写申论")
        val (b1, r1) = Moling.receive(MolingBook(), f, at(10))
        val (b2, r2) = Moling.receive(b1, f + ("reason" to "换了个说法"), at(10, 5))
        assertEquals(r1.id, r2.id)
        assertEquals(1, b2.orders.size)
    }

    @Test fun limitsPendingAndExpires() {
        var b = MolingBook()
        repeat(Moling.MAX_PENDING) { i -> b = Moling.receive(b, cmd("cmd" to "note", "text" to "第 $i 条"), at(10)).first }
        assertFalse(Moling.receive(b, cmd("cmd" to "note", "text" to "再一条"), at(10, 1)).second.ok)
        val later = at(10) + Moling.TTL_MS
        assertTrue(Moling.pending(b, later).isEmpty())
        val (b2, r) = Moling.receive(b, cmd("cmd" to "note", "text" to "再一条"), later)
        assertTrue(r.ok)
        assertEquals(Moling.MAX_PENDING, b2.orders.count { it.state == OrderState.EXPIRED })
    }

    @Test fun declineAndFinishRecordState() {
        val o = order(cmd("cmd" to "note", "text" to "材料"))
        val b = MolingBook(listOf(o))
        assertEquals(OrderState.DECLINED, Moling.decline(b, o.id, at(10)).orders.single().state)
        val done = Moling.finish(b, o.id, com.zongkong.core.agent.ToolResult("ok", "记下了"), at(10))
        assertEquals(OrderState.DONE, done.orders.single().state)
        assertEquals("记下了", done.orders.single().result)
    }

    // ---------------------------------------------------------------- 做

    @Test fun setMainCreatesActionUnderMolingThreadAndReusesIt() = runTest {
        val env = FakeEnv()
        val tools = ZkTools(env)
        val r = Moling.run(order(cmd("cmd" to "set_main", "title" to "写申论第二题", "at" to at(15).toString(), "done" to "写完 800 字")), tools, { env.w }, env.nowMs, zone)
        assertTrue(r.text, r.ok)
        val a = env.w.actions().single { it.title == "写申论第二题" }
        assertEquals(at(15), TimeParse.startMillis(a.plan!!, zone))
        assertEquals("写完 800 字", a[com.zongkong.core.work.F.CRITERIA])
        assertEquals("墨灵的水道", env.w.rec(a.threadKey)!!.title)
        assertEquals(a.key, Plans.day(env.book, com.zongkong.core.DayClock.dateKey(env.nowMs, zone))!!.mainActionKey)
        assertTrue(Moling.run(order(cmd("cmd" to "set_main", "title" to "写申论第二题", "at" to "0", "done" to "")), tools, { env.w }, env.nowMs, zone).ok)
        assertEquals(1, env.w.actions().count { it.title == "写申论第二题" })
        assertEquals(1, env.w.threads().count { it.title == "墨灵的水道" })
    }

    @Test fun addActionKeepsTimesAndDoesNotDuplicate() = runTest {
        val env = FakeEnv()
        val tools = ZkTools(env)
        val f = cmd("cmd" to "add_action", "title" to "背单词", "start" to at(20).toString(), "end" to at(20, 45).toString(), "done" to "")
        assertTrue(Moling.run(order(f), tools, { env.w }, env.nowMs, zone).ok)
        val a = env.w.actions().single { it.title == "背单词" }
        assertEquals(at(20), TimeParse.startMillis(a.plan!!, zone))
        assertEquals(at(20, 45), TimeParse.endMillis(a.plan!!, zone))
        Moling.run(order(f), tools, { env.w }, env.nowMs, zone)
        assertEquals(1, env.w.actions().count { it.title == "背单词" })
    }

    @Test fun startFocusLocksTheAction() = runTest {
        val env = FakeEnv()
        val r = Moling.run(order(cmd("cmd" to "start_focus", "minutes" to "25", "title" to "写申论")), ZkTools(env), { env.w }, env.nowMs, zone)
        assertTrue(r.text, r.ok)
        val s = env.w.session
        assertNotNull(s)
        assertEquals(env.w.actions().single { it.title == "写申论" }.key, s!!.actionKey)
        assertEquals(env.nowMs + 25 * 60_000L, s.lockUntil)
    }

    @Test fun blockingIsImmediateButUnblockingWaits() = runTest {
        val env = FakeEnv()
        val tools = ZkTools(env)
        val pkg = "com.ss.android.ugc.aweme"
        assertTrue(Moling.run(order(cmd("cmd" to "tighten", "kind" to "block_app", "value" to pkg)), tools, { env.w }, env.nowMs, zone).ok)
        assertTrue(pkg in env.cfg.blocked)
        Moling.run(order(cmd("cmd" to "request_loosen", "kind" to "unblock_app", "value" to pkg)), tools, { env.w }, env.nowMs, zone)
        assertTrue("放宽要等冷静期", pkg in env.cfg.blocked)
        assertTrue(env.cfg.pending.isNotEmpty())
    }

    @Test fun noteLandsInInbox() = runTest {
        val env = FakeEnv()
        val r = Moling.run(order(cmd("cmd" to "note", "text" to "申论开头先亮观点")), ZkTools(env), { env.w }, env.nowMs, zone)
        assertTrue(r.ok)
        val n = env.w.notes().single()
        assertEquals(NoteType.MATERIAL, n.noteType)
        assertTrue(n.title.contains("申论开头先亮观点"))
    }

    @Test fun runChecksAgainBeforeDoing() = runTest {
        val env = FakeEnv()
        val bad = MolingOrder("x", cmd("cmd" to "tighten", "kind" to "block_app", "value" to "com.android.settings"), at(10))
        assertFalse(Moling.run(bad, ZkTools(env), { env.w }, env.nowMs, zone).ok)
        assertTrue(env.cfg.blocked.isEmpty())
    }

    @Test fun statusMapHasEveryField() {
        val m = MolingStatus(at = 1, mainTitle = "写申论", aims = listOf("上岸", "考研"), blocked = listOf("a", "b")).toMap()
        assertEquals("写申论", m["mainTitle"])
        assertEquals("上岸\n考研", m["aims"])
        assertEquals(27, m.size)
    }
}
