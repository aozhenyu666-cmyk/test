package cn.companion.pet.core.device

import cn.companion.pet.core.action.ActionKind
import cn.companion.pet.core.action.ActionResult
import cn.companion.pet.core.action.ActionStatus
import cn.companion.pet.core.agent.AgentDecision
import cn.companion.pet.core.agent.AgentReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审计 P1-4 / P2-5：策略按真实活动窗口判断；动作后按重新观察核对，未核实不说成失败。 */
class ActiveWindowBindingTest {
    private val now = 10_000L
    private fun node(id: String, text: String = "", editable: Boolean = false, rid: String = "r") =
        NodeRef(id, "o1", "com.app", text, rid, "View", clickable = true, editable = editable, scrollable = true,
            left = 0, top = 0, right = 100, bottom = 50)
    private val obs = DeviceObservation("o1", "com.app", now - 1_000, now + 30_000, listOf(node("n1"), node("in", editable = true, rid = "input")))
    private fun snap(tracked: String?, active: String?) = DeviceSnapshot(tracked, true, true, ShizukuStatus.NOT_INSTALLED,
        activeWindowPackage = active, ownPackage = "cn.companion.pet")

    @Test
    fun ownWindowInFrontBlocksActionsOnOtherAppsObservation() {
        // 感知记录忽略桌宠自己，仍写着 com.app；真实活动窗口是桌宠设置页。
        val d = DevicePolicy.check(DeviceCommand.Back, snap("com.app", "cn.companion.pet"), obs, setOf("com.app"), now)
        assertFalse(d.allowed)
        val scroll = DevicePolicy.check(DeviceCommand.Scroll(null), snap("com.app", "cn.companion.pet"), obs, setOf("com.app"), now)
        assertFalse(scroll.allowed)
    }

    @Test
    fun activeWindowWinsOverStaleTrackedForeground() {
        assertFalse(DevicePolicy.check(DeviceCommand.TapNode("n1"), snap("com.app", "com.other"), obs, setOf("com.app"), now).allowed)
        assertTrue(DevicePolicy.check(DeviceCommand.TapNode("n1"), snap("com.stale", "com.app"), obs, setOf("com.app"), now).allowed)
        // 拿不到活动窗口时退回感知记录，行为与旧版一致。
        assertTrue(DevicePolicy.check(DeviceCommand.TapNode("n1"), snap("com.app", null), obs, setOf("com.app"), now).allowed)
    }

    @Test
    fun inputVerifiedByReadingTheSameFieldBack() {
        val after = obs.copy(id = "o2", nodes = listOf(node("in2", text = "你好", editable = true, rid = "input")))
        assertEquals(DeviceStatus.SUCCESS, DeviceVerifier.verify(DeviceCommand.InputText("in", "你好"), obs, after, "com.app").status)
        val wrong = obs.copy(id = "o3", nodes = listOf(node("in3", text = "别的", editable = true, rid = "input")))
        assertEquals(DeviceStatus.FAILED, DeviceVerifier.verify(DeviceCommand.InputText("in", "你好"), obs, wrong, "com.app").status)
        assertEquals(DeviceStatus.UNVERIFIED, DeviceVerifier.verify(DeviceCommand.InputText("in", "你好"), obs, null, "com.app").status)
    }

    @Test
    fun openAppVerifiedByActiveWindow() {
        assertEquals(DeviceStatus.SUCCESS, DeviceVerifier.verify(DeviceCommand.OpenApp("com.x"), null, null, "com.x").status)
        assertEquals(DeviceStatus.UNVERIFIED, DeviceVerifier.verify(DeviceCommand.OpenApp("com.x"), null, null, "com.launcher").status)
    }

    @Test
    fun tapStaysUnverifiedButReportsPageChange() {
        val after = obs.copy(id = "o2", nodes = listOf(node("other")))
        val v = DeviceVerifier.verify(DeviceCommand.TapNode("n1"), obs, after, "com.app")
        assertEquals(DeviceStatus.UNVERIFIED, v.status)
        assertEquals("true", v.evidence["pageChanged"])
        assertEquals("false", DeviceVerifier.verify(DeviceCommand.TapNode("n1"), obs, obs.copy(id = "o2"), "com.app").evidence["pageChanged"])
    }

    @Test
    fun unverifiedIsNotReportedAsFailure() {
        val result = ActionResult("a", ActionKind.TAP_NODE, ActionStatus.UNAVAILABLE, "已执行，页面有变化", mapOf("unverified" to "true"))
        val text = AgentReport.text(AgentDecision.ToolCall(ActionKind.TAP_NODE, emptyMap(), emptyList(), ""), result)
        assertFalse(text.contains("没成功"))
        assertTrue(AgentReport.lead(result).contains("未核实"))
    }
}
