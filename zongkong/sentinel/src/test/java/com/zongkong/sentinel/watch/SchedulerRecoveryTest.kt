package com.zongkong.sentinel.watch

import com.zongkong.sentinel.health.Challenge
import com.zongkong.sentinel.health.ChallengeAck
import com.zongkong.sentinel.health.HealthAction
import com.zongkong.sentinel.health.HealthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 接线级回归：失联 → 恢复 → 重新变绿 这一整圈，走真实的 HealthStore + HealthScheduler。
 *
 * 以前的问题（都能在真机上把哨兵弄成「永远失联」）：
 *  1. 进入恢复后调度器不再发挑战，客户端修好了也拿不到 ack，永远停在失联；
 *  2. 一次恢复被记成两次（调度器和 WatchService 各 +1），预算当场用完；
 *  3. 恢复成功后次数不清零，以后每次故障都直接「预算用完」；
 *  4. 无障碍关着时每 10 秒通知一次上层（记录文件被刷屏）；
 *  5. 没有变化时记录时间不更新，界面误报「哨兵兜底不可用」。
 */
class SchedulerRecoveryTest {

    private var wall = 1_800_000_000_000L
    private var mono = 5_000L

    private class Sink : HealthScheduler.ProbeSink {
        val sent = ArrayList<Pair<String, Challenge>>()
        override fun dispatch(pkg: String, challenge: Challenge, budgetMs: Long) {
            sent += pkg to challenge
        }
        fun lastFor(pkg: String): Challenge? = sent.lastOrNull { it.first == pkg }?.second
    }

    private val pkg = HealthStore.ZONGKONG

    private fun setup(): Triple<HealthStore, Sink, Pair<HealthScheduler, MutableList<Pair<HealthAction, String>>>> {
        val hs = HealthStore(Files.createTempDirectory("sched-rec").toFile())
        hs.register(pkg, "1.0", 1, "i1", "sha", 0, nowWall = wall, nowMono = mono)
        val sink = Sink()
        val actions = ArrayList<Pair<HealthAction, String>>()
        val sch = HealthScheduler(
            store = hs, sink = sink, mono = { mono }, wall = { wall },
            activeEnforcement = { true },
            onAction = { p, a, r -> if (p == pkg) actions += a to r },
        )
        return Triple(hs, sink, sch to actions)
    }

    private fun ack(sch: HealthScheduler, ch: Challenge, instance: String) {
        sch.onAck(pkg, ch, ChallengeAck(2, pkg, instance, ch.nonce, ch.seq, mono), mono + 5)
    }

    /** 推进一轮：每发出一个挑战就让它失败（客户端不回）。 */
    private fun failingTick(sch: HealthScheduler, sink: Sink): HealthScheduler.Tick {
        mono += HealthScheduler.TICK_MS
        wall += HealthScheduler.TICK_MS
        val before = sink.sent.size
        val t = sch.tick()
        sink.sent.drop(before).filter { it.first == pkg }.forEach { sch.onProbeFailure(pkg, "调不通") }
        return t
    }

    @Test
    fun `client that recovers after a rebind turns healthy again and gets its budget back`() {
        val (hs, sink, pair) = setup()
        val (sch, actions) = pair

        // 先健康一次
        sch.tick()
        ack(sch, sink.lastFor(pkg)!!, "i1")
        assertEquals(HealthStatus.HEALTHY, sch.tick().statuses[pkg])

        // 客户端不回了：一直推进，直到进入恢复
        var guard = 0
        while (actions.none { it.first == HealthAction.START_RECOVERY } && guard++ < 20) failingTick(sch, sink)
        assertTrue("应当进入有限恢复", actions.any { it.first == HealthAction.START_RECOVERY })
        assertEquals("一次恢复只记一次", 1, hs.get(pkg).recoveriesTried)

        // 恢复中：调度器仍要继续发挑战（否则永远拿不到「恢复后的 ack」）
        val sentBefore = sink.sent.size
        mono += HealthScheduler.TICK_MS
        val t = sch.tick()
        assertEquals(HealthStatus.RECOVERING, t.statuses[pkg])
        assertTrue("恢复中也要发挑战", sink.sent.size > sentBefore)

        // 客户端被重新挂上：新实例注册，然后真的回了 ack
        hs.register(pkg, "1.0", 1, "i2", "sha", 0, nowWall = wall, nowMono = mono)
        sch.onNewInstance(pkg, "i2")
        mono += HealthScheduler.TICK_MS
        sch.tick()
        ack(sch, sink.lastFor(pkg)!!, "i2")

        assertEquals(HealthStatus.HEALTHY, sch.tick().statuses[pkg])
        val rec = hs.get(pkg)
        assertEquals("恢复成功后预算清零", 0, rec.recoveriesTried)
        assertEquals(0L, rec.recoveringSinceMono)
        assertEquals(1, rec.stats.recoverySuccesses)
        assertEquals(1, rec.stats.outageCount)
    }

    @Test
    fun `escalated client is still probed and can come back`() {
        val (hs, sink, pair) = setup()
        val (sch, actions) = pair
        sch.tick()
        ack(sch, sink.lastFor(pkg)!!, "i1")

        // 一直不回，走到「恢复预算用完」
        repeat(15) { failingTick(sch, sink) }
        assertTrue(actions.any { it.first == HealthAction.ESCALATE })
        assertEquals("升级只通知一次，不每轮刷", 1, actions.count { it.first == HealthAction.ESCALATE })

        // 之后客户端自己好了（用户手动去无障碍里重开）：新实例 + 真 ack → 变回健康
        hs.register(pkg, "1.0", 1, "i3", "sha", 0, nowWall = wall, nowMono = mono)
        sch.onNewInstance(pkg, "i3")
        mono += HealthScheduler.TICK_MS
        sch.tick()
        ack(sch, sink.lastFor(pkg)!!, "i3")
        assertEquals(HealthStatus.HEALTHY, sch.tick().statuses[pkg])
    }

    @Test
    fun `disabled accessibility is reported once, not every tick`() {
        val (hs, _, pair) = setup()
        val (sch, actions) = pair
        hs.observeSystem(pkg, installed = true, permissionEnabled = false, nowWall = wall)
        repeat(10) {
            mono += HealthScheduler.TICK_MS
            assertEquals(HealthStatus.DISABLED, sch.tick().statuses[pkg])
        }
        assertEquals(1, actions.count { it.first == HealthAction.NOTIFY })
        // 重新开启再关掉：是一次新的变化，要再提醒一次
        hs.observeSystem(pkg, installed = true, permissionEnabled = true, nowWall = wall)
        sch.tick()
        hs.observeSystem(pkg, installed = true, permissionEnabled = false, nowWall = wall)
        sch.tick()
        assertEquals(2, actions.count { it.first == HealthAction.NOTIFY })
    }

    @Test
    fun `flush refreshes the heartbeat even when nothing changed`() {
        val dir = Files.createTempDirectory("sched-flush").toFile()
        val hs = HealthStore(dir)
        hs.flush(wall)
        val later = wall + 5 * 60_000
        hs.flush(later)
        // 换一个实例从磁盘读：:watch 还在推进，界面就该看到新鲜记录
        val snap = HealthStore(dir).snapshot(later)
        assertEquals(later, snap.updatedAtWall)
        assertTrue(StatusPage.watchFresh(snap, later + 1_000))
    }
}
