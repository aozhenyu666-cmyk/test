package com.yishou.app.healthwire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回包字段的白名单证据（一手透明原则 + 规范 §0 铁律 7）。
 *
 * 哨兵只该知道两件事：这个 provider 活着没有、这一手停没停。
 * 屏幕文字、任务与断点、对话、集数、认知评估**一律不出现**。
 */
class HealthProviderWireTest {

    @Test
    fun `ping 回包只含白名单字段`() {
        val p = ProviderWire.pingPayload(
            pkg = "com.yishou.app",
            instanceId = "inst-1",
            stopped = false,
            atMono = 12_345,
        )
        assertEquals("键集合必须和白名单完全一致", ProviderWire.PING_KEYS, p.keys)
        assertEquals(true, p["ok"])
        assertEquals(2, p["v"])
        assertEquals("com.yishou.app", p["pkg"])
        assertEquals("inst-1", p["instanceId"])
        assertEquals(false, p["stopped"])
        assertEquals(12_345L, p["atMono"])
        assertEquals(true, p["providerAlive"])
    }

    @Test
    fun `ping 不含任何敏感字段`() {
        val p = ProviderWire.pingPayload("com.yishou.app", "inst", true, 1L)
        ProviderWire.FORBIDDEN_KEYS.forEach { bad ->
            assertFalse("ping 不该出现 $bad", p.containsKey(bad))
        }
        // 顺带核对值里也不该有长文本（防呆）
        p.forEach { (k, v) ->
            assertFalse("$k 的值不像敏感长文本", v is String && v.length > 64)
        }
    }

    @Test
    fun `ping 在无障碍没连上时 instanceId 是空串而不是 null`() {
        val p = ProviderWire.pingPayload("com.yishou.app", null, false, 0L)
        assertEquals("", p["instanceId"])
        // 空 instanceId 就是「没连上」，绝不能当成健康
        assertEquals("", p["instanceId"])
    }

    @Test
    fun `ping 的 stopped 反映真实暂停状态`() {
        val running = ProviderWire.pingPayload("com.yishou.app", "i", stopped = false, atMono = 1)
        assertEquals(false, running["stopped"])
        val stopped = ProviderWire.pingPayload("com.yishou.app", "i", stopped = true, atMono = 1)
        assertEquals(true, stopped["stopped"])
    }

    @Test
    fun `emergencyStop 回包只含白名单字段`() {
        val p = ProviderWire.emergencyStopPayload(
            pkg = "com.yishou.app",
            policyIds = listOf("yishou-window-1000", "yishou-window-2000"),
            revision = 1,
            atWall = 1_700_000_000_000L,
            atMono = 5_000L,
            alreadyStopped = false,
            note = "本地规则已全部停止（2 条）",
        )
        assertEquals("键集合必须和白名单完全一致", ProviderWire.EMERGENCY_KEYS, p.keys)
        assertEquals(true, p["ok"])
        assertEquals(true, p["stopped"])
        assertEquals(false, p["alreadyStopped"])
        assertEquals("yishou-window-1000,yishou-window-2000", p["policyIds"])
        assertEquals(2, p["stoppedCount"])
        assertEquals(1_700_000_000_000L, p["atWall"])
        assertEquals(5_000L, p["atMono"])
    }

    @Test
    fun `emergencyStop 回包不含任何敏感字段`() {
        val p = ProviderWire.emergencyStopPayload(
            "com.yishou.app", listOf("a"), 1, 1L, 2L, true, "已停止",
        )
        ProviderWire.FORBIDDEN_KEYS.forEach { bad ->
            assertFalse("emergencyStop 不该出现 $bad", p.containsKey(bad))
        }
    }

    @Test
    fun `白名单与禁用清单不重叠`() {
        assertTrue(
            "白名单和禁用清单不能有交集",
            ProviderWire.PING_KEYS.intersect(ProviderWire.FORBIDDEN_KEYS).isEmpty(),
        )
        assertTrue(
            ProviderWire.EMERGENCY_KEYS.intersect(ProviderWire.FORBIDDEN_KEYS).isEmpty(),
        )
    }

    @Test
    fun `policyIds 是逗号分隔的纯 id 摘要 不夹带业务内容`() {
        val ids = listOf("yishou-window-1761393600000", "yishou-window-1761480000000")
        val p = ProviderWire.emergencyStopPayload("com.yishou.app", ids, 1, 1L, 1L, false, "")
        val text = p["policyIds"] as String
        assertEquals(ids.joinToString(","), text)
        // 只由 id 组成：没有空格、换行、中文
        assertFalse(text.contains("\n"))
        assertFalse(text.contains(" "))
        ids.forEach { assertTrue(text.contains(it)) }
        assertEquals(ids.size, p["stoppedCount"])
    }

    @Test
    fun `协议版本冻结为 2`() {
        assertEquals(2, ProviderWire.PROTOCOL_VERSION)
        assertEquals(HealthProvider.PROTOCOL_VERSION, ProviderWire.PROTOCOL_VERSION)
    }
}
