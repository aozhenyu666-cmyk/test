package cn.companion.pet.core.device

import cn.companion.pet.core.action.ActionKind
import cn.companion.pet.core.action.ActionRequest
import cn.companion.pet.core.action.ActionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControllerTest {

    private val now = 10_000L
    private fun node(id: String = "n1") = NodeRef(id, "o1", "com.test", "按钮", "id/button", "Button",
        clickable = true, editable = false, scrollable = false, left = 0, top = 0, right = 10, bottom = 10)
    private fun obs(expires: Long = 20_000L) = DeviceObservation("o1", "com.test", now, expires, listOf(node()))
    private fun snap(unlocked: Boolean = true, connected: Boolean = true, packageName: String = "com.test") =
        DeviceSnapshot(packageName, unlocked, connected, ShizukuStatus.READY)

    @Test
    fun shizukuStatusMapping() {
        assertEquals(ShizukuStatus.NOT_INSTALLED, ShizukuStatusResolver.resolve(false, false, false, false))
        assertEquals(ShizukuStatus.UNSUPPORTED, ShizukuStatusResolver.resolve(true, false, true, true))
        assertEquals(ShizukuStatus.NOT_RUNNING, ShizukuStatusResolver.resolve(true, false, false, true))
        assertEquals(ShizukuStatus.NO_PERMISSION, ShizukuStatusResolver.resolve(true, true, false, false))
        assertEquals(ShizukuStatus.READY, ShizukuStatusResolver.resolve(true, true, false, true))
    }

    @Test
    fun policyRejectsLockedDisallowedSensitiveAndStale() {
        val cmd = DeviceCommand.TapNode("n1")
        assertFalse(DevicePolicy.check(cmd, snap(unlocked = false), obs(), setOf("com.test"), now).allowed)
        assertFalse(DevicePolicy.check(cmd, snap(), obs(), setOf("com.other"), now).allowed)
        assertFalse(DevicePolicy.check(cmd, snap(), obs(), setOf("com.android.settings"), now).allowed)
        assertFalse(DevicePolicy.check(cmd, snap(), obs(expires = now - 1), setOf("com.test"), now).allowed)
        assertFalse(DevicePolicy.check(DeviceCommand.TapNode("missing"), snap(), obs(), setOf("com.test"), now).allowed)
        assertFalse(DevicePolicy.check(DeviceCommand.InputText("n1", "x".repeat(301)), snap(), obs(), setOf("com.test"), now).allowed)
        assertTrue(DevicePolicy.check(cmd, snap(), obs(), setOf("com.test"), now).allowed)
    }

    @Test
    fun observationExpires() {
        assertTrue(obs().valid(now))
        assertFalse(obs(expires = now - 1).valid(now))
    }

    private class FakeController(private val result: DeviceResult) : DeviceController {
        override fun snapshot(now: Long) = DeviceSnapshot("com.test", true, true, ShizukuStatus.READY)
        override fun observe(now: Long) = result
        override fun execute(command: DeviceCommand, now: Long) = result
    }

    @Test
    fun unverifiedDeviceResultDoesNotPretendSuccess() {
        val adapter = DeviceActionAdapter(FakeController(DeviceResult(DeviceStatus.UNVERIFIED, "已点击，需重新观察", "o2")))
        val result = adapter.execute(ActionRequest("a1", ActionKind.TAP_NODE, mapOf("node_id" to "n1")))
        assertEquals(ActionStatus.UNAVAILABLE, result.status)
        assertEquals("true", result.data["unverified"])
        assertEquals("o2", result.data["observationId"])
    }

    @Test
    fun successDeviceResultIsReportedWithEvidence() {
        val adapter = DeviceActionAdapter(FakeController(DeviceResult(DeviceStatus.SUCCESS, "状态读取成功",
            evidence = mapOf("foreground" to "com.test"))))
        val result = adapter.execute(ActionRequest("a2", ActionKind.GET_DEVICE_STATE))
        assertEquals(ActionStatus.SUCCESS, result.status)
        assertEquals("com.test", result.data["foreground"])
    }

    @Test
    fun foregroundSwitchAndInputShapeAreRejected() {
        assertFalse(DevicePolicy.check(DeviceCommand.TapNode("n1"), snap(packageName = "com.other"), obs(), setOf("com.test"), now).allowed)
        assertFalse(DevicePolicy.check(DeviceCommand.InputText("n1", "text"), snap(), obs(), setOf("com.test"), now).allowed)
        val invalidBounds = obs().copy(nodes = listOf(node().copy(right = 0)))
        assertFalse(DevicePolicy.check(DeviceCommand.TapNode("n1"), snap(), invalidBounds, setOf("com.test"), now).allowed)
    }
}