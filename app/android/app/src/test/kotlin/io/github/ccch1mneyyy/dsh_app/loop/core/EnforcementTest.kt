package io.github.ccch1mneyyy.dsh_app.loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0 执法可靠性：本地规则缓存（C2）、锁的仲裁（C5）、违抗复查窗（C4）、锁和界面分开（C1）。 */
class EnforcementTest {

    private val min = 60_000L

    private fun snapshot(vararg rules: Pair<String, Long>, block: Map<String, Long> = emptyMap()): Map<String, Any?> =
        linkedMapOf(
            "v" to 1,
            "rules" to rules.map { (kind, until) -> linkedMapOf("kind" to kind, "until" to until, "why" to "why-$kind") },
            "block" to block,
        )

    // ---------------------------------------------------------------- C2 RuleBook

    @Test
    fun rules_survive_a_restart_and_run_out_on_the_local_clock() {
        val store = MemorySnapshotStore()
        val now = 1_000_000L
        RuleBook(store).replace(snapshot("task" to now + 30 * min, block = mapOf("com.game" to now + 10 * min)), now)

        // The engine and the process died; a new process reads the same file.
        val book = RuleBook(store).also { it.recover() }
        assertEquals(now + 30 * min, book.lockedUntil(now + min))
        assertEquals("task", book.primary(now + min)?.kind)
        assertEquals(setOf("com.game"), book.blocked(now + min))

        assertEquals(emptySet<String>(), book.blocked(now + 11 * min))
        assertEquals(0L, book.lockedUntil(now + 30 * min))
        assertNull(book.primary(now + 31 * min))
    }

    @Test
    fun a_rule_never_outlives_the_cap_even_if_the_plugin_said_so() {
        val book = RuleBook(MemorySnapshotStore())
        val now = 5_000L
        book.replace(snapshot("refuse" to now + 100 * 3_600_000L), now)
        assertTrue(book.lockedUntil(now) <= now + RuleBook.MAX_RULE_MS)
        assertTrue(book.active(now + RuleBook.MAX_RULE_MS).isEmpty())
    }

    @Test
    fun a_new_snapshot_replaces_the_old_one_and_a_missing_one_changes_nothing() {
        val book = RuleBook(MemorySnapshotStore())
        val now = 10_000L
        book.replace(snapshot("review" to now + 60 * min, "task" to now + 30 * min), now)
        assertFalse(book.replace(null, now + 1))
        assertEquals(2, book.active(now + 1).size)
        // 对账通过：插件下一次快照里两条都没了。
        book.replace(snapshot(), now + 2)
        assertTrue(book.active(now + 2).isEmpty())
    }

    @Test
    fun merge_keeps_the_longer_rule_of_a_kind() {
        val book = RuleBook(MemorySnapshotStore())
        val now = 0L + 1
        book.merge(LockRule("refuse", now + 30 * min), now)
        book.merge(LockRule("refuse", now + 10 * min), now)
        assertEquals(now + 30 * min, book.lockedUntil(now))
        book.merge(LockRule("refuse", now + 60 * min), now)
        assertEquals(1, book.active(now).size)
        assertEquals(now + 60 * min, book.lockedUntil(now))
    }

    // ---------------------------------------------------------------- C5 LockPriority

    @Test
    fun night_wins_over_everything_and_default_sits_above_only_unknown() {
        // 第二轮顺序：深夜锁 > 晚间对账 > 晨间 > 任务 > 拒绝 > 互动舱 > 默认锁 > 普通(未知)。
        assertEquals("night", LockPriority.primary(
            listOf(LockRule("refuse", 900), LockRule("task", 500), LockRule("review", 100), LockRule("night", 50)),
        )?.kind)
        assertEquals("review", LockPriority.primary(listOf(LockRule("refuse", 900), LockRule("review", 100), LockRule("session", 999)))?.kind)
        assertEquals("task", LockPriority.primary(listOf(LockRule("refuse", 900), LockRule("task", 500)))?.kind)
        // 默认锁排在普通提醒之上、拒绝之下。
        assertEquals("refuse", LockPriority.primary(listOf(LockRule("default", 900), LockRule("refuse", 1)))?.kind)
        assertEquals("default", LockPriority.primary(listOf(LockRule("default", 10), LockRule("weird", 900)))?.kind)
        assertNull(LockPriority.primary(emptyList()))
    }

