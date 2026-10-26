package cn.companion.pet.core.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 交接说明 C / 六项验收的逻辑层：
 * 离开 → 宽限 → 全屏拦截 → 有界拉回并核验前台 → 确认回到目标（通知训练接回原题）；
 * 练习换来的休息真正暂停守护并到点恢复；通话/紧急界面不算离开。
 */
class StrictTakeoverTest {
    @get:Rule val tmp = TemporaryFolder()

    private val target = "com.google.android.apps.bard"
    private var t = 1_000_000L

    private class Phone(var active: String? = "com.launcher") : FocusDevicePort {
        var accept = true
        var arrives = true
        val launches = mutableListOf<String>()
        override fun launch(pkg: String): Boolean { launches += pkg; if (accept && arrives) active = pkg; return accept }
        override fun activePackage(): String? = active
    }

    private class Ui : FocusUiPort {
        var blockShown = false
        val blocks = mutableListOf<String>()
        var returned = 0
        override fun showBlock(targetLabel: String, reason: String): OverlayResult { blocks += reason; blockShown = true; return OverlayResult(true) }
        override fun hideBlock() { blockShown = false }
        override fun remind(targetLabel: String, text: String) {}
        override fun returnedToTarget(targetLabel: String) { returned++ }
    }

    private class Sched : FocusScheduler {
        val tasks = mutableListOf<() -> Unit>()
        override fun post(delayMillis: Long, task: () -> Unit) { tasks += task }
        fun runAll() { val copy = tasks.toList(); tasks.clear(); copy.forEach { it() } }
    }

    private fun contract(minutes: Int = 5) = FocusContract(
        sessionId = "s1", targetPackage = target, targetLabel = "Gemini", startedAt = t, expiresAt = t + minutes * 60_000L,
        mode = FocusMode.TEXT, enforcement = FocusEnforcement.STRICT, graceMillis = 10_000,
        maxReturnAttempts = 3, retryBackoffMillis = 8_000, armConfirmMillis = 6_000,
    )

    private val phone = Phone()
    private val ui = Ui()
    private val sched = Sched()
    private val store by lazy { FocusStore(tmp.root) { t } }
    private val c by lazy { FocusController(store, phone, ui, sched) { t } }

    private fun tickFor(seconds: Int) = repeat(seconds) { t += 1_000; c.tick() }

    private fun startInTarget() {
        assertEquals(StartResult.Started, c.start(contract(), "com.launcher"))
        c.onForeground(target)
        assertEquals(FocusState.IN_TARGET, c.snapshot!!.state)
        assertEquals(0, ui.returned) // 刚进入目标不算“回来”
    }

    @Test
    fun leaveBlocksFullScreenThenReturnIsVerifiedAndReported() {
        startInTarget()
        phone.active = "com.ss.android.ugc.aweme"
        c.onForeground("com.ss.android.ugc.aweme")
        tickFor(9)
        assertFalse(ui.blockShown) // 宽限内不拦
        tickFor(2)
        assertTrue(ui.blockShown) // 真正显示了全屏拦截层
        assertEquals(listOf(target, target), phone.launches) // 建立时打开一次 + 有界拉回一次
        sched.runAll() // 1.2 秒后核验前台
        assertEquals(FocusState.IN_TARGET, c.snapshot!!.state)
        assertFalse(ui.blockShown)
        assertEquals(1, ui.returned) // 训练据此把原题带回
    }

    @Test
    fun autoReturnStopsAfterThreeAttempts() {
        startInTarget()
        phone.arrives = false
        phone.active = "com.ss.android.ugc.aweme"
        c.onForeground("com.ss.android.ugc.aweme")
        repeat(10) { tickFor(10); sched.runAll() }
        assertEquals(1 + 3, phone.launches.size) // 建立时打开一次 + 自动拉回最多 3 次
        assertEquals(0, ui.returned)
        assertTrue(c.snapshot!!.lastResult.contains("不再自动重试"))
    }

    @Test
    fun manualReturnFromAwayAlsoReported() {
        startInTarget()
        c.onForeground("com.tencent.mm")
        c.onForeground(target)
        assertEquals(1, ui.returned)
    }

    @Test
    fun earnedRestPausesGuardAndResumesOnTime() {
        startInTarget()
        c.pauseUntil(t + 2 * 60_000, "练习换来的休息")
        assertEquals(FocusState.PAUSED, c.snapshot!!.state)
        phone.active = "com.ss.android.ugc.aweme"
        c.onForeground("com.ss.android.ugc.aweme")
        tickFor(110)
        assertFalse(ui.blockShown) // 休息期内不拦
        tickFor(11)
        assertEquals(FocusState.AWAY_GRACE, c.snapshot!!.state) // 到点恢复，按普通宽限重新计
        tickFor(11)
        assertTrue(ui.blockShown)
    }

    @Test
    fun restNeverExtendsContractAndSurvivesRestart() {
        startInTarget()
        val expires = c.snapshot!!.contract.expiresAt
        c.pauseUntil(t + 60 * 60_000, "练习换来的休息")
        assertEquals(expires, c.snapshot!!.pauseUntil) // 截到契约到期
        val again = FocusController(FocusStore(tmp.root) { t }, phone, ui, sched) { t }
        val restored = again.restore()!!
        assertEquals(FocusState.PAUSED, restored.state)
        assertEquals(expires, restored.pauseUntil)
        t = expires
        again.tick()
        assertFalse(again.active) // 到期结束，不复活、不延长
    }

    @Test
    fun repeatedRestPauseIsIdempotent() {
        startInTarget()
        c.pauseUntil(t + 2 * 60_000, "休息")
        val gen = c.snapshot!!.generation
        c.pauseUntil(t + 2 * 60_000, "休息") // 崩溃后补发同一次休息
        assertEquals(gen, c.snapshot!!.generation)
    }

    @Test
    fun emergencyExitAlwaysWorksDuringBlock() {
        startInTarget()
        c.onForeground("com.ss.android.ugc.aweme")
        tickFor(11)
        assertTrue(ui.blockShown)
        c.abort()
        assertFalse(c.active)
        assertFalse(ui.blockShown)
    }

    @Test
    fun callsAndEmergencyScreensAreNotLeaving() {
        val gate = ForegroundGate("cn.companion.pet")
        for (pkg in listOf("com.android.incallui", "com.android.dialer", "com.google.android.dialer",
                "com.android.phone", "com.android.server.telecom", "com.android.emergency",
                "com.android.permissioncontroller", "com.android.systemui")) {
            assertTrue(pkg, gate.isTransient(pkg))
        }
        assertFalse(gate.isTransient("com.ss.android.ugc.aweme"))
    }
}
