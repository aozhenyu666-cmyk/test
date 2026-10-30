package com.zongkong.sentinel.watch

import com.zongkong.sentinel.health.HealthStatus
import com.zongkong.sentinel.health.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 哨兵 v2 的纯逻辑单测（本机无真机，能跑的就是这些 + 共享内核）。
 *
 * 覆盖：策略持久化（§4）、多客户端独立记录（§7/T04）、紧急暂停与正常解除分离（§6/T16）、
 * 事件通道判定（T03/T13）、调度器状态机（T02/T14）。
 */
class SentinelWatchV2Test {

    // ---------- 测试脚手架 ----------

    private var wallV = 1_800_000_000_000L
    private var monoV = 5_000L

    private companion object {
        /** 测试里所有窗口都以这个墙钟为基准，避免和 wallV 的绝对值脱钩。 */
        const val WALL_BASE = 1_800_000_000_000L
    }

    private fun tmpDir(): File = Files.createTempDirectory("sentinel-v2").toFile()

    private fun policy(
        id: String = "p1",
        rev: Int = 1,
        issuer: String = "zongkong",
        starts: Long = WALL_BASE,
        ends: Long = WALL_BASE + 3_600_000,
        lockAt: Long? = null,
        enabled: Boolean = true,
        required: Set<String> = setOf(HealthStore.ZONGKONG, HealthStore.YISHOU, HealthStore.PET),
    ) = Policy(
        policyId = id, revision = rev, issuer = issuer, requiredApps = required,
        startsAtWall = starts, endsAtWall = ends, lockAtWall = lockAt, enabled = enabled,
    )

    // ---------- §4 策略持久化：先落盘再监管，原子写 ----------

    @Test
    fun `syncPolicy persists and bumps revision`() {
        val dir = tmpDir()
        val store = PolicyStore(dir)
        val body = """
            {"schemaVersion":2,"policies":[{"policyId":"p1","revision":1,"issuer":"zongkong",
            "requiredApps":["com.zongkong.app","com.yishou.app","cn.companion.pet"],
            "startsAtWall":0,"endsAtWall":1000,"failureAction":"LOCK","enabled":true}]}
        """.trimIndent()

        val r = store.applySyncPolicy(1_000, "zongkong", body)
        assertTrue(r.ok)
        assertEquals(1, r.acceptedRevision)

        // 真落盘了：新实例（模拟进程重启）能读回来。
        val reread = PolicyStore(dir).load()
        assertNull(reread.error)
        assertEquals(1, reread.doc.policies.size)
        assertEquals(1, reread.doc.revision)
        assertTrue(reread.doc.policies[0].effective(500))
        // 没有留下 .tmp 半截文件。
        assertFalse(File(dir, "policies.json.tmp").exists())
    }

