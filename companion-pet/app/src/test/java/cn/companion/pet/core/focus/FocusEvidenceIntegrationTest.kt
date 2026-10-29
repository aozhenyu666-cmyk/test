package cn.companion.pet.core.focus

import cn.companion.pet.core.training.ActionReceiptState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusEvidenceIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()
    private var t = 1_000_000L
    private val target = "example.target"
    private class Phone : FocusDevicePort {
        var active: String? = "example.home"
        var accepts = true
        var arrives = true
        var polling = false
        var reads = 0
        val launches = mutableListOf<String>()
        override fun launch(pkg: String): Boolean { launches += pkg; if (accepts && arrives) active = pkg; return accepts }
        override fun activePackage(): String? { reads++; return active }
        override fun observeForeground(): String? = if (polling) activePackage() else null
    }
    private class Ui : FocusUiPort {
        var attached = false
        var drawn = false
        var full = true
        var hideWorks = true
        var canShow = true
        var returned = 0
        override fun showBlock(targetLabel: String, reason: String): OverlayResult {
            attached = canShow
            return OverlayResult(canShow, if (canShow) "" else "权限不足")
        }
        override fun hideBlock() { if (hideWorks) attached = false }
        override fun observeBlock() = BlockObservation(attached, drawn, full)
        override fun remind(targetLabel: String, text: String) = Unit
        override fun returnedToTarget(targetLabel: String) { returned++ }
    }
    private inner class Scheduler : FocusScheduler {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        override fun post(delayMillis: Long, task: () -> Unit) { tasks += (t + delayMillis) to task }
        fun runDue() {
            val due = tasks.filter { it.first <= t }.toList()
            tasks.removeAll(due.toSet())
            due.forEach { it.second() }
        }
    }
    private val phone = Phone()
    private val ui = Ui()
    private val scheduler = Scheduler()
    private val store by lazy { FocusStore(tmp.root) { t } }
    private val controller by lazy { FocusController(store, phone, ui, scheduler) { t } }
    private fun contract(id: String = "same") = FocusContract(id, target, "目标", t, t + 10 * 60_000,
        FocusMode.TEXT, FocusEnforcement.STRICT, graceMillis = 1000, retryBackoffMillis = 8000)
    private fun advance(ms: Long, interactive: Boolean = true) { t += ms; controller.tick(interactive); scheduler.runDue() }
    private fun start() { assertEquals(StartResult.Started, controller.start(contract(), phone.active)); controller.onForeground(target); advance(200) }
    private fun leave() { phone.active = "example.feed"; controller.onForeground(phone.active); advance(1000) }
    private fun last(capability: String) = controller.actionHistory().last { it.capability == capability }

    @Test fun acceptedLaunchNeedsTwoIndependentObservationsBeforeConfirmed() {
        start(); leave()
        assertEquals(ActionReceiptState.API_REPORTED_SUCCESS, last("focus_return").state)
        advance(1200)
        assertEquals(FocusState.IN_TARGET, controller.snapshot!!.state)
        assertEquals(ActionReceiptState.API_REPORTED_SUCCESS, last("focus_return").state)
        advance(200)
        assertEquals(ActionReceiptState.OBSERVED_CONFIRMED, last("focus_return").state)
        assertTrue(last("focus_return").observed)
        assertFalse(ui.attached)
    }

    @Test fun acceptedLaunchWithoutTargetForegroundIsNeverConfirmed() {
        start(); phone.arrives = false; leave(); advance(1200); advance(200)
        assertEquals(FocusState.BLOCKING, controller.snapshot!!.state)
        assertEquals(ActionReceiptState.FAILED, last("focus_return").state)
        assertEquals(0, ui.returned)
    }

    @Test fun missingForegroundIsUnknownInsteadOfAnInventedFailureOrSuccess() {
        start(); leave(); phone.active = null; advance(1200)
        assertEquals(ActionReceiptState.UNKNOWN, last("focus_return").state)
    }

    @Test fun targetForegroundWithStuckBlockerDoesNotPassReturnAcceptance() {
        start(); ui.hideWorks = false; leave(); advance(1200); advance(200)
        assertEquals(ActionReceiptState.FAILED, last("focus_return").state)
        assertTrue(controller.status().overlayShown)
        assertTrue(last("focus_return").detail.contains("仍然附着"))
    }

    @Test fun addViewAcceptanceAloneDoesNotProveTheBlockerWasDrawn() {
        start(); phone.arrives = false; leave(); advance(200)
        assertEquals(ActionReceiptState.FAILED, last("focus_block_draw").state)
        assertFalse(last("focus_block_draw").observed)
    }

    @Test fun drawnBlockIsNarrowEvidenceAndDoesNotClaimTouchInterception() {
        start(); phone.arrives = false; ui.drawn = true; leave(); advance(200)
        assertEquals(ActionReceiptState.OBSERVED_CONFIRMED, last("focus_block_draw").state)
        assertTrue(last("focus_block_draw").detail.contains("实际触摸拦截仍需真机"))
    }

    @Test fun staleCallbacksCannotActOnANewContractEvenIfItsPublicIdIsReused() {
        start(); leave()
        val oldTasks = scheduler.tasks.map { it.second }; scheduler.tasks.clear()
        controller.abort()
        assertEquals(StartResult.Started, controller.start(contract(), phone.active))
        controller.onForeground(target)
        phone.arrives = false
        leave()
        val before = controller.snapshot
        val reads = phone.reads
        oldTasks.forEach { it() }
        assertEquals(before, controller.snapshot)
        assertEquals(reads, phone.reads)
    }

    @Test fun periodicFreshSampleDetectsALostWindowEvent() {
        start(); phone.polling = true; phone.arrives = false; phone.active = "example.feed"
        advance(2000)
        assertEquals(FocusState.AWAY_GRACE, controller.snapshot!!.state)
        advance(1000)
        assertEquals(FocusState.RETURNING, controller.snapshot!!.state)
        assertTrue(ui.attached)
    }

    @Test fun interruptionHidesBlockAndInvalidatesPendingReturnVerification() {
        start(); leave()
        assertTrue(ui.attached)
        val expiry = controller.snapshot!!.contract.expiresAt
        advance(100, interactive = false)
        assertFalse(ui.attached)
        assertEquals(FocusState.BLOCKING, controller.snapshot!!.state)
        advance(2000, interactive = false)
        assertEquals(0, ui.returned)
        assertEquals(2, phone.launches.size)
        assertEquals(expiry, controller.snapshot!!.contract.expiresAt)
    }

    @Test fun unchangedTicksDoNotRewriteTheContractEverySecond() {
        var writes = 0
        // 注意：必须模拟生产代码的原子替换（REPLACE_EXISTING）。
        // File.renameTo 在目标已存在时返回 false，用它统计"写盘次数"会让
        // store.save() 误报失败 → lastPersisted 不更新 → 每次 tick 都重写。
        // 测出来的 122 次是测试自己造成的假象，不是产品缺陷。
        val counting = FocusStore(tmp.newFolder(), replace = { from, to ->
            writes++
            runCatching {
                java.nio.file.Files.move(
                    from.toPath(), to.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                true
            }.getOrDefault(false)
        })
        val c = FocusController(counting, phone, ui, scheduler) { t }
        c.start(contract(), phone.active); c.onForeground(target)
        val before = writes
        repeat(120) { t += 1000; c.tick() }
        assertEquals(before, writes)
    }

    @Test fun restoredControllerDoesNotReuseOldObservedReceipts() {
        start(); assertEquals(ActionReceiptState.OBSERVED_CONFIRMED, controller.lastAction()!!.state)
        val restored = FocusController(store, phone, ui, scheduler) { t }
        restored.restore()
        assertNull(restored.lastAction())
    }
}
