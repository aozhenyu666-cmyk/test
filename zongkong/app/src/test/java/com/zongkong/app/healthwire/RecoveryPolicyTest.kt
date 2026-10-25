package com.zongkong.app.healthwire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复编排的纯逻辑部分（T11/T12）。
 *
 * 真机的无障碍列表读写、跨进程文件锁只能在设备上跑（→ NOT_RUN），
 * 但「什么时候允许发恢复请求」「恢复请求发出后是否等于修好」这两条判断可以在这里固定下来。
 */
class RecoveryPolicyTest {

    private fun ledger(mono: () -> Long): HealthLedger =
        HealthLedger(mono = mono, newInstanceId = object : () -> String {
            private var n = 0
            override fun invoke(): String = "inst-${++n}"
        })

    @Test
    fun `重绑请求已发出不等于健康`() {
        var now = 1_000L
        val l = ledger { now }
        l.setRegistered(true)
        l.onConnected()
        l.noteAck(1, now)
        assertTrue(l.verdict(permissionEnabled = true).healthy)

        // 失败到需要恢复
        repeat(3) { l.noteFailure("dispatcher timeout") }
        now += 31_000
        assertTrue(l.shouldStartRecovery(l.verdict(permissionEnabled = true)))

        // 「恢复请求已发出」
        assertTrue(l.beginRecovery(maxRecoveries = 2, nowMono = now))
        val v = l.verdict(permissionEnabled = true)
        assertEquals(com.zongkong.app.health.HealthStatus.RECOVERING, v.status)
        assertFalse("恢复请求发出后绝不能立刻变 HEALTHY", v.healthy)
    }

    @Test
    fun `旧实例上的 ack 不能算恢复成功`() {
        var now = 1_000L
        val l = ledger { now }
        l.setRegistered(true)
        l.onConnected()
        l.noteAck(1, now)
        repeat(3) { l.noteFailure("x") }
        now += 31_000
        l.beginRecovery(maxRecoveries = 2, nowMono = now)

        // 还是老实例（没重连），即使有 ack 也不算
        now += 500
        l.noteAck(2, now)
        val v = l.verdict(permissionEnabled = true)
        assertFalse("没有新实例就不算恢复成功，实际 ${v.status}", v.healthy)
    }

    @Test
    fun `恢复次数封顶两次`() {
        var now = 1_000L
        val l = ledger { now }
        l.setRegistered(true)
        l.onConnected()
        l.noteAck(1, now)
        repeat(3) { l.noteFailure("x") }
        assertTrue(l.beginRecovery(2, now))
        l.endRecovery()
        assertTrue(l.beginRecovery(2, now + 1))
        l.endRecovery()
        assertFalse("第三次必须拒绝（最多两次）", l.beginRecovery(2, now + 2))
    }

    @Test
    fun `恢复预算用完升级为失联处理`() {
        var now = 1_000L
        val l = ledger { now }
        l.setRegistered(true)
        l.onConnected()
        l.noteAck(1, now)
        repeat(3) { l.noteFailure("x") }
        l.beginRecovery(2, now)
        now += 31_000
        val v = l.verdict(permissionEnabled = true)
        assertEquals(com.zongkong.app.health.HealthStatus.UNRESPONSIVE, v.status)
        assertEquals(com.zongkong.app.health.HealthAction.ESCALATE, v.action)
    }

    @Test
    fun `紧急暂停优先于一切恢复动作`() {
        var now = 1_000L
        val l = ledger { now }
        l.setRegistered(true)
        l.onConnected()
        l.noteAck(1, now)
        repeat(3) { l.noteFailure("x") }
        l.setPaused(true)
        val v = l.verdict(permissionEnabled = true)
        assertEquals(com.zongkong.app.health.HealthStatus.PAUSED, v.status)
        assertFalse("暂停时不发恢复", l.shouldStartRecovery(v))
    }
}
