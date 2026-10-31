package com.zongkong.app.healthwire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-6a：紧急暂停必须会自己失效。
 *
 * 旧行为的病灶：`emergencyStopped()` 只看「有没有 STOPPED 记录」，不看窗口、不看时长，
 * 于是客户端一旦被暂停就**永久**暂停（哨兵侧有到期，客户端侧没有）。
 * 这组用例钉住两个数：**到期时刻**必须算得出来、**到期后**必须不再暂停。
 */
class EmergencyLapseTest {

    private val now = 1_700_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min

    private fun policy(
        starts: Long = now - min,
        ends: Long = now + 30 * min,
        stopped: Boolean = true,
        enabled: Boolean = true,
    ) = LocalPolicy(
        policyId = "p",
        startsAtWall = starts,
        endsAtWall = ends,
        emergencyState = if (stopped) LocalPolicy.EMERGENCY_STOPPED else LocalPolicy.EMERGENCY_NONE,
        enabled = enabled,
    )

    // ---------- deadline：按下那一刻就算死 ----------

    @Test
    fun `到期时刻取窗口结束与上限的较小值`() {
        // 窗口只剩 30 分钟，上限 2 小时 → 以窗口为准。
        assertEquals(now + 30 * min, EmergencyLapse.deadline(listOf(policy()), now, EmergencyLapse.DEFAULT_MAX_MS))
        // 窗口还有 10 小时，上限 2 小时 → 以上限为准（免得一次暂停变成整天不管）。
        assertEquals(now + 2 * hour, EmergencyLapse.deadline(listOf(policy(ends = now + 10 * hour)), now, EmergencyLapse.DEFAULT_MAX_MS))
    }

    @Test
    fun `没有生效窗口时只按上限兜底 绝不留永久暂停`() {
        val until = EmergencyLapse.deadline(emptyList(), now, EmergencyLapse.DEFAULT_MAX_MS)
        assertEquals(now + EmergencyLapse.DEFAULT_MAX_MS, until)
        assertTrue("上限必须有限", until > now)
    }

    @Test
    fun `上限设 0 表示只按窗口判`() {
        assertEquals(now + 5 * hour, EmergencyLapse.deadline(listOf(policy(ends = now + 5 * hour)), now, EmergencyLapse.capMs(0)))
    }

    @Test
    fun `上限小时数被夹在 24 小时内`() {
        assertEquals(24 * hour, EmergencyLapse.capMs(1000))
        assertEquals(0L, EmergencyLapse.capMs(0))
        assertEquals(2 * hour, EmergencyLapse.capMs(EmergencyLapse.DEFAULT_MAX_HOURS))
    }

    // ---------- eval：到期就不算暂停 ----------

    @Test
    fun `没有记录也没有标记就是没暂停`() {
        val v = EmergencyLapse.eval(0L, 0L, listOf(policy(stopped = false)), now)
        assertFalse(v.active)
        assertEquals(0L, v.untilWall)
        assertFalse(v.staleOnDisk)
    }

    @Test
    fun `到期前算暂停 到期后不算并标记需要清盘`() {
        val until = now + 30 * min
        val before = EmergencyLapse.eval(now, until, listOf(policy()), now - 10 * min)
        assertTrue(before.active)
        assertEquals(until, before.untilWall)
        assertFalse(before.staleOnDisk)

        // 到期的那一刻就不再暂停（边界：now == until）
        val atEdge = EmergencyLapse.eval(now - 30 * min, until, listOf(policy()), until)
        assertFalse("到点即失效，不许多停一秒", atEdge.active)
        assertTrue("磁盘上还写着已暂停 → 需要清一次", atEdge.staleOnDisk)

        val after = EmergencyLapse.eval(now - 60 * min, now - 30 * min, listOf(policy(ends = now + 10 * hour)), now)
        assertFalse(after.active)
        assertTrue(after.staleOnDisk)
        assertTrue(after.why.contains("到期"))
    }

    @Test
    fun `老数据没有到期时刻时按上限补算 不能当成永久`() {
        // 升级前按下的暂停：untilWall = 0。age = 3 小时 > 默认上限 2 小时 → 必须已失效。
        val v = EmergencyLapse.eval(now - 3 * hour, 0L, listOf(policy(ends = now + 10 * hour)), now)
        assertFalse("老记录不许变成永久暂停", v.active)
        assertEquals(now - 3 * hour + EmergencyLapse.DEFAULT_MAX_MS, v.untilWall)
        assertTrue(v.staleOnDisk)
    }

    @Test
    fun `老数据在上限内仍然算暂停`() {
        val v = EmergencyLapse.eval(now - 10 * min, 0L, listOf(policy(ends = now + 10 * hour)), now)
        assertTrue(v.active)
        assertEquals(now - 10 * min + EmergencyLapse.DEFAULT_MAX_MS, v.untilWall)
    }

    @Test
    fun `老数据多次判定得到同一个到期时刻 不随现在漂移`() {
        val a = EmergencyLapse.eval(now - min, 0L, listOf(policy(ends = now + 10 * hour)), now)
        val b = EmergencyLapse.eval(now - min, 0L, listOf(policy(ends = now + 10 * hour)), now + 7 * min)
        assertEquals("到期时刻必须钉死在按下那一刻，否则永远差 2 小时", a.untilWall, b.untilWall)
    }

    @Test
    fun `只有标记没有记录时按窗口结束判`() {
        val v = EmergencyLapse.eval(0L, 0L, listOf(policy(ends = now - 5 * min)), now)
        assertFalse(v.active)
        assertTrue(v.staleOnDisk)

        val alive = EmergencyLapse.eval(0L, 0L, listOf(policy(ends = now + 5 * min)), now)
        assertTrue(alive.active)
    }

    // ---------- 关键断言：到期 ≠ 已恢复监管 ----------

    @Test
    fun `到期只解除暂停 规则本身照旧执行`() {
        val p = policy(ends = now + 3 * hour)
        val expired = EmergencyLapse.eval(now - 3 * hour, now - hour, listOf(p), now)
        assertFalse("到点后不再暂停", expired.active)
        // 规则还在窗口内、还启用 —— 兜底判定必须能重新锁屏。
        assertTrue(p.effective(now))
        assertEquals(LocalPolicy.ACTION_LOCK, p.failureAction)
    }
}
