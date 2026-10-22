package com.moling.core.flow

import com.moling.core.link.ZkCommand
import com.moling.core.mind.BEIJING
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 第十三批 G1/G2：本地计划代驾、可见时间、改版/换日、执行账本幂等与回执分离。 */
class PlanDriverTest {
    private val day = "2026-10-22"

    private fun at(h: Int, mi: Int = 0, d: Int = 22) =
        LocalDateTime.of(2026, 10, d, h, mi).atZone(BEIJING).toInstant().toEpochMilli()

    private fun plan(
        day: String = this.day,
        intent: String = "今天只推申论",
        main: String = "写申论",
        startMin: Int = 9 * 60,
        confirmed: Boolean = false,
    ) = FlowPlan(
        day = day,
        intent = intent,
        channels = listOf(
            Channel("main-0", ChannelKind.MAIN, main, startMin, 60, "交出一段"),
            Channel("pool-0", ChannelKind.POOL, "歇一会儿", startMin + 60, 20, "到点回来"),
        ),
        risks = emptyList(),
        confirmed = confirmed,
    )

    private class Harness(policy: AdoptPolicy = AdoptPolicy(), initial: PlanDriverState = PlanDriverState()) {
        val saves = mutableListOf<PlanDriverState>()
        var p = policy
        val driver = PlanDriver(initial, { p }, { saves += it })
    }

    @Test fun draftIsNotAdoptedWhenSwitchIsOff() {
        val h = Harness(AdoptPolicy(enabled = false))
        val p = plan()
        h.driver.putDraft(p, at(9))
        repeat(30) { h.driver.tick(at(9, 1 + it), visible = true) }
        val a = h.driver.adoption(day)!!
        assertEquals(AdoptionState.DRAFT, a.state)
        assertTrue("可见时间照常累计", a.visibleMs > 0)
        assertFalse(p.confirmed)
        assertEquals(0, h.driver.snapshot().operations.size)
    }