    @Test
    fun `a client cannot write another issuer's policy`() {
        val store = PolicyStore(tmpDir())
        val body = """{"policies":[{"policyId":"x","revision":1,"issuer":"pet",
            "requiredApps":[],"startsAtWall":0,"endsAtWall":100,"enabled":true}]}"""
        val r = store.applySyncPolicy(0, "zongkong", body)
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("不能写"))
        // 失败时磁盘上仍然没有这个 policy。
        assertTrue(PolicyStore(store.path().parentFile!!).load().doc.policies.isEmpty())
    }

    @Test
    fun `stale revision is ignored but not accepted as new`() {
        val store = PolicyStore(tmpDir())
        val v2 = """{"policies":[{"policyId":"p1","revision":2,"issuer":"zongkong",
            "requiredApps":[],"startsAtWall":0,"endsAtWall":100,"enabled":true}]}"""
        assertEquals(1, store.applySyncPolicy(10, "zongkong", v2).acceptedRevision)
        // 迟到的旧包（rev 1）不能覆盖 rev 2。
        val v1 = v2.replace("\"revision\":2", "\"revision\":1")
        val r = store.applySyncPolicy(20, "zongkong", v1)
        assertTrue(r.ok)
        assertEquals(1, r.acceptedRevision) // revision 没有前进
        assertEquals(2, store.load().doc.policies[0].revision) // 磁盘上还是 rev 2
    }

    @Test
    fun `broken policy file is reported not silently wiped`() {
        val dir = tmpDir()
        File(dir, "policies.json").writeText("{ 这不是 json")
        val res = PolicyStore(dir).load()
        assertNotNull(res.error)
        // 文件还在：给人看，不悄悄清零。
        assertTrue(File(dir, "policies.json").exists())
    }

    @Test
    fun `emergency stop persists STOPPED and lockDue skips it`() {
        val store = PolicyStore(tmpDir())
        val body = """{"policies":[{"policyId":"p1","revision":1,"issuer":"sentinel",
            "requiredApps":[],"startsAtWall":0,"endsAtWall":100000,"lockAtWall":5000,"enabled":true}]}"""
        assertTrue(store.applySyncPolicy(0, "sentinel", body).ok)
        assertTrue(store.lockDue(6_000).isNotEmpty())
        assertTrue(store.setEmergency(7_000, null, PolicyStore.EMERGENCY_STOPPED).ok)
        // 紧急暂停后不再产生到期锁屏，重读文件也仍是 STOPPED。
        assertTrue(store.lockDue(8_000).isEmpty())
        assertEquals(PolicyStore.EMERGENCY_STOPPED, store.load().doc.policies[0].emergencyState)
    }

    @Test
    fun `expired policy never effective even if client keeps talking`() {
        val store = PolicyStore(tmpDir())
        val body = """{"policies":[{"policyId":"p1","revision":1,"issuer":"yishou",
            "requiredApps":["com.yishou.app"],"startsAtWall":0,"endsAtWall":1000,"enabled":true}]}"""
        store.applySyncPolicy(0, "yishou", body)
        assertTrue(store.load().doc.activeAt(999).isNotEmpty())
        assertTrue(store.load().doc.activeAt(1000).isEmpty())
        assertTrue(store.load().doc.requiredAppsAt(1000).isEmpty())
    }

    // ---------- §7 多客户端独立记录 ----------

    private fun registered(dir: File, pkg: String, instance: String = "i-1"): HealthStore {
        val hs = HealthStore(dir)
        hs.register(pkg, "1.0", 1, instance, "sha", 1, nowWall = wallV, nowMono = monoV)
        return hs
    }

    @Test
    fun `one client not answering does not touch the other two`() {
        val dir = tmpDir()
        val hs = HealthStore(dir)
        for (pkg in HealthStore.CLIENTS) {
            hs.register(pkg, "1.0", 1, "i1", "sha", 1, nowWall = wallV, nowMono = monoV)
        }
        // 只有总控在途，另外两个不受影响。
        assertTrue(hs.beginProbe(HealthStore.ZONGKONG, wallV, monoV) > 0)
        assertEquals(1, hs.get(HealthStore.ZONGKONG).inFlight)
        assertEquals(0, hs.get(HealthStore.YISHOU).inFlight)
        assertEquals(0, hs.get(HealthStore.PET).inFlight)

        // 在途上限 1：同一客户端不会堆积请求。
        assertEquals(0L, hs.beginProbe(HealthStore.ZONGKONG, wallV, monoV))

        // 判定「卡死」后，仍然只影响它自己。
        hs.markProbeWedged(HealthStore.ZONGKONG, "挑战超时", wallV)
        assertEquals(1, hs.get(HealthStore.ZONGKONG).consecutiveFailures)
        assertEquals(0, hs.get(HealthStore.YISHOU).consecutiveFailures)
        assertEquals(HealthStore.EVENT_PENDING, hs.get(HealthStore.PET).eventChannel)
    }

    @Test
    fun `records survive process restart`() {
        val dir = tmpDir()
        val hs = registered(dir, HealthStore.ZONGKONG)
        hs.onAckAccepted(
            HealthStore.ZONGKONG,
            HealthStore.ClientRecordData("i1", 7, 9_000, 3, "OBSERVED_LOCKED", 8_000, 42, 8_500),
            wallV,
        )
        hs.flush(wallV)

        val again = HealthStore(dir)
        val rec = again.get(HealthStore.ZONGKONG, wallV)
        assertEquals(7L, rec.lastAckSeq)
        assertEquals(3, rec.ruleRevision)
        assertEquals("OBSERVED_LOCKED", rec.lastReceiptState)
        assertEquals(42L, rec.eventSeq)
        assertTrue(rec.registered)
    }

    @Test
    fun `new instance clears wedged flag but keeps ack evidence`() {
        val dir = tmpDir()
        val hs = registered(dir, HealthStore.PET, "old")
        hs.onAckAccepted(HealthStore.PET, HealthStore.ClientRecordData("old", 1, 1_000, 0, "", 0, 0, 0), wallV)
        hs.markProbeWedged(HealthStore.PET, "挑战超时", wallV)
        assertTrue(hs.get(HealthStore.PET).wedged)

        hs.register(HealthStore.PET, "1.0", 1, "new", "sha", 0, nowWall = wallV, nowMono = monoV)
        val rec = hs.get(HealthStore.PET)
        assertEquals("new", rec.instanceId)
        assertFalse(rec.wedged)
        // 旧 ack 仍在：新实例还没 ack，评测器会继续判 UNKNOWN/SUSPECT，不会突然变绿（T11）。
        assertEquals(1L, rec.lastAckSeq)
    }

    // ---------- §6 紧急暂停（与正常解除分开） ----------

    @Test
    fun `emergency pause records who confirmed and never claims all stopped`() {
        val targets = listOf(HealthStore.ZONGKONG, HealthStore.YISHOU, HealthStore.PET)
        val rec = Emergency.trigger(
            nowWall = 1_000, nowMono = 2_000, reason = "现在就停",
            targets = targets, reached = listOf(HealthStore.ZONGKONG),
        )
        assertTrue(rec.confirmed[HealthStore.ZONGKONG] == true)
        assertFalse(rec.confirmed[HealthStore.YISHOU] == true)
        assertFalse(rec.allConfirmed(targets))
        val text = Emergency.describe(rec, targets)
        assertTrue(text.contains("暂停未确认"))
        assertTrue(text.contains("现在就停"))
    }

    @Test
    fun `emergency pause is local and immediate - no word count, no cooling`() {
        // 不传任何分析文本、不需要等待：一调用就有记录。
        val rec = Emergency.trigger(nowWall = 5L, nowMono = 6L, reason = "")
        assertEquals(5L, rec.atWall)
        assertTrue(rec.reason.contains("未填原因"))
    }

    @Test
    fun `emergency pause ends with the window`() {
        val p = policy(starts = 0, ends = 10_000)
        val rec = Emergency.trigger(nowWall = 1, nowMono = 1, reason = "x", policyId = null)
        assertTrue(Emergency.activeFor(rec, 5_000, p))
        assertFalse(Emergency.activeFor(rec, 10_000, p))
        assertNull(HealthStore(tmpDir()).snapshot(0).emergency)
    }

    // ---------- T03/T13 事件通道 ----------

    @Test
    fun `no comparable event means pending not abnormal`() {
        val (v, why) = EventChannel.judge(
            EventChannel.Context(sentinelSawEventAfter = false, clientEventAtMono = 0),
        )
        assertEquals(EventChannel.Verdict.PENDING, v)
        assertTrue(why.contains("无法区分") || why.contains("没有可对照"))
    }

    @Test
    fun `suppressed context is never abnormal`() {
        val (v, _) = EventChannel.judge(
            EventChannel.Context(
                sentinelSawEventAfter = true, clientEventAtMono = 1_000,
                compareRefMono = 5_000, suppressed = true, sinceCompareMs = 600_000,
            ),
        )
        assertEquals(EventChannel.Verdict.PENDING, v)
    }

    @Test
    fun `client event progress behind reference is abnormal after grace`() {
        val (v, why) = EventChannel.judge(
            EventChannel.Context(
                sentinelSawEventAfter = true, clientEventAtMono = 1_000,
                compareRefMono = 5_000, sinceCompareMs = 60_000, graceMs = 20_000,
            ),
        )
        assertEquals(EventChannel.Verdict.ABNORMAL, v)
        assertTrue(why.contains("没有前进"))
    }

    @Test
    fun `client event progress caught up is verified`() {
        val (v, _) = EventChannel.judge(
            EventChannel.Context(
                sentinelSawEventAfter = true, clientEventAtMono = 5_000,
                compareRefMono = 5_000, sinceCompareMs = 60_000,
            ),
        )
        assertEquals(EventChannel.Verdict.VERIFIED, v)
    }

    @Test
    fun `event progress file round trips`() {
        val dir = tmpDir()
        val f = EventProgressFile(dir)
        val st = EventProgressFile.State(seq = 0, instanceId = "i1")
        assertTrue(f.append(st, EventProgressFile.Event(1, HealthStore.ZONGKONG, 100, 200)))
        val back = f.read()!!
        assertEquals(1L, back.seq)
        assertEquals(HealthStore.ZONGKONG, back.recent.last().pkg)
    }

    // ---------- T02/T14 调度器：只有真 dispatcherAck 才算健康 ----------

    private class FakeSink : HealthScheduler.ProbeSink {
        val sent = ArrayList<Pair<String, com.zongkong.sentinel.health.Challenge>>()
        override fun dispatch(pkg: String, challenge: com.zongkong.sentinel.health.Challenge, budgetMs: Long) {
            sent += pkg to challenge
        }
    }

    private fun schedulerWith(hs: HealthStore, sink: FakeSink) = HealthScheduler(
        store = hs, sink = sink,
        mono = { monoV }, wall = { wallV },
        activeEnforcement = { true },
    )

    @Test
    fun `no ack yet is never healthy and a probe is dispatched`() {
        val dir = tmpDir()
        val hs = registered(dir, HealthStore.ZONGKONG)
        val sink = FakeSink()
        val sch = schedulerWith(hs, sink)
        val tick = sch.tick()
        assertEquals(HealthStatus.UNKNOWN, tick.statuses[HealthStore.ZONGKONG])
        assertTrue(tick.dispatched.contains(HealthStore.ZONGKONG))
        assertEquals(1, sink.sent.size)
    }

    @Test
    fun `real ack makes it healthy, older instance ack is rejected`() {
        val dir = tmpDir()
        val hs = registered(dir, HealthStore.ZONGKONG, "i1")
        val sink = FakeSink()
        val sch = schedulerWith(hs, sink)
        sch.tick()
        val (pkg, ch) = sink.sent.last()
        val good = com.zongkong.sentinel.health.ChallengeAck(
            protocolVersion = 2, clientPkg = pkg, instanceId = "i1",
            nonce = ch.nonce, seq = ch.seq, handledAtClientMono = monoV,
            ruleRevision = 1, lastEventSeq = 9, lastEventAtClientMono = monoV,
        )
        sch.onAck(pkg, ch, good, monoV + 100)
        assertEquals(HealthStatus.HEALTHY, sch.tick().statuses[pkg])

        // 客户端重建了连接（新 instanceId）：校验基线必须换新。
        hs.register(pkg, "1.0", 1, "i2", "sha", 1, nowWall = wallV, nowMono = monoV)
        sch.onNewInstance(pkg, "i2")
        val ch2 = com.zongkong.sentinel.health.Challenge(
            nonce = "n2", seq = 10, issuedAtMono = monoV + 100, expectedInstanceId = "i2",
        )

        // 旧实例 i1 的回应必须被拒，不能刷新健康（T14）。
        sch.onAck(pkg, ch2, good.copy(instanceId = "i1", nonce = "n2", seq = 10), monoV + 200)
        assertTrue(hs.get(pkg).consecutiveFailures > 0)

        // 新实例 i2 的真 ack 才能重新变绿。
        sch.onAck(pkg, ch2, good.copy(instanceId = "i2", nonce = "n2", seq = 10), monoV + 300)
        assertEquals(HealthStatus.HEALTHY, sch.tick().statuses[pkg])
    }

    @Test
    fun `duplicate seq is rejected`() {
        val dir = tmpDir()
        val hs = registered(dir, HealthStore.ZONGKONG, "i1")
        val sink = FakeSink()
        val sch = schedulerWith(hs, sink)
        sch.tick()
        val (pkg, ch) = sink.sent.last()
        val ack = com.zongkong.sentinel.health.ChallengeAck(2, pkg, "i1", ch.nonce, ch.seq, monoV)
        sch.onAck(pkg, ch, ack, monoV)
        assertEquals(0, hs.get(pkg).consecutiveFailures)
        // 同一个 seq 再来一次：拒。
        sch.onAck(pkg, ch, ack, monoV + 10)
        assertEquals(1, hs.get(pkg).consecutiveFailures)
    }

    @Test
    fun `wedged probe eventually becomes unresponsive and asks for lock`() {
        val dir = tmpDir()
        val hs = registered(dir, HealthStore.ZONGKONG, "i1")
        val sink = FakeSink()
        val sch = schedulerWith(hs, sink)
        sch.tick() // 发第一次探测（永不应答）

        // 超过 5s 预算：判超时（不新建线程、不阻塞其他客户端）。
        monoV += 6_000
        val t1 = sch.tick()
        assertTrue(t1.timedOut.contains(HealthStore.ZONGKONG))
        assertEquals(1, hs.get(HealthStore.ZONGKONG).consecutiveFailures)

        // 再攒一次失败 → 进入 UNRESPONSIVE 并要求锁屏。
        monoV += 6_000
        val t2 = sch.tick()
        assertEquals(HealthStatus.UNRESPONSIVE, t2.statuses[HealthStore.ZONGKONG])
        assertTrue(t2.needLock.any { it.first == HealthStore.ZONGKONG })

        // 其他客户端不受影响。
        assertEquals(HealthStatus.UNREGISTERED, t2.statuses[HealthStore.YISHOU])
    }

    @Test
    fun `suspended enforcement never escalates to a lock`() {
        val dir = tmpDir()
        val hs = registered(dir, HealthStore.ZONGKONG, "i1")
        val sink = FakeSink()
        var active = false
        val sch = HealthScheduler(hs, sink, { monoV }, { wallV }, activeEnforcement = { active })
        sch.tick()
        monoV += 6_000; sch.tick()
        monoV += 6_000; val t = sch.tick()
        // 确认失联，但此刻不可执行（息屏 / 无生效窗口）：只记录，不锁（T13）。
        assertEquals(HealthStatus.UNRESPONSIVE, t.statuses[HealthStore.ZONGKONG])
        assertTrue(t.needLock.isEmpty())

        // 恢复预算用完后进入 ESCALATE；此时若已在活跃监管窗口，才允许锁屏。
        monoV += 31_000
        val escalated = sch.tick()
        assertEquals(HealthStatus.UNRESPONSIVE, escalated.statuses[HealthStore.ZONGKONG])
        assertTrue(escalated.needLock.isEmpty()) // 仍然不可执行 → 仍然不锁
        active = true
        monoV += 6_000
        assertTrue(sch.tick().needLock.any { it.first == HealthStore.ZONGKONG })
    }

    // ---------- §10 统计页口径 ----------

    @Test
    fun `status page shows four rows and honest overall state`() {
        val dir = tmpDir()
        val hs = registered(dir, HealthStore.ZONGKONG)
        val page = StatusPage.build(
            snapshot = hs.snapshot(wallV),
            policies = emptyList(),
            nowWall = wallV,
            nowMono = monoV,
        )
        assertEquals(4, page.rows.size)
        assertEquals(listOf("总控", "一手", "同桌", "哨兵自己"), page.rows.map { it.title })
        // 没有生效窗口 → 四态里的第四态。
        assertEquals("没有生效中的监管窗口", page.overallText)
        assertFalse(page.rows.any { it.healthy }) // 一条真 ack 都没有：谁都不许绿
        assertEquals("未接入", page.rows[1].statusText)
        assertTrue(page.statsText.contains("数据缺失"))
    }

    @Test
    fun `status page reports partial failure when a required app is not healthy`() {
        val dir = tmpDir()
        val hs = HealthStore(dir)
        hs.register(HealthStore.ZONGKONG, "1.0", 1, "i1", "sha", 0, wallV, monoV)
        // 让哨兵自己看起来「已接入且无障碍开启」，否则四态会先报哨兵不可用。
        hs.register(HealthStore.SELF, "1.2", 3, "s1", "sha", 0, wallV, monoV)
        hs.observeSystem(HealthStore.SELF, installed = true, permissionEnabled = true, nowWall = wallV)

        val page = StatusPage.build(hs.snapshot(wallV), listOf(policy()), wallV, monoV)
        assertEquals("部分监管失效", page.overallText)
    }

    // ---------- T04：一个客户端卡住，不阻塞其他客户端 ----------

    @Test
    fun `one wedged client never blocks the other two`() {
        val dir = tmpDir()
        val hs = HealthStore(dir)
        for (pkg in HealthStore.CLIENTS) {
            hs.register(pkg, "1.0", 1, "i1", "sha", 0, nowWall = wallV, nowMono = monoV)
        }
        val sink = FakeSink()
        val sch = schedulerWith(hs, sink)

        // 三轮：总控永远不回；一手、同桌每轮都用各自的新挑战真回。
        var seq = 0L
        repeat(3) { round ->
            sch.tick()
            for (pkg in listOf(HealthStore.YISHOU, HealthStore.PET)) {
                seq += 1
                val ch = com.zongkong.sentinel.health.Challenge(
                    nonce = "n-$pkg-$round", seq = seq, issuedAtMono = monoV, expectedInstanceId = "i1",
                )
                sch.onAck(
                    pkg,
                    ch,
                    com.zongkong.sentinel.health.ChallengeAck(
                        protocolVersion = 2, clientPkg = pkg, instanceId = "i1",
                        nonce = ch.nonce, seq = ch.seq, handledAtClientMono = monoV,
                    ),
                    monoV + 10,
                )
            }
            if (round < 2) monoV += 6_000
        }

        val tick = sch.tick()
        // 一手 / 同桌仍是健康：它们的探测、计时、锁屏判定都没被总控拖住（T04 的核心）。
        assertEquals(HealthStatus.HEALTHY, tick.statuses[HealthStore.YISHOU])
        assertEquals(HealthStatus.HEALTHY, tick.statuses[HealthStore.PET])
        // 总控独自掉了（失联或已进入有限恢复），且掉线期间只有它被列入锁屏事件。
        assertFalse(tick.statuses[HealthStore.ZONGKONG] == HealthStatus.HEALTHY)
        assertFalse(tick.needLock.any { it.first == HealthStore.YISHOU })
        assertFalse(tick.needLock.any { it.first == HealthStore.PET })

        // 继续推进：总控最终一定会走到「确认失联 → 交给管理员通道锁屏」，而另外两个始终不锁。
        var lockedForZongkong = tick.needLock.any { it.first == HealthStore.ZONGKONG }
        var lockedForOthers = tick.needLock.any { it.first != HealthStore.ZONGKONG }
        repeat(8) {
            monoV += HealthScheduler.TICK_MS
            val next = sch.tick()
            lockedForZongkong = lockedForZongkong || next.needLock.any { it.first == HealthStore.ZONGKONG }
            lockedForOthers = lockedForOthers || next.needLock.any { it.first != HealthStore.ZONGKONG }
            // 另外两个持续健康（每轮都真回）：不会被卡死的总控连带判失联。
            for (pkg in listOf(HealthStore.YISHOU, HealthStore.PET)) {
                seq += 1
                val ch = com.zongkong.sentinel.health.Challenge(
                    nonce = "keep-$pkg-$seq", seq = seq, issuedAtMono = monoV, expectedInstanceId = "i1",
                )
                sch.onAck(
                    pkg,
                    ch,
                    com.zongkong.sentinel.health.ChallengeAck(2, pkg, "i1", ch.nonce, ch.seq, monoV),
                    monoV + 10,
                )
            }
            assertEquals(HealthStatus.HEALTHY, sch.tick().statuses[HealthStore.YISHOU])
            assertEquals(HealthStatus.HEALTHY, sch.tick().statuses[HealthStore.PET])
        }
        assertTrue("总控失联后应交给管理员锁屏通道", lockedForZongkong)
        assertFalse("卡死的客户端不能连带触发别人的锁屏", lockedForOthers)
    }

    @Test
    fun `wedged client is retried with a new probe instead of being pinned forever`() {
        val dir = tmpDir()
        val hs = registered(dir, HealthStore.ZONGKONG, "i1")
        val sink = FakeSink()
        val sch = schedulerWith(hs, sink)
        sch.tick()
        val probesBefore = sink.sent.count { it.first == HealthStore.ZONGKONG }
        val seqBefore = hs.get(HealthStore.ZONGKONG).probeSeq

        monoV += 6_000
        val timedOutTick = sch.tick() // 判超时并释放该在途槽位
        assertTrue(timedOutTick.timedOut.contains(HealthStore.ZONGKONG))
        // 关键：这个客户端没有被永久钉死——探测序号继续前进（还能发新的探测）。
        assertTrue(hs.get(HealthStore.ZONGKONG).probeSeq > seqBefore)
        assertTrue(sink.sent.count { it.first == HealthStore.ZONGKONG } > probesBefore)
        // 同一轮只记一次失败，不按 tick 膨胀计数。
        assertEquals(1, hs.get(HealthStore.ZONGKONG).consecutiveFailures)
    }

    // ---------- T05：调度不在无障碍 Handler 上 ----------

    @Test
    fun `scheduler needs no Looper and runs on a plain thread`() {
        // JVM 单测里根本没有 android.os.Looper / Handler。能在普通线程上完整跑完一轮，
        // 就是「调度不依赖无障碍主线程」的机器级证据（无障碍冻结时 :watch 照样推进）。
        val dir = tmpDir()
        val hs = registered(dir, HealthStore.ZONGKONG, "i1")
        val sink = FakeSink()
        val sch = schedulerWith(hs, sink)
        var status: HealthStatus? = null
        var error: Throwable? = null
        val t = Thread {
            try { status = sch.tick().statuses[HealthStore.ZONGKONG] } catch (e: Throwable) { error = e }
        }
        t.start()
        t.join(5_000)
        assertNull(error)
        assertEquals(HealthStatus.UNKNOWN, status) // 还没 ack：UNKNOWN，不是 HEALTHY（T02）
    }

    // ---------- T03：a11y 进程写、:watch 进程读 ----------

    @Test
    fun `event progress written by one process is readable by another`() {
        val dir = tmpDir()
        // 模拟 a11y 进程写。
        val a11y = EventProgressFile(dir)
        var st = EventProgressFile.State(instanceId = "a11y-1")
        assertTrue(a11y.append(st, EventProgressFile.Event(1, "com.android.launcher", 1_000, 2_000)))
        st = a11y.read()!!
        assertTrue(a11y.append(st, EventProgressFile.Event(2, HealthStore.ZONGKONG, 3_000, 4_000)))

        // 模拟 :watch 进程读（新实例 = 另一个进程里的对象）。
        val watch = EventProgressFile(dir)
        val seen = watch.read()!!
        assertEquals(2L, seen.seq)
        assertEquals(3_000L, seen.atMono)
        assertEquals(2, seen.recent.size)
        assertEquals("a11y-1", seen.instanceId)
    }

    @Test
    fun `watch uses event progress to separate frozen dispatcher from silent event channel`() {
        // 执行线程还回话（dispatcherHealthy = true）但事件进度落后 → 异常（T03 的正确结论）。
        val (frozenChannel, _) = EventChannel.judge(
            EventChannel.Context(
                sentinelSawEventAfter = true, clientEventAtMono = 1_000,
                compareRefMono = 9_000, sinceCompareMs = 30_000, graceMs = 20_000,
                dispatcherHealthy = true,
            ),
        )
        assertEquals(EventChannel.Verdict.ABNORMAL, frozenChannel)

        // 执行线程本身就不回话 → 先不说事件通道的结论（别把两件事混在一起）。
        val (unknown, why) = EventChannel.judge(
            EventChannel.Context(
                sentinelSawEventAfter = true, clientEventAtMono = 1_000,
                compareRefMono = 9_000, sinceCompareMs = 30_000,
                dispatcherHealthy = false,
            ),
        )
        assertEquals(EventChannel.Verdict.PENDING, unknown)
        assertTrue(why.contains("执行线程"))
    }

    // ---------- §10 首页四态：哨兵自己那行也要说真话 ----------

    @Test
    fun `sentinel row is not green when watch has no fresh record`() {
        val page = StatusPage.build(
            snapshot = HealthStore.Snapshot(), // 没有任何记录
            policies = listOf(policy()),
            nowWall = wallV,
            nowMono = monoV,
            selfAvailable = false,
            selfAccessibilityEnabled = true,
        )
        val self = page.rows.last { it.pkg == HealthStore.SELF }
        assertFalse(self.healthy)
        assertTrue(self.statusText.contains("兜底不可用"))
        assertEquals("哨兵兜底不可用", page.overallText)
    }

    @Test
    fun `sentinel row admits missing accessibility even when watch is alive`() {
        val dir = tmpDir()
        val hs = HealthStore(dir)
        hs.register(HealthStore.SELF, "1.2", 3, "s1", "sha", 0, wallV, monoV)
        hs.observeSystem(HealthStore.SELF, installed = true, permissionEnabled = false, nowWall = wallV)
        hs.flush(wallV)
        val page = StatusPage.build(
            hs.snapshot(wallV), listOf(policy()), wallV, monoV,
            selfAvailable = true, selfAccessibilityEnabled = false,
        )
        val self = page.rows.last { it.pkg == HealthStore.SELF }
        assertFalse(self.healthy)
        assertTrue(self.statusText.contains("无障碍没开"))
    }

    // ---------- F-01：事件通道必须走**真实接线**，不能只测 judge() ----------
    //
    // 教训：旧单测直接调 EventChannel.judge()，绕开了 tick()/refreshSystemFacts() 的真实接线，
    // 于是「判据对、接线错」也能全绿。以下用例一律走 EventChannelWiring.refresh（:watch 的真实路径），
    // 用临时目录里的真 events.json 文件，不用手搓 Context。

    /** 造一个「a11y 真的收到了窗口事件」的 events.json（真写盘）。 */
    private fun writeRealEvents(dir: File, seq: Long, atMono: Long): EventProgressFile {
        val f = EventProgressFile(dir)
        var st = EventProgressFile.State(instanceId = "a11y-1")
        var i = 0L
        while (i < seq) {
            i += 1
            check(f.append(st, EventProgressFile.Event(i, HealthStore.ZONGKONG, atMono, atMono)))
            st = f.read()!!
        }
        return f
    }

    /** 一个「执行线程确实在回话」的已注册客户端。 */
    private fun clientWithHealthyDispatcher(dir: File, pkg: String, eventAtMono: Long): HealthStore {
        val hs = HealthStore(dir)
        hs.register(pkg, "1.0", 1, "i1", "sha", 0, nowWall = wallV, nowMono = monoV)
        hs.onAckAccepted(
            pkg,
            HealthStore.ClientRecordData(
                instanceId = "i1", seq = 1, receivedAtMono = monoV,
                ruleRevision = 1, lastReceiptState = "", lastReceiptAtMono = 0,
                eventSeq = 1, eventAtMono = eventAtMono,
            ),
            wallV,
        )
        return hs
    }

    @Test
    fun `wiring produces ABNORMAL when client event progress stalls past grace`() {
        val dir = tmpDir()
        // 哨兵自己在 mono=10_000 收到了真实窗口事件。
        //
        // 注意这里的数据为什么从 1_000 改成 10_000（P0-2）：
        // 客户端与哨兵是两个独立的无障碍服务，同一次窗口切换由系统分别派发，
        // 两端打点天然差几十到几百毫秒。旧数据用「客户端 500、参照 1_000」这种
        // **500ms 的差**来模拟「卡死」——它和白噪声落在同一个量级，
        // 测的其实是「抖动算不算故障」，而不是「卡死算不算故障」。
        // 现在客户端停在 500、参照在 10_000，落后 9.5s，远超 2s 抖动容差，
        // 才是这条用例名字真正要表达的场景。
        val events = writeRealEvents(dir, seq = 1, atMono = 10_000)
        val hs = clientWithHealthyDispatcher(dir, HealthStore.ZONGKONG, eventAtMono = 500)

        // 参照事件已过 60s，远超 20s grace。
        val out = EventChannelWiring.refresh(
            store = hs,
            progress = events.read(),
            nowWall = wallV,
            nowMono = 61_000,
            suppressed = false,
            graceMs = 20_000,
        )

        assertEquals(EventChannel.Verdict.ABNORMAL, out[HealthStore.ZONGKONG]!!.first)
        // 关键：**落盘**的也是异常，不是「待验证」。
        assertEquals(HealthStore.EVENT_ABNORMAL, hs.get(HealthStore.ZONGKONG).eventChannel)
    }

    @Test
    fun `scheduler tick does not overwrite the wiring verdict`() {
        // 这条是 F-01 的回归测试：以前 scheduler.tick() 会在同一轮里把结论覆盖成「待验证」。
        val dir = tmpDir()
        // 参照事件取 10_000、客户端停在 500（落后 9.5s），理由同上一条用例（P0-2 抖动容差）。
        val events = writeRealEvents(dir, seq = 1, atMono = 10_000)
        val hs = clientWithHealthyDispatcher(dir, HealthStore.ZONGKONG, eventAtMono = 500)
        val sink = FakeSink()
        val sch = schedulerWith(hs, sink)

        // 真实顺序：先 refreshSystemFacts（这里直接调它的接线点），再 tick。
        EventChannelWiring.refresh(hs, events.read(), wallV, 61_000, suppressed = false, graceMs = 20_000)
        assertEquals(HealthStore.EVENT_ABNORMAL, hs.get(HealthStore.ZONGKONG).eventChannel)

        sch.tick()

        // tick 之后必须仍然是「异常」——调度器不再碰事件通道。
        assertEquals(
            "scheduler.tick() 不得覆盖事件通道结论（F-01）",
            HealthStore.EVENT_ABNORMAL,
            hs.get(HealthStore.ZONGKONG).eventChannel,
        )
    }

    @Test
    fun `wiring reports VERIFIED when client event progress caught up`() {
        val dir = tmpDir()
        val events = writeRealEvents(dir, seq = 1, atMono = 1_000)
        val hs = clientWithHealthyDispatcher(dir, HealthStore.ZONGKONG, eventAtMono = 1_500)
        val out = EventChannelWiring.refresh(hs, events.read(), wallV, 61_000, suppressed = false)
        assertEquals(EventChannel.Verdict.VERIFIED, out[HealthStore.ZONGKONG]!!.first)
        assertEquals(HealthStore.EVENT_VERIFIED, hs.get(HealthStore.ZONGKONG).eventChannel)
    }

    @Test
    fun `wiring stays PENDING while screen off even if progress stalls`() {
        val dir = tmpDir()
        val events = writeRealEvents(dir, seq = 1, atMono = 1_000)
        val hs = clientWithHealthyDispatcher(dir, HealthStore.ZONGKONG, eventAtMono = 500)
        val out = EventChannelWiring.refresh(hs, events.read(), wallV, 61_000, suppressed = true)
        assertEquals(EventChannel.Verdict.PENDING, out[HealthStore.ZONGKONG]!!.first)
        assertEquals(HealthStore.EVENT_PENDING, hs.get(HealthStore.ZONGKONG).eventChannel)
    }

    @Test
    fun `wiring stays PENDING when sentinel itself received no event`() {
        val dir = tmpDir()
        // events.json 不存在 = 哨兵自己也没收到事件：无法区分是谁的问题，不许判异常。
        val hs = clientWithHealthyDispatcher(dir, HealthStore.ZONGKONG, eventAtMono = 500)
        val out = EventChannelWiring.refresh(
            store = hs, progress = null, nowWall = wallV, nowMono = 61_000, suppressed = false,
        )
        assertEquals(EventChannel.Verdict.PENDING, out[HealthStore.ZONGKONG]!!.first)
    }

    @Test
    fun `wiring stays PENDING while dispatcher is not confirmed`() {
        val dir = tmpDir()
        val events = writeRealEvents(dir, seq = 1, atMono = 1_000)
        // 从没 ack 过（lastAckAtMono = 0）：执行线程本身没确认，先不解判断事件通道。
        val hs = HealthStore(dir)
        hs.register(HealthStore.ZONGKONG, "1.0", 1, "i1", "sha", 0, nowWall = wallV, nowMono = monoV)
        val out = EventChannelWiring.refresh(hs, events.read(), wallV, 61_000, suppressed = false)
        assertEquals(EventChannel.Verdict.PENDING, out[HealthStore.ZONGKONG]!!.first)
    }

    @Test
    fun `wiring ignores unregistered clients`() {
        val dir = tmpDir()
        val events = writeRealEvents(dir, seq = 1, atMono = 1_000)
        val hs = HealthStore(dir)
        val out = EventChannelWiring.refresh(hs, events.read(), wallV, 61_000, suppressed = false)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `evaluate does not self reference the previous verdict`() {
        // sentinelSawEventAfter 必须来自文件事实，而不是「上次结论」。
        val dir = tmpDir()
        val events = writeRealEvents(dir, seq = 2, atMono = 5_000)
        // 上一次结论是「已验证」→ 但文件里的事件确实存在 → 仍按文件事实判。
        val ok = EventChannel.evaluate(
            progress = events.read(), clientEventAtMono = 5_000,
            nowMono = 90_000, registered = true, permissionEnabled = true,
            dispatcherHealthy = true, suppressed = false,
        )
        assertEquals(EventChannel.Verdict.VERIFIED, ok.first)

        // 文件为空（哨兵自己没收到事件）→ 无论上次结论是什么，都只能是待验证。
        val pending = EventChannel.evaluate(
            progress = EventProgressFile.State(), clientEventAtMono = 100,
            nowMono = 90_000, registered = true, permissionEnabled = true,
            dispatcherHealthy = true, suppressed = false,
        )
        assertEquals(EventChannel.Verdict.PENDING, pending.first)
        assertTrue(pending.second.contains("没有可对照") || pending.second.contains("无法区分"))
    }
}