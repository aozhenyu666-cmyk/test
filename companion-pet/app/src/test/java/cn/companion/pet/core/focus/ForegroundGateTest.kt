package cn.companion.pet.core.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundGateTest {
    private val gate = ForegroundGate(ownPackage = "cn.companion.pet", debounceMillis = 300)

    @Test
    fun systemWindowsAreIgnored() {
        assertTrue(gate.plan("com.android.systemui", 1_000) is GatePlan.Ignore)
        assertTrue(gate.plan("com.sohu.inputmethod.sogou", 1_000) is GatePlan.Ignore)
        assertTrue(gate.plan("cn.companion.pet", 1_000) is GatePlan.Ignore)
        assertTrue(gate.plan(null, 1_000) is GatePlan.Ignore)
    }

    @Test
    fun normalSwitchIsScheduledThenConfirmed() {
        val p = gate.plan("com.android.chrome", 1_000)
        assertTrue(p is GatePlan.Schedule)
        assertEquals(300L, (p as GatePlan.Schedule).delayMillis)
        val r = gate.confirm("com.android.chrome", 1_300)
        assertEquals(GateResult.Confirmed("com.android.chrome"), r)
    }

    @Test
    fun confirmBeforeDebounceIsDropped() {
        gate.plan("com.android.chrome", 1_000)
        assertTrue(gate.confirm("com.android.chrome", 1_100) is GateResult.Dropped)
    }

    @Test
    fun permissionDialogOnTopDoesNotCountAsLeave() {
        gate.plan("com.android.chrome", 1_000)
        val r = gate.confirm("com.google.android.permissioncontroller", 1_400)
        assertTrue(r is GateResult.Dropped)
    }

    @Test
    fun ownOverlayOnTopDoesNotCountAsLeave() {
        gate.plan("com.gemini", 1_000)
        val r = gate.confirm("cn.companion.pet", 1_400)
        assertTrue(r is GateResult.Dropped)
    }

    @Test
    fun repeatedEventsKeepLatest() {
        gate.plan("com.a", 1_000)
        gate.plan("com.b", 1_100)
        // 实际活动窗口是 b，就以 b 为准；旧事件 a 不生效。
        assertEquals(GateResult.Confirmed("com.b"), gate.confirm("com.b", 1_400))
        gate.plan("com.c", 1_500)
        gate.plan("com.d", 1_600)
        assertEquals(GateResult.Confirmed("com.d"), gate.confirm("com.d", 1_900))
    }
}
