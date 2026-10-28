package com.zongkong.app.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「无障碍被系统从列表里摘掉」必须被自动修好，而不是只能手点。
 *
 * ## 事故现场（iQOO 12，0.16.0-health-v2）
 *
 * 诊断日志 553 条里，15:25 之后连续 6 小时只有「健康异常」，
 * **零条 rebind_sent** —— 一次都没试着修。`dumpsys accessibility` 显示：
 *
 *     Binding services:{}
 *     Crashed services:{{com.zongkong.app/...GuardService}, ...}
 *
 * 系统把服务标成「崩过」后不再 bind。而自愈的判断是「设置里没有我 = 用户关的」，
 * 于是只发提醒、永不动手。这是「老是挂掉、只能手点」的主因。
 *
 * ## 修法
 *
 * 把「设置里没有我」拆成两种：用户关的（尊重，只提醒）、系统摘的（必须修）。
 * 判据见 [SelfHeal.systemRemovedPure]：历史上被挂载过 + 无障碍总开关还开着。
 */
class UnregisteredStepTest {

    // ---------- 系统摘的 vs 用户关的 ----------

    @Test
    fun `运行过且总开关还开着算系统摘的`() {
        // 真机上的典型：服务崩掉后系统把它从列表里删了，但无障碍总开关仍是 1。
        // 这种必须修，不然就是「只能手点」。
        assertEquals(true, SelfHeal.systemRemovedPure(everRan = true, masterOn = true))
    }

    @Test
    fun `总开关被关掉算用户自己关的`() {
        // 用户去设置里关，通常会把总开关一起关掉。这种不能替他打开。
        assertEquals(false, SelfHeal.systemRemovedPure(everRan = true, masterOn = false))
    }

    @Test
    fun `从没运行过就不算被摘掉`() {
        // 刚装、还没授权的时候「列表里没有我」是正常的，不该去写系统设置。
        assertEquals(false, SelfHeal.systemRemovedPure(everRan = false, masterOn = true))
        assertEquals(false, SelfHeal.systemRemovedPure(everRan = false, masterOn = false))
    }

    // ---------- 这个判定真的接进了「修还是提醒」 ----------

    @Test
    fun `系统摘掉时会去修而不是只提醒`() {
        val now = 1_000_000L
        // enabled=false（列表里没有我）+ systemRemoved=true（是系统摘的）→ 应该 HEAL
        assertEquals(
            SelfHeal.Step.HEAL,
            SelfHeal.step(
                running = false,
                enabled = false,
                canWrite = true,
                heals = 0,
                now = now,
                lastHeal = 0,
                systemRemoved = true,
            ),
        )
    }

    @Test
    fun `用户关掉时只提醒不修`() {
        val now = 1_000_000L
        // enabled=false 且不是系统摘的 → WARN，不擅自打开用户关掉的东西
        assertEquals(
            SelfHeal.Step.WARN,
            SelfHeal.step(
                running = false,
                enabled = false,
                canWrite = true,
                heals = 0,
                now = now,
                lastHeal = 0,
                systemRemoved = false,
            ),
        )
    }

    @Test
    fun `在跑的时候什么都不做`() {
        assertEquals(
            SelfHeal.Step.NONE,
            SelfHeal.step(running = true, enabled = true, canWrite = true, heals = 0, now = 1_000_000L, lastHeal = 0),
        )
    }

    @Test
    fun `修的次数用完就转成提醒`() {
        // 连着修 MAX_HEALS 次都不成，多修只会一直开关服务把系统惹毛，交回用户更诚实。
        assertEquals(
            SelfHeal.Step.WARN,
            SelfHeal.step(
                running = false,
                enabled = true,
                canWrite = true,
                heals = SelfHeal.MAX_HEALS,
                now = 10_000_000L,
                lastHeal = 0,
            ),
        )
    }

    @Test
    fun `两次修复之间有间隔`() {
        val now = 1_000_000L
        // 刚修过（间隔不够）→ 这轮让路，不要疯狂开关
        assertEquals(
            SelfHeal.Step.WARN,
            SelfHeal.step(
                running = false,
                enabled = true,
                canWrite = true,
                heals = 1,
                now = now,
                lastHeal = now - 1_000L,
            ),
        )
    }

    @Test
    fun `没有写安全设置权限时只能提醒`() {
        assertEquals(
            SelfHeal.Step.WARN,
            SelfHeal.step(
                running = false,
                enabled = false,
                canWrite = false,
                heals = 0,
                now = 1_000_000L,
                lastHeal = 0,
                systemRemoved = true,
            ),
        )
    }
}
