package com.zongkong.app.system

import com.zongkong.app.health.HealthStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自愈不能再把自己修坏：健康判据抖一下 ≠ 系统真把服务停了。
 *
 * 真机事故（Android 14，40 分钟，`files/zk/diag.jsonl` 114 条）：
 * ```
 * health_ok HEALTHY → stale 开着但没在运行 → health_state SUSPECT(32s) →
 * unbound → destroyed → rebind_ok → health_lost UNRESPONSIVE
 * ```
 * 计数：connected 18 / health_disconnected 14 / stale 13 / destroyed 7 / unbound 7 / rebind_ok 5。
 * 每约 4 分钟一次破坏性重绑，每次自造约一分钟真失联；哨兵同步刷出
 * 「总控说自己下线了／心跳回来了」。用户看到的就是「无障碍显示开着，却拦不住娱乐 APP」。
 *
 * 根因：`running` 来自 [com.zongkong.app.ui.PermissionStatus.guardRunning]（`status == HEALTHY`），
 * 它把「未知/可疑/恢复中/暂停」和「确认失联/无障碍关了」压成同一个 false，
 * 而 `HealthConfig.staleAfterMs = 30_000` 对 10 秒探测周期 —— 正常服务也会周期性 HEALTHY→SUSPECT。
 *
 * 修法：`step()` 增加 [HealthStatus] 语义门，只有 [SelfHeal.statusMeansDead] 为真才可能拆。
 */
class SelfHealOscillationTest {

    private val t0 = 10_000_000L
    private val gapPast = t0 - SelfHeal.HEAL_GAP_MS - 1
    private val confirmed = SelfHeal.STALE_CONFIRM_MS + 1

    private fun step(
        running: Boolean = false,
        enabled: Boolean = true,
        canWrite: Boolean = true,
        heals: Int = 0,
        now: Long = t0,
        lastHeal: Long = gapPast,
        systemRemoved: Boolean = false,
        status: HealthStatus? = HealthStatus.UNRESPONSIVE,
        staleForMs: Long? = confirmed,
    ) = SelfHeal.step(
        running = running,
        enabled = enabled,
        canWrite = canWrite,
        heals = heals,
        now = now,
        lastHeal = lastHeal,
        systemRemoved = systemRemoved,
        status = status,
        staleForMs = staleForMs,
    )

    // ---------- 回归核心：证据不足的状态绝不得触发重绑 ----------

    @Test
    fun `SUSPECT 不得触发重绑`() {
        // 真机现象：最近真实响应 32s 前 → SUSPECT → running=false。绑定其实好好的。
        assertEquals(SelfHeal.Step.NONE, step(status = HealthStatus.SUSPECT))
    }

    @Test
    fun `UNKNOWN 不得触发重绑`() {
        assertEquals(SelfHeal.Step.NONE, step(status = HealthStatus.UNKNOWN))
    }

    @Test
    fun `RECOVERING 不得触发重绑`() {
        // 恢复中说明系统正在自己救，这时候拆它是最糟的。
        assertEquals(SelfHeal.Step.NONE, step(status = HealthStatus.RECOVERING))
    }

    @Test
    fun `PAUSED 不得触发重绑`() {
        assertEquals(SelfHeal.Step.NONE, step(status = HealthStatus.PAUSED))
    }

    @Test
    fun `HEALTHY 不得触发重绑`() {
        assertEquals(SelfHeal.Step.NONE, step(status = HealthStatus.HEALTHY))
    }

    @Test
    fun `回放真机一整轮抖动不产生任何重绑`() {
        // 10 秒一格：HEALTHY ↔ SUSPECT 来回抖，全程状态都不是「确诊死了」。
        val sequence = listOf(
            HealthStatus.HEALTHY,
            HealthStatus.SUSPECT,
            HealthStatus.HEALTHY,
            HealthStatus.SUSPECT,
            HealthStatus.HEALTHY,
            HealthStatus.SUSPECT,
            HealthStatus.HEALTHY,
        )
        val heals = sequence.count { s ->
            step(running = s == HealthStatus.HEALTHY, status = s, staleForMs = confirmed) == SelfHeal.Step.HEAL
        }
        assertEquals("判据抖动不该产生任何一次破坏性重绑", 0, heals)
    }

