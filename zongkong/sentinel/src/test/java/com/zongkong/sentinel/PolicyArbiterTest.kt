package com.zongkong.sentinel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 策略版本仲裁（报告 P0-03 / 指令第四节第 8 条）。
 *
 * 验收要求原文：「总控与哨兵收到不同 revision 时，不能把旧策略误认作最新策略」。
 *
 * 真实风险场景：推送是异步的，旧消息可能后到。
 * 用户刚刚结束严格窗口（revision=7，不强制），一条迟到的 revision=6 推送到达，
 * 如果哨兵无脑覆盖，就会在用户已经退出监管后继续拦娱乐应用。
 */
class PolicyArbiterTest {

    // ---------- 首次 ----------

    @Test
    fun `首次收到任何版本都接受`() {
        val r = PolicyArbiter.arbitrate(null, 0)
        assertEquals(PolicyArbiter.Verdict.ACCEPT_FIRST, r.verdict)
        assertTrue(r.apply)
        assertTrue(r.policyChanged)
    }

    // ---------- 版本前进 ----------

    @Test
    fun `版本变大时接受为新策略`() {
        val r = PolicyArbiter.arbitrate(6, 7)
        assertEquals(PolicyArbiter.Verdict.ACCEPT_NEWER, r.verdict)
        assertTrue(r.apply)
        assertTrue("版本变了就算策略更新", r.policyChanged)
    }

    @Test
    fun `版本大幅跳变也接受`() {
        val r = PolicyArbiter.arbitrate(2, 99)
        assertTrue(r.apply)
        assertTrue(r.policyChanged)
    }

    // ---------- 版本相同 ----------

    @Test
    fun `版本相同时接受但不算策略变化`() {
        val r = PolicyArbiter.arbitrate(7, 7)
        assertEquals(PolicyArbiter.Verdict.ACCEPT_SAME, r.verdict)
        assertTrue("心跳要能刷新", r.apply)
        assertFalse("同一策略不该被当成新策略", r.policyChanged)
    }

    // ---------- 版本倒退：核心回归 ----------

    @Test
    fun `版本倒退时拒绝写入`() {
        val r = PolicyArbiter.arbitrate(7, 6)
        assertEquals(PolicyArbiter.Verdict.REJECT_STALE, r.verdict)
        assertFalse("旧策略绝不能覆盖新策略", r.apply)
        assertFalse(r.policyChanged)
    }

    @Test
    fun `迟到的旧推送不会把已结束的监管重新打开`() {
        // 用户已经退出监管（revision=7，不强制）。
        val current = 7
        // 一条 revision=6 的旧推送后到（那时还在强制）。
        val stale = PolicyArbiter.arbitrate(current, 6)
        assertFalse("必须拒绝，否则用户退出监管后还会继续被拦", stale.apply)
        assertTrue(stale.note.contains("拒绝过期策略"))
    }

    @Test
    fun `版本 0 也不能覆盖更高版本`() {
        assertFalse(PolicyArbiter.arbitrate(1, 0).apply)
    }

    @Test
    fun `拒绝时有可读的中文说明`() {
        val r = PolicyArbiter.arbitrate(5, 3)
        assertTrue("诊断要能说清收到几、当前几", r.note.contains("3") && r.note.contains("5"))
    }

    // ---------- 单调性 ----------

    @Test
    fun `连续递增的推送全部被接受`() {
        var cur: Int? = null
        for (v in 0..10) {
            val r = PolicyArbiter.arbitrate(cur, v)
            assertTrue("版本 $v 应被接受", r.apply)
            cur = v
        }
        assertEquals(10, cur)
    }

    @Test
    fun `乱序到达时保留最大的那一个`() {
        // 模拟网络乱序：7, 5, 9, 6, 8
        var cur: Int? = null
        for (v in listOf(7, 5, 9, 6, 8)) {
            if (PolicyArbiter.arbitrate(cur, v).apply) cur = maxOf(cur ?: v, v)
        }
        assertEquals("最终必须停在收到过的最大版本", 9, cur)
    }
}
