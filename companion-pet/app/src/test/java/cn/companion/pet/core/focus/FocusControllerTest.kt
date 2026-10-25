package cn.companion.pet.core.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeDevice(var launchAccepted: Boolean = true, var active: String? = "com.gemini") : FocusDevicePort {
        var launches = 0
        override fun launch(pkg: String): Boolean { launches++; return launchAccepted }
        override fun activePackage(): String? = active
    }

    private class FakeOverlay : FocusOverlayPort {
        var shown = false
        var reason = ""
        override fun show(targetLabel: String, reason: String) { shown = true; this.reason = reason }
        override fun hide() { shown = false }
    }

    private class QueueScheduler : FocusScheduler {
        private val tasks = ArrayDeque<Pair<Long, () -> Unit>>()
        override fun post(delayMillis: Long, task: () -> Unit) { tasks.addLast(delayMillis to task) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().second() }
    }

    private fun contract(enforcement: FocusEnforcement = FocusEnforcement.STRICT) = FocusContract(
        sessionId = "s", targetPackage = "com.gemini", targetLabel = "Gemini",
        startedAt = 0, expiresAt = 10_000_000, mode = FocusMode.TEXT, enforcement = enforcement,
        graceMillis = 1_000, allowedGraceMillis = 2_000, maxReturnAttempts = 2, retryBackoffMillis = 10,
    )

    @Test
    fun startPersistsContractBeforeLaunch() {
        val device = FakeDevice()
        val c = FocusController(tmp.root, device, FakeOverlay(), QueueScheduler()) { 1_000 }
        assertEquals(StartResult.Started, c.start(contract(), "com.gemini"))
        assertEquals(FocusState.ARMED, c.state)
        assertEquals(1, device.launches)
    }

    @Test
    fun launchFailureDoesNotLeavePhantomSession() {
        val device = FakeDevice(launchAccepted = false)
        val c = FocusController(tmp.root, device, FakeOverlay(), QueueScheduler()) { 1_000 }
        val r = c.start(contract(), "com.gemini")
        assertTrue(r is StartResult.Failed)
        assertNull(c.state)
    }

    @Test
    fun launchedButNotForegroundIsNotSuccess() {
        val device = FakeDevice(active = "com.other")
        val overlay = FakeOverlay()
        val sched = QueueScheduler()
        val c = FocusController(tmp.root, device, overlay, sched) { 1_000 }
        c.start(contract(), "com.gemini")
        c.onConfirmedLeave("com.other")
        c.onGraceElapsed()
        c.returnNow()
        sched.runAll()
        assertTrue(overlay.shown)
        assertEquals(FocusState.BLOCKING, c.state)
    }

    @Test
    fun returningToTargetHidesBlock() {
        val device = FakeDevice(active = "com.gemini")
        val overlay = FakeOverlay()
        val sched = QueueScheduler()
        var time = 1_000L
        val c = FocusController(tmp.root, device, overlay, sched) { time }
        c.start(contract(), "com.gemini")
        c.onConfirmedLeave("com.other")
        c.onGraceElapsed()
        assertTrue(overlay.shown)
        c.returnNow()
        sched.runAll()
        assertFalse(overlay.shown)
        assertEquals(FocusState.IN_TARGET, c.state)
    }

    @Test
    fun abortStopsEverything() {
        val device = FakeDevice(active = "com.other")
        val overlay = FakeOverlay()
        val sched = QueueScheduler()
        val c = FocusController(tmp.root, device, overlay, sched) { 1_000 }
        c.start(contract(), "com.gemini")
        c.onConfirmedLeave("com.other")
        c.onGraceElapsed()
        c.abort()
        assertNull(c.state)
        assertFalse(overlay.shown)
    }

    @Test
    fun liveDoesNotPullBack() {
        val device = FakeDevice(active = "com.other")
        val overlay = FakeOverlay()
        val c = FocusController(tmp.root, device, overlay, QueueScheduler()) { 1_000 }
        val live = contract().copy(mode = FocusMode.LIVE)
        c.start(live, "com.gemini")
        c.onConfirmedLeave("com.other")
        c.onGraceElapsed()
        assertFalse(overlay.shown)
    }

    @Test
    fun expiredSessionIsNotRestored() {
        val device = FakeDevice()
        var time = 1_000L
        val c = FocusController(tmp.root, device, FakeOverlay(), QueueScheduler()) { time }
        val short = contract().copy(expiresAt = 5_000)
        c.start(short, "com.gemini")
        time = 6_000
        val restored = FocusController(tmp.root, device, FakeOverlay(), QueueScheduler()) { time }
        assertNull(restored.restore())
    }
}