    @Test
    fun `证据不足时连提醒都不该发`() {
        // 30 秒的判据抖动不该告诉用户「它被系统停了」。check() 在这种情况下直接 return。
        assertEquals(SelfHeal.Step.NONE, step(status = HealthStatus.SUSPECT, canWrite = false))
    }

    // ---------- 真故障仍然要修 ----------

    @Test
    fun `确诊 UNRESPONSIVE 且持续够久才该修`() {
        assertEquals(SelfHeal.Step.HEAL, step(status = HealthStatus.UNRESPONSIVE))
    }

    @Test
    fun `确诊 DISABLED 且系统摘掉时按恢复处理`() {
        assertEquals(
            SelfHeal.Step.HEAL,
            step(status = HealthStatus.DISABLED, systemRemoved = true),
        )
    }

    @Test
    fun `诊断没跑但刚确诊的那一秒先不拆`() {
        // 次要防抖：给系统一个自己挂回来的机会。
        assertEquals(SelfHeal.Step.NONE, step(status = HealthStatus.UNRESPONSIVE, staleForMs = 0L))
    }

    // ---------- 原有安全护栏不得被削弱 ----------

    @Test
    fun `用户自己关掉了只提醒不修`() {
        assertEquals(
            SelfHeal.Step.WARN,
            step(status = HealthStatus.DISABLED, enabled = false, systemRemoved = false),
        )
    }

    @Test
    fun `没有写安全设置权限只提醒`() {
        assertEquals(SelfHeal.Step.WARN, step(status = HealthStatus.UNRESPONSIVE, canWrite = false))
    }

    @Test
    fun `修复次数用满只提醒`() {
        assertEquals(
            SelfHeal.Step.WARN,
            step(status = HealthStatus.UNRESPONSIVE, heals = SelfHeal.MAX_HEALS),
        )
    }

    @Test
    fun `两次修复间隔不够只提醒`() {
        assertEquals(
            SelfHeal.Step.WARN,
            step(status = HealthStatus.UNRESPONSIVE, lastHeal = t0 - 1_000L),
        )
    }

    @Test
    fun `在跑就什么都不做`() {
        assertEquals(
            SelfHeal.Step.NONE,
            step(running = true, status = HealthStatus.UNRESPONSIVE, staleForMs = confirmed),
        )
    }

    @Test
    fun `没给状态时保持旧语义`() {
        // 兼容老调用点：status/staleForMs 都不给时，行为等价于改动前的 step()。
        assertEquals(
            SelfHeal.Step.HEAL,
            SelfHeal.step(
                running = false,
                enabled = true,
                canWrite = true,
                heals = 0,
                now = t0,
                lastHeal = 0L,
            ),
        )
    }

    // ---------- 状态语义本身 ----------

    @Test
    fun `只有确诊死掉的三种状态算死`() {
        val dead = listOf(
            HealthStatus.DISABLED,
            HealthStatus.UNRESPONSIVE,
            HealthStatus.UNREGISTERED,
        )
        dead.forEach { assertEquals("$it 应算真故障", true, SelfHeal.statusMeansDead(it)) }
    }

    @Test
    fun `证据不足的四种状态都不算死`() {
        val unproven = listOf(
            HealthStatus.HEALTHY,
            HealthStatus.UNKNOWN,
            HealthStatus.SUSPECT,
            HealthStatus.RECOVERING,
            HealthStatus.PAUSED,
        )
        unproven.forEach { assertEquals("$it 不该算真故障", false, SelfHeal.statusMeansDead(it)) }
    }
}