    @Test
    fun third_round_kinds_outrank_default_lock() {
        // 第三轮：状态机下发的 evening(90) / followup(80) 必须压在默认锁之上，否则锁屏页会念"默认严格"
        // 而不是"把这一个问题说清楚"。未知 kind 仍排在默认锁之下（上面那条断言）。
        assertEquals("followup", LockPriority.primary(listOf(LockRule("default", 900), LockRule("followup", 10)))?.kind)
        assertEquals("evening", LockPriority.primary(listOf(LockRule("followup", 900), LockRule("evening", 10)))?.kind)
        assertEquals("followup", LockPriority.primary(listOf(LockRule("task", 900), LockRule("followup", 10)))?.kind)
    }

    // ---------------------------------------------------------------- C4 DefianceWindow

    @Test
    fun fifteen_seconds_in_a_game_in_total_is_defiance_even_with_home_in_between() {
        val w = DefianceWindow()
        w.open(0, "busy")
        var t = 0L
        var fired = false
        // 8 s game, 20 s launcher, 8 s game: cumulative 16 s.
        repeat(8) { t += 1_000; fired = fired || w.sample(t, listed = true) }
        repeat(20) { t += 1_000; fired = fired || w.sample(t, listed = false) }
        assertFalse(fired)
        repeat(8) { t += 1_000; fired = fired || w.sample(t, listed = true) }
        assertTrue(fired)
        assertFalse(w.isOpen)
    }

    @Test
    fun the_window_closes_after_five_minutes_without_firing() {
        val w = DefianceWindow()
        w.open(0, "overlay")
        var t = 0L
        var fired = false
        while (t < 5 * min) { t += 1_000; fired = fired || w.sample(t, listed = t % 60_000 < 2_000) }
        assertFalse(fired)
        assertFalse(w.isOpen)
    }

    @Test
    fun a_long_gap_between_samples_does_not_count_as_play() {
        val w = DefianceWindow()
        w.open(0, "overlay")
        w.sample(1_000, listed = true)
        // The main thread slept 60 s: at most one step counts.
        assertFalse(w.sample(61_000, listed = true))
        assertTrue(w.spentMs <= 2_000)
    }

    // ---------------------------------------------------------------- C1 lock vs page

    private fun guard(): InterceptGuard {
        var n = 0
        val g = InterceptGuard(
            MemorySnapshotStore(), InterceptLedgerBook(MemorySnapshotStore()),
            clock = { 0L }, ids = { "evt-" + (++n) },
        )
        g.recover(0)
        return g
    }

    @Test
    fun five_locks_in_a_row_each_end_through_a_different_close_and_the_sixth_still_locks() {
        val g = guard()
        val env = InterceptEnv(dayKey = 1)
        var now = 1_000L
        fun lock(): String {
            val d = g.force("com.game", "王者", env, now, strict = true)
            assertTrue("lock at $now: $d", d is InterceptDecision.Block)
            return (d as InterceptDecision.Block).eventId
        }
        // 1: 紧急退出
        assertTrue(g.onExited(lock(), now)); now += 1_000
        // 2: 急事直达
        assertTrue(g.onDismissed(lock(), "quick:com.android.dialer", now)); now += 1_000
        // 3: 回 Gemini
        assertTrue(g.onDismissed(lock(), "gemini", now)); now += 1_000
        // 4: 破窗
        assertTrue(g.onDismissed(lock(), "breakglass", now)); now += 1_000
        // 5: 系统收走了页面后被别的 App 顶替
        assertTrue(g.onDismissed(lock(), "superseded", now)); now += 1_000
        // 6
        lock()
    }

    @Test
    fun screen_off_does_not_end_the_lock_and_a_stale_one_expires() {
        val g = guard()
        val env = InterceptEnv(dayKey = 1)
        val d = g.force("com.game", "王者", env, 1_000L, strict = true) as InterceptDecision.Block
        // 灭屏：nothing reported. The lock is still the open one: re-showing it means the same eventId.
        assertEquals(d.eventId, g.openEventId())
        assertNull(g.expireStale(1_000L + 29 * min, 30 * min))
        assertEquals(d.eventId, g.expireStale(1_000L + 31 * min, 30 * min))
        assertNull(g.openEventId())
        assertTrue(g.force("com.game", "王者", env, 1_000L + 32 * min, strict = true) is InterceptDecision.Block)
    }
}
