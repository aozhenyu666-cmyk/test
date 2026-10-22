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

    @Test fun reminderCanBeTurnedOffAndNeverAdopts() {
        val h = Harness(AdoptPolicy(remind = false))
        val p = plan()
        h.driver.putDraft(p, at(9))
        repeat(30) { h.driver.tick(at(9, 1 + it)) }
        val a = h.driver.adoption(day)!!
        assertEquals("看多久都不会自己采用", AdoptionState.DRAFT, a.state)
        assertEquals("关掉提醒就不提醒", 0L, a.remindedAt)
        assertFalse(p.confirmed)
        assertEquals(0, h.driver.snapshot().operations.size)
    }

    @Test fun reminderAtTwentyMinutesNeverAdopts() {
        val h = Harness(AdoptPolicy(remind = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.tick(at(9))
        repeat(19) { h.driver.tick(at(9, 1 + it)) }
        assertEquals("19 分钟还不够", AdoptionState.DRAFT, h.driver.adoption(day)!!.state)
        assertEquals(0L, h.driver.adoption(day)!!.remindedAt)
        h.driver.tick(at(9, 20))
        val a = h.driver.adoption(day)!!
        assertEquals("到点只提醒，不自动采用", AdoptionState.DRAFT, a.state)
        assertEquals("到点发了提醒", at(9, 20), a.remindedAt)
        assertTrue(a.message.contains("一键采用"))
        assertFalse("不改旧 confirmed", p.confirmed)
        assertEquals("没有自动准备/发送任何指令", 0, h.driver.snapshot().operations.size)
        assertEquals(0, h.driver.sentCount())
        h.driver.tick(at(9, 21))
        assertEquals("提醒只发一次", at(9, 20), h.driver.adoption(day)!!.remindedAt)
    }

    @Test fun confirmedPlanIsNotReminded() {
        val h = Harness(AdoptPolicy(remind = true))
        h.driver.putDraft(plan(confirmed = true), at(9))
        h.driver.tick(at(9, 30))
        val a = h.driver.adoption(day)!!
        assertEquals(AdoptionState.DRAFT, a.state)
        assertEquals(0L, a.remindedAt)
    }

    @Test fun restartDoesNotAdoptOrRemindYesterdayDraft() {
        val h = Harness(AdoptPolicy(remind = true))
        h.driver.putDraft(plan(), at(23))
        h.driver.tick(at(23))
        repeat(3) { h.driver.tick(at(23, 1 + it)) }
        val json = Json.encodeToString(h.driver.snapshot())
        val restored = Json.decodeFromString<PlanDriverState>(json)
        // 第二天中午才重启：昨天的草稿不自动采用，也没有今天的草稿。
        val again = Harness(AdoptPolicy(remind = true), restored)
        again.driver.tick(at(12, d = 23))
        assertNull(again.driver.adoption("2026-10-23"))
        assertEquals(AdoptionState.EXPIRED, again.driver.snapshot().adoption!!.state)
        assertTrue(again.driver.snapshot().operations.isEmpty())
    }

    @Test fun rewrittenPlanResetsCountdownAndCancelsUnsentCommands() {
        val h = Harness(AdoptPolicy(remind = true))
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
        assertEquals("已经发出的保持回执", OperationState.ZK_PENDING, h.driver.operation(first.operationId)!!.state)
        val canceled = prepared.operations[1]
        assertEquals("没发的取消", OperationState.CANCELLED, h.driver.operation(canceled.operationId)!!.state)
        assertTrue(state.adoption!!.message.contains("取消"))
        assertEquals(1, h.driver.sentCount())
    }

    @Test fun changingFocusRequiresRecheck() {
        val h = Harness(AdoptPolicy(remind = true))
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
        val h = Harness(AdoptPolicy(remind = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        val edited = p.copy(intent = "临时改的")
        val r = h.driver.adopt(edited, at(9, 1))
        assertFalse(r.ok)
        assertEquals(ConflictKind.PLAN_CHANGED, r.conflicts.single().kind)
        assertTrue(h.driver.adopt(p, at(9, 2)).ok)
    }

    @Test fun tooOldDraftIsNotAdopted() {
        val h = Harness(AdoptPolicy(remind = true, maxDraftAgeMs = 60 * 60_000L))
        val p = plan()
        h.driver.putDraft(p, at(8))
        val r = h.driver.adopt(p, at(10))
        assertFalse(r.ok)
        assertEquals(ConflictKind.DRAFT_TOO_OLD, r.conflicts.single().kind)
    }

    @Test fun operationsAreIdempotent() {
        val h = Harness(AdoptPolicy(remind = true))
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
        val h = Harness(AdoptPolicy(remind = true))
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
        val h = Harness(AdoptPolicy(remind = true))
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

    @Test fun cancelledAdoptionStaysCancelledAfterPlanRewrite() {
        val h = Harness(AdoptPolicy(remind = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        assertTrue(h.driver.cancel(at(9, 1)).ok)
        assertEquals(AdoptionState.CANCELLED, h.driver.adoption(day)!!.state)

        val changed = plan(intent = "改成下午推", startMin = 14 * 60)
        h.driver.putDraft(changed, at(9, 2))
        val a = h.driver.adoption(day)!!
        assertEquals("不采纳要粘住，不能改稿就变回草稿", AdoptionState.CANCELLED, a.state)
        assertTrue(a.message.contains("没采纳"))
        assertEquals(2, h.driver.snapshot().drafts.single().revision)
        assertTrue("他再点才算", h.driver.adopt(changed, at(9, 3)).ok)
        assertEquals(AdoptionState.LOCAL_ADOPTED, h.driver.adoption(day)!!.state)
    }

    @Test fun rejectedAdoptionStaysRejectedAfterPlanRewrite() {
        val h = Harness(AdoptPolicy(remind = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        assertTrue(h.driver.adopt(p, at(9, 1)).ok)
        val prepared = h.driver.prepareExecution(p, listOf(ZkCommand.Note("x", "计划")), at(9, 2))
        val op = prepared.operations.single().operationId
        h.driver.markSending(op, at(9, 3))
        h.driver.recordReceipt(op, PlanReceipt("r-1", ok = false, message = "总控拒绝"), at(9, 4))
        assertEquals(AdoptionState.REJECTED, h.driver.adoption(day)!!.state)

        val changed = plan(intent = "改成下午推", startMin = 14 * 60)
        h.driver.putDraft(changed, at(9, 5))
        assertEquals("被拒绝也要粘住", AdoptionState.REJECTED, h.driver.adoption(day)!!.state)
        assertTrue("他再点才算", h.driver.adopt(changed, at(9, 6)).ok)
    }

    @Test fun zongkongStatesMapToLedgerFinals() {
        assertEquals("DONE", PlanReceipts.finalOf("DONE"))
        assertEquals("倒计时中还不算执行", "PENDING", PlanReceipts.finalOf("SCHEDULED"))
        assertEquals("PENDING", PlanReceipts.finalOf("PENDING"))
        assertEquals("你在总控取消了", "DECLINED", PlanReceipts.finalOf("VETOED"))
        assertEquals("DECLINED", PlanReceipts.finalOf("declined"))
        assertEquals("EXPIRED", PlanReceipts.finalOf("EXPIRED"))
        assertEquals("FAILED", PlanReceipts.finalOf("FAILED"))
        // 发过、总控说过期 → 不写“没发”
        val h = Harness(AdoptPolicy(remind = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.adopt(p, at(9, 1))
        val op = h.driver.prepareExecution(p, listOf(ZkCommand.Note("x", "计划")), at(9, 2)).operations.single().operationId
        h.driver.markSending(op, at(9, 3))
        h.driver.recordReceipt(op, PlanReceipt("r-1", ok = true, auto = true, message = "倒计时"), at(9, 4))
        h.driver.recordFinal(op, "r-1", PlanReceipts.finalOf("VETOED"), "你取消了", at(9, 5))
        assertEquals(OperationState.REJECTED, h.driver.operation(op)!!.state)
    }

    @Test fun rewriteAfterReminderRestartsTheCountdown() {
        val h = Harness(AdoptPolicy(remind = true))
        h.driver.putDraft(plan(), at(9))
        h.driver.tick(at(9, 20))
        assertEquals(at(9, 20), h.driver.adoption(day)!!.remindedAt)
        // 9:30 改了计划：新版本要重新等 20 分钟才提醒，不能一改完就显示“到点了”
        h.driver.putDraft(plan(main = "写报告"), at(9, 30))
        h.driver.tick(at(9, 31))
        assertEquals("改版后旧提醒不算数", 0L, h.driver.adoption(day)!!.remindedAt)
        h.driver.tick(at(9, 49))
        assertEquals(0L, h.driver.adoption(day)!!.remindedAt)
        h.driver.tick(at(9, 50))
        val a = h.driver.adoption(day)!!
        assertEquals("新版本到点再提醒", at(9, 50), a.remindedAt)
        assertEquals("还是不自动采用", AdoptionState.DRAFT, a.state)
    }

    @Test fun sentOperationExpiredByZongkongIsNotLabelledUnsent() {
        val h = Harness(AdoptPolicy(remind = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.adopt(p, at(9, 1))
        val op = h.driver.prepareExecution(p, listOf(ZkCommand.Note("x", "计划")), at(9, 2)).operations.single().operationId
        h.driver.markSending(op, at(9, 3))
        h.driver.recordReceipt(op, PlanReceipt("r-1", ok = true, auto = false, message = "总控等确认"), at(9, 4))
        h.driver.recordFinal(op, "r-1", "EXPIRED", "总控里没人确认，过期了", at(10))
        val o = h.driver.operation(op)!!
        assertEquals(OperationState.ZK_EXPIRED, o.state)
        assertFalse("发过的不能写“没发”", o.state.label.contains("没发"))
    }

    @Test fun pendingReceiptIsNeverExpiredLocallyAndCanBeQueried() {
        val h = Harness(AdoptPolicy(remind = true, operationTtlMs = 60_000L))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.adopt(p, at(9, 1))
        val prepared = h.driver.prepareExecution(p, listOf(ZkCommand.Note("x", "计划")), at(9, 2))
        val op = prepared.operations.single().operationId
        h.driver.markSending(op, at(9, 3))
        h.driver.recordReceipt(op, PlanReceipt("r-1", ok = true, auto = false, message = "总控等确认"), at(9, 4))
        assertEquals(OperationState.ZK_PENDING, h.driver.operation(op)!!.state)

        h.driver.tick(at(9, 30))
        assertEquals("ZK_PENDING 不在本地过期", OperationState.ZK_PENDING, h.driver.operation(op)!!.state)
        assertEquals("到点去查回执", listOf(op), h.driver.receiptsToQuery(at(9, 30)).map { it.operationId })
        assertEquals(0, h.driver.snapshot().operations.count { it.state == OperationState.EXPIRED })
    }

    @Test fun interruptedSendingBecomesUnknownOnExpire() {
        val h = Harness(AdoptPolicy(remind = true, operationTtlMs = 60_000L))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.adopt(p, at(9, 1))
        val prepared = h.driver.prepareExecution(p, listOf(ZkCommand.Note("x", "计划")), at(9, 2))
        val op = prepared.operations.single().operationId
        h.driver.markSending(op, at(9, 3))
        h.driver.expire(at(9, 30))
        assertEquals("SENDING 中断是结果未知，不是没发", OperationState.UNKNOWN, h.driver.operation(op)!!.state)
        assertEquals(0, h.driver.snapshot().operations.count { it.state == OperationState.EXPIRED })
    }

    @Test fun planRewriteWhileSendingBecomesUnknownNotCancelled() {
        val h = Harness(AdoptPolicy(remind = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.adopt(p, at(9, 1))
        val prepared = h.driver.prepareExecution(p, listOf(ZkCommand.Note("x", "计划")), at(9, 2))
        val op = prepared.operations.single().operationId
        h.driver.markSending(op, at(9, 3))
        h.driver.putDraft(plan(intent = "改成下午推"), at(9, 4))
        assertEquals("正在发的不能记成已取消（没发）", OperationState.UNKNOWN, h.driver.operation(op)!!.state)
    }

    @Test fun cancelNeverMarksSendingOrSentAsNotSent() {
        val h = Harness(AdoptPolicy(remind = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.adopt(p, at(9, 1))
        val prepared = h.driver.prepareExecution(
            p,
            listOf(ZkCommand.Note("a", "计划"), ZkCommand.Note("b", "计划"), ZkCommand.Note("c", "计划")),
            at(9, 2),
        )
        val active = prepared.operations[0].operationId
        val sending = prepared.operations[1].operationId
        val unsent = prepared.operations[2].operationId
        h.driver.markSending(active, at(9, 3))
        h.driver.recordReceipt(active, PlanReceipt("r-1", ok = true, auto = true, message = "执行了"), at(9, 4))
        h.driver.markSending(sending, at(9, 5))

        val out = h.driver.cancel(at(9, 6))
        assertTrue(out.ok)
        assertEquals(OperationState.ACTIVE, h.driver.operation(active)!!.state)
        assertEquals("正在发的记 UNKNOWN", OperationState.UNKNOWN, h.driver.operation(sending)!!.state)
        assertEquals("真正没发的才取消", OperationState.CANCELLED, h.driver.operation(unsent)!!.state)
        assertTrue("说清楚正在发的不能撤销", out.message.contains("结果未知"))
    }

    @Test fun prepareRequiresLocalAdoptionAndCurrentPlan() {
        val h = Harness(AdoptPolicy(remind = true))
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
        val h = Harness(AdoptPolicy(remind = true))
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
        val h = Harness(AdoptPolicy(remind = true))
        val p = plan()
        h.driver.putDraft(p, at(9))
        h.driver.putDraft(p, at(9, 5))
        assertEquals(1, h.driver.snapshot().drafts.single().revision)
        assertEquals(1, h.driver.snapshot().drafts.size)
    }

    @Test fun noDraftMeansNoAdoption() {
        val h = Harness(AdoptPolicy(remind = true))
        val r = h.driver.adopt(plan(), at(9))
        assertFalse(r.ok)
        assertEquals(ConflictKind.NO_DRAFT, r.conflicts.single().kind)
        assertNull(h.driver.snapshot().adoption)
    }
}
