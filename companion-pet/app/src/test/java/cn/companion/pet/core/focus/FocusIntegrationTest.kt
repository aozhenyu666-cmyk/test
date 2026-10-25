package cn.companion.pet.core.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 审计 F-01—F-08 的回归用例（移植 deepseek/c3-focus-guard 时逐条复现并修复）。
 * 假手机 + 假时钟 + 手动推进的调度器。
 */
class FocusIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val target = "com.google.android.apps.bard"
    private var t = 1_000_000L

    private class Phone(var active: String? = "com.launcher") : FocusDevicePort {
        var accept = true
        /** 启动请求被接受后，目标是否真的到前台。 */
        var arrives = true
        val launches = mutableListOf<String>()
        override fun launch(pkg: String): Boolean {
            launches += pkg
            if (accept && arrives) active = pkg
            return accept
        }
        override fun activePackage(): String? = active
    }

    private class Ui : FocusUiPort {
        var canShow = true
        var blockShown = false
        val blocks = mutableListOf<String>()
        val reminds = mutableListOf<String>()
        val ends = mutableListOf<FocusEndReason>()
        override fun showBlock(targetLabel: String, reason: String): OverlayResult {
            blocks += reason
            if (!canShow) return OverlayResult(false, "没有悬浮窗/无障碍权限")
            blockShown = true
            return OverlayResult(true)
        }
        override fun hideBlock() { blockShown = false }
        override fun remind(targetLabel: String, text: String) { reminds += text }
        override fun ended(targetLabel: String, reason: FocusEndReason) { ends += reason }
    }

    private class Sched : FocusScheduler {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        override fun post(delayMillis: Long, task: () -> Unit) { tasks += delayMillis to task }
        fun runAll() { val copy = tasks.toList(); tasks.clear(); copy.forEach { it.second() } }
    }

    private fun contract(enf: FocusEnforcement = FocusEnforcement.STRICT, mode: FocusMode = FocusMode.TEXT, id: String = "s1") = FocusContract(
        sessionId = id, targetPackage = target, targetLabel = "Gemini", startedAt = t, expiresAt = t + 25 * 60_000,
        mode = mode, enforcement = enf, allowedPackages = setOf("com.notes"), graceMillis = 10_000, allowedGraceMillis = 90_000,
        maxReturnAttempts = 3, retryBackoffMillis = 8_000, armConfirmMillis = 6_000,
    )

    private data class Rig(val phone: Phone, val ui: Ui, val sched: Sched, val store: FocusStore, val c: FocusController)

    private fun rig(store: FocusStore = FocusStore(tmp.root) { t }): Rig {
        val phone = Phone(); val ui = Ui(); val sched = Sched()
        return Rig(phone, ui, sched, store, FocusController(store, phone, ui, sched) { t })
    }

    private fun Rig.tickFor(seconds: Int) = repeat(seconds) { t += 1_000; c.tick() }

    // F-01：刚建立、还没确认目标在前台就切走，仍然开始宽限并最终干预。
    @Test
    fun f01_leavingBeforeTargetConfirmedStillGuards() {
        val r = rig()
        r.phone.arrives = false // 系统接受了，但还没切到目标
        assertEquals(StartResult.Started, r.c.start(contract(), "com.launcher"))
        assertEquals(FocusState.ARMED, r.c.snapshot!!.state)
        r.c.onForeground("com.ss.android.ugc.aweme") // 立刻去刷抖音
        assertEquals(FocusState.AWAY_GRACE, r.c.snapshot!!.state)
        r.tickFor(11)
        assertTrue("STRICT intervenes after grace", r.phone.launches.size >= 2)
        // 没有任何前台事件（例如后台启动被系统拦了）：确认超时后同样按离开处理。
        val r2 = rig(FocusStore(tmp.newFolder()) { t })
        r2.phone.arrives = false
        r2.c.start(contract(id = "s2"), "com.launcher")
        r2.tickFor(7)
        assertEquals(FocusState.AWAY_GRACE, r2.c.snapshot!!.state)
    }

    // F-02：STRICT 到期后自动发起有界返回，并核验前台；成功才收起拦截层。
    @Test
    fun f02_strictAutoReturnsAndVerifies() {
        val r = rig()
        r.c.start(contract(), "com.launcher")
        r.c.onForeground(target)
        r.phone.active = "com.tencent.mm"; r.c.onForeground("com.tencent.mm")
        r.tickFor(11)
        assertTrue(r.ui.blockShown)
        assertEquals(FocusState.RETURNING, r.c.snapshot!!.state)
        assertEquals(target, r.phone.active)
        r.sched.runAll() // 核验
        assertEquals(FocusState.IN_TARGET, r.c.snapshot!!.state)
        assertFalse("block hidden only after verified", r.ui.blockShown)
    }

    // F-02：自动返回次数有上限，失败保留可退出的拦截层并说明原因，不无限重试。
    @Test
    fun f02_retriesAreBoundedWithAccurateMessage() {
        val r = rig()
        r.c.start(contract(), "com.launcher"); r.c.onForeground(target)
        r.phone.arrives = false
        r.phone.active = "com.tencent.mm"; r.c.onForeground("com.tencent.mm")
        repeat(60) { t += 1_000; r.c.tick(); r.sched.runAll() }
        val autoLaunches = r.phone.launches.size - 1 // 减去 start
        assertEquals(3, autoLaunches)
        assertTrue(r.ui.blockShown)
        assertTrue(r.c.status().lastResult.contains("不再自动重试"))
        assertTrue(r.c.status().lastResult.contains("没有回到前台"))
    }

    // F-03：用户自己回到目标，拦截层收起，旧的重试回调作废。
    @Test
    fun f03_manualReturnHidesBlockAndCancelsRetries() {
        val r = rig()
        r.c.start(contract(), "com.launcher"); r.c.onForeground(target)
        r.phone.arrives = false
        r.phone.active = "com.tencent.mm"; r.c.onForeground("com.tencent.mm")
        r.tickFor(11)
        assertTrue(r.ui.blockShown)
        val launchesBefore = r.phone.launches.size
        r.phone.active = target; r.c.onForeground(target) // 自己点回去了
        assertFalse(r.ui.blockShown)
        assertEquals(FocusState.IN_TARGET, r.c.snapshot!!.state)
        r.phone.active = "com.tencent.mm" // 旧核验回调来了，此时已在别处
        r.sched.runAll()
        assertEquals("stale verify ignored", FocusState.IN_TARGET, r.c.snapshot!!.state)
        r.tickFor(5)
        assertEquals(launchesBefore, r.phone.launches.size)
    }

    // F-04：存不下就不启动；损坏文件备份不复活；结束后重启不复活。
    @Test
    fun f04_persistenceFailureAndRestore() {
        val failing = FocusStore(tmp.root, replace = { _, _ -> false }) { t }
        val r = rig(failing)
        val result = r.c.start(contract(), "com.launcher")
        assertTrue(result is StartResult.Failed)
        assertTrue((result as StartResult.Failed).reason.contains("保存失败"))
        assertTrue("target must not be opened", r.phone.launches.isEmpty())
        assertFalse(r.c.active)

        val dir = tmp.newFolder()
        val ok = rig(FocusStore(dir) { t })
        ok.c.start(contract(id = "s9"), "com.launcher")
        // 杀进程重启：恢复守护
        val restored = rig(FocusStore(dir) { t })
        assertEquals("s9", restored.c.restore()!!.contract.sessionId)
        restored.c.abort()
        assertNull("aborted session never revives", rig(FocusStore(dir) { t }).c.restore())

        File(dir, "focus/session.json").writeText("{broken")
        val store = FocusStore(dir) { t }
        assertNull(rig(store).c.restore())
        assertTrue(store.lastError!!.contains("损坏"))
        assertEquals(1, File(dir, "focus").listFiles { f -> f.name.startsWith("session.corrupt-") }!!.size)

        val expiredDir = tmp.newFolder()
        rig(FocusStore(expiredDir) { t }).c.start(contract(id = "s10"), "com.launcher")
        t += 26 * 60_000
        assertNull("expired never revives", rig(FocusStore(expiredDir) { t }).c.restore())
    }

    // F-05：拦截层显示失败时如实记录，不宣称已拦截；仍然尝试返回。
    @Test
    fun f05_overlayFailureIsReported() {
        val r = rig()
        r.ui.canShow = false
        r.c.start(contract(), "com.launcher"); r.c.onForeground(target)
        r.phone.active = "com.tencent.mm"; r.c.onForeground("com.tencent.mm")
        r.tickFor(11)
        val st = r.c.status()
        assertFalse(st.overlayShown)
        assertTrue(st.overlayError.contains("权限"))
        assertTrue(r.phone.launches.size >= 2)
    }

    // F-06：启动被拒和“接受了但没到前台”分开；旧会话的回调不影响新会话。
    @Test
    fun f06_launchResultIsRealAndStaleCallbacksIgnored() {
        val r = rig()
        r.c.start(contract(), "com.launcher"); r.c.onForeground(target)
        r.phone.accept = false
        r.phone.active = "com.tencent.mm"; r.c.onForeground("com.tencent.mm")
        r.tickFor(11)
        assertTrue(r.c.status().lastResult.contains("拒绝"))
        assertTrue(r.sched.tasks.isEmpty())

        r.phone.accept = true; r.phone.arrives = false
        r.tickFor(9) // 下一次重试：被接受，等待核验
        assertEquals(1, r.sched.tasks.size)
        r.c.abort()
        assertFalse(r.c.active)
        // 同一个控制器开新会话；旧会话留下的核验回调此时才到。
        r.phone.active = target
        assertEquals(StartResult.Started, r.c.start(contract(id = "new"), "com.launcher"))
        r.phone.active = "com.tencent.mm"
        r.sched.runAll()
        assertEquals("new session untouched by old callback", FocusState.ARMED, r.c.snapshot!!.state)
        assertEquals("new", r.c.snapshot!!.contract.sessionId)
        assertTrue(r.c.status().lastResult.isEmpty())
    }

    // F-07：重复的前台事件不会顺延宽限截止时间。
    @Test
    fun f07_repeatedEventsDoNotExtendGrace() {
        val r = rig()
        r.c.start(contract(), "com.launcher"); r.c.onForeground(target)
        r.phone.active = "com.tencent.mm"; r.c.onForeground("com.tencent.mm")
        val deadline = r.c.snapshot!!.awayDeadline
        repeat(9) { t += 1_000; r.c.onForeground("com.tencent.mm"); r.c.onForeground("com.launcher"); r.c.tick() }
        assertEquals(deadline, r.c.snapshot!!.awayDeadline)
        t += 2_000; r.c.tick()
        assertTrue(r.ui.blockShown)
        // 从允许的 App 换到别的 App：宽限缩回普通长度，而不是重新给 90 秒
        val r2 = rig(FocusStore(tmp.newFolder()) { t })
        r2.c.start(contract(id = "s7"), "com.launcher"); r2.c.onForeground(target)
        r2.c.onForeground("com.notes")
        val long = r2.c.snapshot!!.awayDeadline
        t += 5_000; r2.c.onForeground("com.tencent.mm")
        assertTrue(r2.c.snapshot!!.awayDeadline < long)
        assertEquals(t + 10_000, r2.c.snapshot!!.awayDeadline)
    }

    // F-08：SOFT 只提醒，不全屏、不自动拉回；LIVE 默认不拉回。
    @Test
    fun f08_softOnlyRemindsAndLiveNeverPulls() {
        val r = rig()
        r.c.start(contract(FocusEnforcement.SOFT), "com.launcher"); r.c.onForeground(target)
        r.phone.active = "com.tencent.mm"; r.c.onForeground("com.tencent.mm")
        r.tickFor(60)
        assertTrue(r.ui.blocks.isEmpty())
        assertFalse(r.ui.blockShown)
        assertEquals(1, r.phone.launches.size)
        assertTrue(r.ui.reminds.isNotEmpty())
        assertTrue("reminders are bounded", r.ui.reminds.size <= 3)

        val live = rig(FocusStore(tmp.newFolder()) { t })
        live.c.start(contract(FocusEnforcement.STRICT, FocusMode.LIVE, "l1"), "com.launcher"); live.c.onForeground(target)
        live.phone.active = "com.tencent.mm"; live.c.onForeground("com.tencent.mm")
        live.tickFor(60)
        assertTrue(live.ui.blocks.isEmpty())
        assertEquals(1, live.phone.launches.size)
    }

    // 暂停/紧急退出/到期绝对停止；熄屏期间不拉回。
    @Test
    fun pauseAbortExpiryAndScreenOffStopEverything() {
        val r = rig()
        r.c.start(contract(), "com.launcher"); r.c.onForeground(target)
        r.phone.active = "com.tencent.mm"; r.c.onForeground("com.tencent.mm")
        repeat(30) { t += 1_000; r.c.tick(interactive = false) }
        assertEquals("no pulls while screen off", 1, r.phone.launches.size)
        r.c.pause()
        assertFalse(r.ui.blockShown)
        r.tickFor(60)
        assertEquals(1, r.phone.launches.size)
        r.c.abort()
        assertEquals(listOf(FocusEndReason.USER_ABORTED), r.ui.ends)
        r.tickFor(60)
        assertEquals(1, r.phone.launches.size)

        val e = rig(FocusStore(tmp.newFolder()) { t })
        e.c.start(contract(id = "e1"), "com.launcher"); e.c.onForeground(target)
        t += 26 * 60_000; e.c.tick()
        assertEquals(listOf(FocusEndReason.EXPIRED), e.ui.ends)
        assertFalse(e.c.active)
    }

    // 打不开目标：不留下守护。
    @Test
    fun launchFailureLeavesNoGuard() {
        val r = rig()
        r.phone.accept = false
        val res = r.c.start(contract(), "com.launcher")
        assertTrue(res is StartResult.Failed)
        assertFalse(r.c.active)
        assertNull(rig(r.store).c.restore())
    }
}
