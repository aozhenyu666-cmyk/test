package com.zongkong.app.healthwire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-3 回归测试：恢复预算必须在「换到新实例」时重置。
 *
 * ## 修复前的问题
 *
 * `recoveriesTried` 只在 [HealthLedger.beginRecovery] 里 +1，而 `resetRecoveries()` 全仓零调用。
 * 于是到达上限（默认 2 次）之后，**这个进程活着的余生都不再自愈**。
 *
 * 真机表现就是用户描述的那种：「关开一次能好一会儿，之后又永久失联，只能再关开」。
 * 这不是崩溃频率问题，是恢复预算再也发不出来。
 *
 * ## 为什么在 onConnected 里清零是对的
 *
 * 清零只在「真的换到了新实例」时发生 —— 那是一次新的故障周期的开始。
 * 如果关开失败、服务根本起不来，就不会走到 `onConnected`，计数照旧保留并停在上限，
 * 不会退化成「无限重启」。
 */
class RecoveryBudgetResetTest {

    private fun ledger(clock: () -> Long) = HealthLedger(mono = clock)

    /** 把预算用光：连上 → ack → 失败 → 用掉两次恢复。 */
    private fun exhaust(ledger: HealthLedger, now: Long) {
        ledger.onConnected()
        ledger.noteAck(1, now)
        repeat(3) { ledger.noteFailure("x") }
        assertTrue(ledger.beginRecovery(2, now))
        ledger.endRecovery()
        assertTrue(ledger.beginRecovery(2, now))
        ledger.endRecovery()
        assertFalse("第三次必须已经用光", ledger.beginRecovery(2, now))
    }

    /**
     * 核心用例：预算用光之后，用户手动关开一次（= 新实例连上），必须能再次自愈。
     *
     * 修复前：`recoveriesTried` 仍是 2 → `beginRecovery` 恒 false → 永久失联。
     */
    @Test
    fun `新实例连上后恢复预算重置`() {
        var now = 1_000L
        val l = ledger { now }
        l.setRegistered(true)
        exhaust(l, now)
        assertEquals("前提：预算已经用光", 2, l.recoveriesTried)

        // 用户关开一次无障碍 → 新实例
        now += 60_000
        l.onConnected()
        assertEquals("换到新实例就是新的故障周期，预算必须归零", 0, l.recoveriesTried)
        assertTrue("重置之后必须能再次发起自愈", l.beginRecovery(2, now))
    }

    /**
     * 反向对照：服务起不来（没有 onConnected）时预算**不能**重置，
     * 否则就变成无限重启循环。
     */
    @Test
    fun `服务没起来时预算保持用光 不会无限重试`() {
        var now = 1_000L
        val l = ledger { now }
        l.setRegistered(true)
        exhaust(l, now)

        // 恢复动作失败了很久：一直没 onConnected
        now += 600_000
        l.endRecovery()
        assertFalse("服务没起来就不该再发恢复", l.beginRecovery(2, now))
        assertEquals("预算不能被时间冲淡", 2, l.recoveriesTried)
    }

    /** 断开再连上也算新周期（不是只有「第一次连上」才清零）。 */
    @Test
    fun `断开重连也会重置预算`() {
        var now = 1_000L
        val l = ledger { now }
        l.setRegistered(true)
        exhaust(l, now)

        now += 1_000
        l.onDisconnected(now)
        now += 1_000
        l.onConnected()
        assertEquals(0, l.recoveriesTried)
        assertTrue(l.beginRecovery(2, now))
    }

    /** 重置只影响预算，不能顺手把「恢复中」状态也清了（否则会重复计数）。 */
    @Test
    fun `重置预算不影响正在进行的恢复`() {
        var now = 1_000L
        val l = ledger { now }
        l.setRegistered(true)
        l.onConnected()
        l.noteAck(1, now)
        repeat(3) { l.noteFailure("x") }
        assertTrue(l.beginRecovery(2, now))
        assertTrue(l.recoveringSinceMono > 0)

        // 恢复期间又连上一次（Handler 重建）
        now += 5_000
        l.onConnected()
        assertTrue("恢复中状态要保留，由 endRecovery 结束", l.recoveringSinceMono > 0)
    }
}