    @Test fun autoAdoptAfterTwentyVisibleMinutesButNeverExecutes() {
        val h = Harness(AdoptPolicy(enabled = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.tick(at(9), visible = true)
        repeat(19) { h.driver.tick(at(9, 1 + it), visible = true) }
        assertEquals("19 分钟还不够", AdoptionState.DRAFT, h.driver.adoption(day)!!.state)
        h.driver.tick(at(9, 20), visible = true)
        val a = h.driver.adoption(day)!!
        assertEquals(AdoptionState.LOCAL_ADOPTED, a.state)
        assertTrue(a.message.contains("本地采用"))
        assertFalse("本地采用不改旧 confirmed", p.confirmed)
        assertEquals("没有自动准备/发送任何指令", 0, h.driver.snapshot().operations.size)
        assertEquals(0, h.driver.sentCount())
    }

    @Test fun lockScreenPausesTheCountdown() {
        val h = Harness(AdoptPolicy(enabled = true))
        h.driver.putDraft(plan(), at(9))
        h.driver.tick(at(9), visible = true)
        repeat(9) { h.driver.tick(at(9, 1 + it), visible = true) } // 9:01..9:09，约 9 分钟
        repeat(20) { h.driver.tick(at(9, 10 + it), visible = false) } // 锁屏/离开 9:10..9:29
        val paused = h.driver.adoption(day)!!
        assertTrue("锁屏期间不累计", paused.visibleMs <= 9 * 60_000L + 1)
        assertEquals(AdoptionState.DRAFT, h.driver.adoption(day)!!.state)
        repeat(10) { h.driver.tick(at(9, 30 + it), visible = true) } // 再看到 9:39，总可见约 18 分钟
        assertEquals(AdoptionState.DRAFT, h.driver.adoption(day)!!.state)
        h.driver.tick(at(9, 40), visible = true)
        assertEquals(AdoptionState.LOCAL_ADOPTED, h.driver.adoption(day)!!.state)
    }

    @Test fun restartDoesNotAdoptAnUnseenDraft() {
        val h = Harness(AdoptPolicy(enabled = true))
        h.driver.putDraft(plan(), at(23))
        h.driver.tick(at(23), visible = true)
        repeat(3) { h.driver.tick(at(23, 1 + it), visible = true) }
        val json = Json.encodeToString(h.driver.snapshot())
        val restored = Json.decodeFromString<PlanDriverState>(json)
        // 第二天中午才重启：昨天的草稿不自动采用，也没有今天的草稿。
        val again = Harness(AdoptPolicy(enabled = true), restored)
        again.driver.tick(at(12, d = 23), visible = true)
        assertNull(again.driver.adoption("2026-10-23"))
        assertEquals(AdoptionState.EXPIRED, again.driver.snapshot().adoption!!.state)
        assertTrue(again.driver.snapshot().operations.isEmpty())
    }

    @Test fun rewrittenPlanResetsCountdownAndCancelsUnsentCommands() {
        val h = Harness(AdoptPolicy(enabled = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        assertTrue(h.driver.adopt(p, at(9, 1)).ok)
        val prepared = h.driver.prepareExecution(p, Flow.toCommands(p, Flow.dayStart(day)), at(9, 2))
        assertTrue(prepared.ok)
        assertEquals(2, prepared.operations.size)
        val first = prepared.operations[0]
        h.driver.markSending(first.operationId, at(9, 3))
        h.driver.recordReceipt(first.operationId, PlanReceipt("r-1", ok = true, auto = false, message = "总控等确认"), at(9, 3))
        assertEquals(OperationState.ZK_PENDING, h.driver.operation(first.operationId)!!.state)

        val changed = plan(intent = "改成下午推", startMin = 14 * 60)
        h.driver.putDraft(changed, at(9, 5))
        val state = h.driver.snapshot()
        assertEquals(2, state.drafts.single().revision)
        assertEquals(AdoptionState.DRAFT, state.adoption!!.state)
        assertEquals(0, state.adoption!!.visibleMs)
        assertEquals("已经发出的保持回执", OperationState.ZK_PENDING, h.driver.operation(first.operationId)!!.state)
        val canceled = prepared.operations[1]
        assertEquals("没发的取消", OperationState.CANCELLED, h.driver.operation(canceled.operationId)!!.state)
        assertTrue(state.adoption!!.message.contains("取消"))
        assertEquals(1, h.driver.sentCount())
    }

    @Test fun changingFocusRequiresRecheck() {
        val h = Harness(AdoptPolicy(enabled = true))
        val p = plan()
        h.driver.putDraft(p, at(9), focusKey = "focus-a")
        val bad = h.driver.adopt(p, at(9, 1), focusKey = "focus-b")
        assertFalse(bad.ok)
        assertEquals(ConflictKind.FOCUS_CHANGED, bad.conflicts.single().kind)
        h.driver.putDraft(p, at(9, 2), focusKey = "focus-b")
        assertEquals("焦点换了，倒计时重来", AdoptionState.DRAFT, h.driver.adoption(day)!!.state)
        assertTrue(h.driver.adopt(p, at(9, 3), focusKey = "focus-b").ok)
    }

    @Test fun changedPlanCannotBeAdoptedWithStaleHash() {
        val h = Harness(AdoptPolicy(enabled = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        val edited = p.copy(intent = "临时改的")
        val r = h.driver.adopt(edited, at(9, 1))
        assertFalse(r.ok)
        assertEquals(ConflictKind.PLAN_CHANGED, r.conflicts.single().kind)
        assertTrue(h.driver.adopt(p, at(9, 2)).ok)
    }

    @Test fun tooOldDraftIsNotAdopted() {
        val h = Harness(AdoptPolicy(enabled = true, maxDraftAgeMs = 60 * 60_000L))
        val p = plan()
        h.driver.putDraft(p, at(8))
        val r = h.driver.adopt(p, at(10))
        assertFalse(r.ok)
        assertEquals(ConflictKind.DRAFT_TOO_OLD, r.conflicts.single().kind)
    }

    @Test fun operationsAreIdempotent() {
        val h = Harness(AdoptPolicy(enabled = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.adopt(p, at(9, 1))
        val cmds = listOf(
            ZkCommand.SetMain("写申论", at(9), "交出一段", "计划"),
            ZkCommand.AddAction("写申论", at(9), at(10), "交出一段", "计划"),
        )
        val one = h.driver.prepareExecution(p, cmds, at(9, 2), grantKind = "PLAN")
        val two = h.driver.prepareExecution(p, cmds, at(9, 3), grantKind = "PLAN")
        assertEquals(one.operations.map { it.operationId }, two.operations.map { it.operationId })
        assertEquals(2, h.driver.snapshot().operations.size)
        assertEquals("PLAN", h.driver.snapshot().operations.first().grantKind)

        val op = one.operations.first().operationId
        h.driver.markSending(op, at(9, 4))
        val receipt = PlanReceipt("r-9", ok = true, auto = true, message = "授权内 60 秒后执行")
        h.driver.recordReceipt(op, receipt, at(9, 5))
        assertEquals(OperationState.ACTIVE, h.driver.operation(op)!!.state)
        h.driver.recordReceipt(op, receipt, at(9, 6))
        assertEquals("重复回执不加次数/不改状态", 1, h.driver.operation(op)!!.attempts)
        h.driver.recordReceipt(op, PlanReceipt("r-10", ok = false, message = "又拒一次"), at(9, 7))
        assertEquals("终态不被后来的回执覆盖", OperationState.ACTIVE, h.driver.operation(op)!!.state)
        assertEquals("r-9", h.driver.operation(op)!!.receiptId)
    }

    @Test fun unknownIsNotResent() {
        val h = Harness(AdoptPolicy(enabled = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.adopt(p, at(9, 1))
        val cmds = listOf(ZkCommand.StartFocus(25, "写申论", "计划"))
        val prepared = h.driver.prepareExecution(p, cmds, at(9, 2))
        val op = prepared.operations.single().operationId
        h.driver.markSending(op, at(9, 3))
        h.driver.markUnknown(op, at(9, 4))
        assertEquals(OperationState.UNKNOWN, h.driver.operation(op)!!.state)
        val again = h.driver.prepareExecution(p, cmds, at(9, 5))
        assertEquals(1, again.operations.size)
        assertEquals("没有新增操作", 1, h.driver.snapshot().operations.size)
        assertEquals(OperationState.UNKNOWN, h.driver.operation(op)!!.state)
        assertEquals(1, h.driver.sentCount())
        assertTrue(h.driver.unsentCount() == 0)
    }

    @Test fun cancelExplainsUnsentAndSent() {
        val h = Harness(AdoptPolicy(enabled = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.adopt(p, at(9, 1))
        val cmds = listOf(
            ZkCommand.SetMain("写申论", at(9), "交出一段", "计划"),
            ZkCommand.Note("放这儿", "计划"),
            ZkCommand.Note("再放一条", "计划"),
        )
        val prepared = h.driver.prepareExecution(p, cmds, at(9, 2))
        val sent = prepared.operations.first().operationId
        h.driver.markSending(sent, at(9, 3))
        h.driver.recordReceipt(sent, PlanReceipt("r-1", ok = true, auto = true, message = "执行了"), at(9, 4))
        val out = h.driver.cancel(at(9, 5))
        assertTrue(out.ok)
        assertTrue(out.message.contains("2"))
        assertTrue(out.message.contains("1"))
        assertEquals(OperationState.ACTIVE, h.driver.operation(sent)!!.state)
        assertEquals(0, h.driver.unsentCount())
        assertEquals(AdoptionState.CANCELLED, h.driver.adoption(day)!!.state)
        assertTrue("取消后还能重新采纳", h.driver.adopt(p, at(9, 6)).ok)
    }

    @Test fun prepareRequiresLocalAdoptionAndCurrentPlan() {
        val h = Harness(AdoptPolicy(enabled = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        val early = h.driver.prepareExecution(p, listOf(ZkCommand.Note("x", "计划")), at(9, 1))
        assertFalse(early.ok)
        assertTrue(h.driver.adopt(p, at(9, 2)).ok)
        val ok = h.driver.prepareExecution(p, listOf(ZkCommand.Note("x", "计划")), at(9, 3))
        assertTrue(ok.ok)
        val ops = h.driver.snapshot().operations.size
        val duplicate = h.driver.prepareExecution(p, listOf(ZkCommand.Note("x", "计划")), at(9, 4))
        assertTrue(duplicate.ok)
        assertEquals(ops, h.driver.snapshot().operations.size)
    }

    @Test fun stateSerializesWithCommandsAndReceipts() {
        val h = Harness(AdoptPolicy(enabled = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.adopt(p, at(9, 1))
        val prepared = h.driver.prepareExecution(p, listOf(ZkCommand.AddAction("写申论", at(9), at(10), "", "计划")), at(9, 2))
        val op = prepared.operations.single().operationId
        h.driver.markSending(op, at(9, 3))
        h.driver.recordReceipt(op, PlanReceipt("r-1", ok = true, auto = false, message = "等确认"), at(9, 4))
        val json = Json.encodeToString(h.driver.snapshot())
        val restored = Json.decodeFromString<PlanDriverState>(json)
        assertEquals(h.driver.snapshot(), restored)
        val driver = PlanDriver(restored)
        val command = driver.operation(op)!!.command
        assertTrue(command is ZkCommand.AddAction)
        assertEquals("写申论", (command as ZkCommand.AddAction).title)
        assertEquals(OperationState.ZK_PENDING, driver.operation(op)!!.state)
    }

    @Test fun duplicateDraftDoesNotBumpRevision() {
        val h = Harness(AdoptPolicy(enabled = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.putDraft(p, at(9, 5))
        assertEquals(1, h.driver.snapshot().drafts.single().revision)
        assertEquals(1, h.driver.snapshot().drafts.size)
    }

    @Test fun noDraftMeansNoAdoption() {
        val h = Harness(AdoptPolicy(enabled = true))
        val r = h.driver.adopt(plan(), at(9))
        assertFalse(r.ok)
        assertEquals(ConflictKind.NO_DRAFT, r.conflicts.single().kind)
        assertNull(h.driver.snapshot().adoption)
    }
}
