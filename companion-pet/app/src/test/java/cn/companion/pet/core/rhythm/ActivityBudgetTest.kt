package cn.companion.pet.core.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** 时段浮动：周末与深夜更活跃；但绝不越过用户明确拒绝的冷却。 */
class ActivityBudgetTest {
    private val zone = ZoneOffset.UTC
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `工作日白天最安静`() {
        // 2026-10-26 周一 10:00
        val p = ActivityBudget.planFor(at("2026-10-26T10:00:00Z"), zone)
        assertEquals(25, p.dailyCap)
    }

    @Test
    fun `工作日晚上比白天活跃`() {
        val p = ActivityBudget.planFor(at("2026-10-26T20:00:00Z"), zone)
        assertEquals(40, p.dailyCap)
    }

    @Test
    fun `周末最活跃`() {
        // 2026-10-25 周日 15:00
        val p = ActivityBudget.planFor(at("2026-10-25T15:00:00Z"), zone)
        assertEquals(60, p.dailyCap)
    }

    @Test
    fun `深夜活跃但不催睡`() {
        val p = ActivityBudget.planFor(at("2026-10-26T23:30:00Z"), zone)
        assertEquals(40, p.dailyCap)
    }

    @Test
    fun `凌晨保持完全安静`() {
        val p = ActivityBudget.planFor(at("2026-10-27T03:00:00Z"), zone)
        assertEquals("00_00 之后不活跃，陪熬夜不吵睡觉", 0, p.dailyCap)
    }

    @Test
    fun `间隔保持一到两分钟量级`() {
        val p = ActivityBudget.planFor(at("2026-10-25T15:00:00Z"), zone)
        assertTrue("间隔应在 1-2 分钟之间", p.minIntervalMillis in 60_000L..120_000L)
    }
    // ---- 拒绝优先：自适应预算只换数字，绝不能缩短「明确拒绝」的冷却 ----

    @Test
    fun `自适应预算再活跃，也不能越过用户刚点过的拒绝冷却`() {
        val dir = kotlin.io.path.createTempDirectory("budget").toFile()
        val policy = cn.companion.pet.core.presence.PresencePolicy(
            cn.companion.pet.core.presence.PresenceConfig(),
            cn.companion.pet.core.presence.PresencePolicyStore(dir),
            zone,
        )
        // 周末正午：预算最活跃（60），若自适应逻辑插队，这里就会变成允许。
        val weekendNoon = at("2026-10-25T12:00:00Z")
        policy.feedback(cn.companion.pet.core.presence.PresenceFeedback.DISMISSED, weekendNoon)
        val plan = ActivityBudget.planFor(weekendNoon + 60_000L, zone)
        assertEquals("前提：这个点的预算本身是允许开口的", 60, plan.dailyCap)

        val gate = policy.check(weekendNoon + 60_000L, paused = false, plan = plan)
        assertTrue("刚点过 x 就必须闭嘴，预算不得覆盖", !gate.allowed)
        assertTrue("理由要指向拒绝冷却", gate.reason.contains("关掉"))

        // 冷却结束后才恢复，而且不是被预算“救”回来的。
        assertTrue(policy.check(weekendNoon + 31 * 60_000L, plan = plan).allowed)
    }

    @Test
    fun `预算为 0 的时段直接不允许开口`() {
        val policy = cn.companion.pet.core.presence.PresencePolicy(
            cn.companion.pet.core.presence.PresenceConfig(dailyCap = 99),
            cn.companion.pet.core.presence.PresencePolicyStore(
                kotlin.io.path.createTempDirectory("budget0").toFile(),
            ),
            zone,
        )
        val threeAm = at("2026-10-27T03:00:00Z")
        val plan = ActivityBudget.planFor(threeAm, zone)
        assertEquals(0, plan.dailyCap)
        val gate = policy.check(threeAm, plan = plan)
        assertTrue("凌晨不开口", !gate.allowed)
    }
}
