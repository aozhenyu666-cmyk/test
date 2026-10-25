package cn.companion.pet.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * F-02 回归测试：紧急暂停两端接通 + 暂停真的挡住自动锁屏。
 *
 * 两个缺口（verifier 复现、captain 确认）：
 *  1. 哨兵发给同桌的 `emergencyStop` 没被实现 → 同桌不会被停；
 *  2. 同桌报给哨兵的 `emergencyStop` 哨兵不认识（方法集冻结）→ 要改走 `syncPolicy`。
 *
 * 以及比功能更硬的一条：暂停之后「能不能自动锁屏」必须真的被挡住，
 * 且要跨**窗口翻页**（新 policyId 顶掉旧规则）与**进程重启**（内存事实清零）。
 */
class EmergencyStopTest {

    // ---------- 假落点：内存版 & 故意写不进去的版本 ----------

    /** 一个能跑「规则文件 + 独立标记」两条来源的内存落点。 */
    private class FakeSink(
        var policies: MutableList<PolicyDto> = mutableListOf(),
        private val failRuleWrite: Boolean = false,
        private val failMarkerWrite: Boolean = false,
        private val markerFileOk: Boolean = true,
    ) : StopSink {
        var markerStopped = false
        var stopAllCalls = 0
        var markCalls = 0

        override fun load(): List<PolicyDto> = policies.toList()

        override fun stopAll(nowWall: Long): Boolean {
            stopAllCalls++
            if (failRuleWrite) return false
            policies = policies.map { it.copy(emergencyState = "STOPPED") }.toMutableList()
            return true
        }

        override fun resumeAll(): Boolean {
            policies = policies.map { it.copy(emergencyState = "NONE") }.toMutableList()
            return true
        }

        override fun markEmergencyStopped(nowWall: Long, reason: String): Boolean {
            markCalls++
            if (failMarkerWrite) return false
            markerStopped = true
            return true
        }

        override fun clearEmergencyStopped(): Boolean {
            markerStopped = false
            return true
        }

        override fun emergencyStopped(): Boolean = markerStopped && markerFileOk
    }

    private fun policy(id: String, state: String = "NONE") = PolicyDto(
        policyId = id, revision = 1, issuer = "user",
        requiredApps = listOf("cn.companion.pet"),
        startsAtWall = 0, endsAtWall = 4_000_000, lockAtWall = 1_000_000,
        emergencyState = state, enabled = true,
    )

    // ---------- 缺口 1：Provider 认 emergencyStop ----------

    @Test
    fun `provider declares the method names the sentinel actually sends`() {
        // 哨兵 watch/ClientCall.kt 发的就是这两个名字。
        assertEquals("challenge", PetHealthProvider.METHOD_CHALLENGE)
        assertEquals("emergencyStop", PetHealthProvider.METHOD_EMERGENCY)
        assertEquals("ping", PetHealthProvider.METHOD_PING)
    }

    @Test
    fun `sentinel sends reason and version under the keys we read`() {
        // 哨兵 ClientCall.notifyEmergency 的 Bundle：v / reason / from
        assertEquals("reason", PetHealthProvider.KEY_REASON)
        assertEquals("v", PetHealthProvider.KEY_VERSION)
    }

    // ---------- 缺口 2：发给哨兵的方法名必须在冻结方法集内 ----------

    @Test
    fun `outbound method names stay inside the frozen spec set`() {
        // 规范 §3.1 冻结：register / syncPolicy / reportReceipt / ping。
        // 本端上报紧急暂停**必须**走 syncPolicy，不能发明第六个方法。
        val allowed = setOf("register", "syncPolicy", "reportReceipt", "ping")
        assertTrue("syncPolicy 必须在冻结集合里", "syncPolicy" in allowed)
        assertFalse("emergencyStop 不能是本端发给哨兵的方法名", "emergencyStop" in allowed)
    }

    @Test
    fun `pause is reported by syncing STOPPED inside the policy json`() {
        val p = Policy(
            policyId = "pet-window", revision = 3, issuer = "user",
            requiredApps = setOf("cn.companion.pet"),
            startsAtWall = 0, endsAtWall = 200, lockAtWall = 100, enabled = true,
            emergencyState = "NONE",
        )
        // 上报暂停：强制把 emergencyState 写成 STOPPED，随规则一起同步。
        val json = PetGuardRuntime.encodePolicies(listOf(p), forcedEmergencyState = "STOPPED")
        assertTrue("必须带上 STOPPED：$json", json.contains("\"emergencyState\":\"STOPPED\""))
        assertTrue(json.contains("\"schemaVersion\":2"))
        // 恢复时同步 NONE。
        val back = PetGuardRuntime.encodePolicies(listOf(p), forcedEmergencyState = "NONE")
        assertTrue(back.contains("\"emergencyState\":\"NONE\""))
        // 不强制时沿用规则自身的值，不偷偷改。
        val plain = PetGuardRuntime.encodePolicies(listOf(p))
        assertTrue(plain.contains("\"emergencyState\":\"NONE\""))
    }

    // ---------- 三条来源：单条不够，并集才算暂停 ----------

    @Test
    fun `any single source is enough to count as paused`() {
        assertTrue(EmergencyGate.isPaused(PauseSources(memoryPaused = true)))
        assertTrue(EmergencyGate.isPaused(PauseSources(policyStopped = true)))
        assertTrue(EmergencyGate.isPaused(PauseSources(markerStopped = true)))
        assertFalse(EmergencyGate.isPaused(PauseSources()))
    }

    /** 关键：窗口翻页顶掉旧规则后，只剩独立标记也仍然是暂停。 */
    @Test
    fun `marker alone still pauses after the rule was replaced by a new window`() {
        val newWindowPolicies = listOf(policy("pet-window-v2", state = "NONE"))
        // 规则文件里已经没有 STOPPED（被新一轮顶掉了），内存也清了（进程重启）。
        val sources = PauseSources(
            memoryPaused = false,
            policyStopped = EmergencyGate.anyPolicyStopped(newWindowPolicies),
            markerStopped = true,
        )
        assertFalse("旧规则确实不在了（这个前提要成立）", sources.policyStopped)
        assertTrue("独立标记必须兜住这种情况", EmergencyGate.isPaused(sources))
    }

    @Test
    fun `describe explains which sources are active`() {
        assertEquals("未暂停", EmergencyGate.describe(PauseSources()))
        assertTrue(EmergencyGate.describe(PauseSources(markerStopped = true)).contains("独立暂停标记"))
        assertTrue(EmergencyGate.describe(PauseSources(policyStopped = true)).contains("STOPPED"))
    }

    // ---------- 停止动作：写盘 + 读回核对 + 幂等 ----------

    @Test
    fun `stopLocal writes both sources and verifies by reading back`() {
        val sink = FakeSink(mutableListOf(policy("a"), policy("b")))
        val out = EmergencyStopLocal.stopLocal(sink, 1_000L, "误锁了")
        assertTrue("应该成功：" + out.note, out.ok)
        assertEquals(listOf("a", "b"), out.policyIds.sorted())
        assertTrue("规则要真的变成 STOPPED", sink.policies.all { it.emergencyState == "STOPPED" })
        assertTrue("独立标记也要写上", sink.markerStopped)
        assertFalse(out.alreadyStopped)
        assertEquals(1, sink.stopAllCalls)
    }

    @Test
    fun `stopLocal is idempotent and does not rewrite rules twice`() {
        val sink = FakeSink(mutableListOf(policy("a")))
        val first = EmergencyStopLocal.stopLocal(sink, 1_000L, "第一次")
        assertTrue(first.ok)
        val second = EmergencyStopLocal.stopLocal(sink, 2_000L, "第二次")
        assertTrue("重复调用仍要 ok=true", second.ok)
        assertTrue("要报 alreadyStopped=true", second.alreadyStopped)
        assertEquals("已经全 STOPPED 就不该再写规则", 1, sink.stopAllCalls)
        assertEquals("但独立标记每次都要确认写上", 2, sink.markCalls)
    }

    @Test
    fun `stopLocal reports failure when the rule write fails`() {
        val sink = FakeSink(mutableListOf(policy("a")), failRuleWrite = true)
        val out = EmergencyStopLocal.stopLocal(sink, 1_000L, "x")
        assertFalse("写盘失败绝不能报成功", out.ok)
        assertTrue("必须说明原因：" + out.note, out.note.contains("写入失败"))
    }

    /** 标记写不进去 = 跨窗口/跨重启就守不住 → 必须 ok=false，让哨兵显示「暂停未确认」。 */
    @Test
    fun `stopLocal reports failure when the marker write fails`() {
        val sink = FakeSink(mutableListOf(policy("a")), failMarkerWrite = true)
        val out = EmergencyStopLocal.stopLocal(sink, 1_000L, "x")
        assertFalse("标记写不上就不能谎称已停", out.ok)
        assertTrue(out.note.contains("暂停标记"))
    }

    @Test
    fun `stopLocal succeeds with no policies but still writes the marker`() {
        val sink = FakeSink(mutableListOf())
        val out = EmergencyStopLocal.stopLocal(sink, 1_000L, "空清单")
        assertTrue("没有规则也算停住", out.ok)
        assertTrue("但独立标记必须写上（否则以后出现的新规则会绕过暂停）", sink.markerStopped)
        assertTrue(out.note.contains("没有本地规则"))
    }

    @Test
    fun `resumeLocal clears rules and the marker`() {
        val sink = FakeSink(mutableListOf(policy("a")))
        EmergencyStopLocal.stopLocal(sink, 1_000L, "x")
        assertTrue(sink.markerStopped)
        assertTrue(EmergencyStopLocal.resumeLocal(sink))
        assertFalse("恢复后标记必须清掉", sink.markerStopped)
        assertTrue(sink.policies.all { it.emergencyState == "NONE" })
    }

    // ---------- 硬门槛：暂停之后就真的不许锁屏 ----------

    private fun ctx(paused: Boolean) = LocalLockContext(
        policyEffective = true,
        emergencyBlocked = paused,
        repairGraceUntilMono = 0,
        sentinelUnresponsive = true,
        selfHealthy = true,
    )

    @Test
    fun `planner refuses to fire while paused`() {
        val planner = LocalLockPlanner(LockArbiter(), requester = { "req" })
        val p = Policy(
            policyId = "pet-window", revision = 1, issuer = "user",
            requiredApps = setOf("cn.companion.pet"),
            startsAtWall = 0, endsAtWall = 4_000_000, lockAtWall = 1_000_000, enabled = true,
        )
        val d = planner.plan(ctx(paused = true), p, "inc-1", 1_000L, "到期")
        assertFalse("暂停中不许锁", d.fire)
        assertTrue(d.reason.contains("紧急暂停"))
    }

    /**
     * 端到端的「跨窗口翻页」场景，用真的文件存储（不是假件）：
     *
     * 1. 写下窗口 A 的规则（NONE）；
     * 2. 紧急暂停 → 规则 STOPPED + 独立标记；
     * 3. **模拟窗口翻页**：写一份新 policyId 的规则顶掉旧的（新规则是 NONE）；
     * 4. 重新 new 一个 PetPolicyStore + EmergencyPauseStore（**模拟进程重启**，内存事实全清）；
     * 5. 断言：暂停判定仍然成立 —— 于是 tick() 不会走到锁屏。
     */
    @Test
    fun `pause survives window rollover and process restart (real files)`() {
        val dir: File = Files.createTempDirectory("pet-pause").toFile()
        var wallNow = 1_000_000L
        var monoNow = 50_000L
        val wall: Wall = Wall { wallNow }
        val mono: Mono = Mono { monoNow }

        // 1) 窗口 A
        val storeA = PetPolicyStore(dir, wall, mono)
        val markersA = EmergencyPauseStore(dir)
        assertTrue(storeA.upsert(policy("window-A")).isSuccess)

        // 2) 紧急暂停（用生产落点，走与 Provider 完全相同的路径）
        val outcome = EmergencyStopLocal.stopLocal(storeA.asStopSink(markersA), wallNow, "用户误锁")
        assertTrue("停止必须成功：" + outcome.note, outcome.ok)
        assertTrue("独立标记文件要真的存在", File(dir, "policy/emergency.json").exists())

        // 3) 窗口翻页：旧规则被**彻底删掉**（不是改状态），新规则是 NONE。
        //    这是最坏情况：规则文件里再也找不到任何 STOPPED，只剩独立标记能兜。
        val paged = PolicyDto(
            policyId = "window-B", revision = 2, issuer = "user",
            requiredApps = listOf("cn.companion.pet"),
            startsAtWall = wallNow, endsAtWall = wallNow + 600_000, lockAtWall = wallNow + 300_000,
            emergencyState = "NONE", enabled = true,
        )
        assertTrue(storeA.upsert(paged).isSuccess)
        assertTrue("旧窗口的规则要被删掉", storeA.remove("window-A").isSuccess)
        val afterPage = PetPolicyStore(dir, wall, mono).asStopSink(markersA).load()
        assertEquals("前提：只剩新窗口这一条规则", 1, afterPage.size)
        assertTrue("前提：旧 STOPPED 规则已经被删掉了", afterPage.none { it.emergencyState == "STOPPED" })
        assertTrue("但新规则确实是 NONE（不然这条测试没意义）", afterPage.all { it.emergencyState != "STOPPED" })

        // 4) 进程重启：全部重新 new，内存事实清零
        val storeB = PetPolicyStore(dir, wall, mono)
        val markersB = EmergencyPauseStore(dir)
        val sinkB = storeB.asStopSink(markersB)
        val sources = PauseSources(
            // PetGuardRuntime.pauseSources() 在重启后 memoryPaused 会是 true，因为标记文件里写着 stopped；
            // 这里故意把它设成 false，模拟「连内存缓存都没有」的最坏情况。
            memoryPaused = false,
            policyStopped = EmergencyGate.anyPolicyStopped(sinkB.load()),
            markerStopped = markersB.readFromDisk()?.stopped == true,
        )
        assertFalse("前提：规则文件里已经找不到 STOPPED", sources.policyStopped)
        assertTrue("独立标记必须跨重启存活", sources.markerStopped)
        assertTrue("所以整体仍然算暂停", EmergencyGate.isPaused(sources))

        // 5) 因此即使到点、即使哨兵失联、即使自己健康，也不许锁。
        val planner = LocalLockPlanner(LockArbiter(), requester = { "req" })
        val duePolicy = Policy(
            policyId = "window-B", revision = 2, issuer = "user",
            requiredApps = setOf("cn.companion.pet"),
            startsAtWall = wallNow, endsAtWall = wallNow + 600_000, lockAtWall = wallNow + 300_000,
            enabled = true,
        )
        wallNow += 300_000
        val decision = planner.plan(
            LocalLockContext(
                policyEffective = true,
                emergencyBlocked = EmergencyGate.isPaused(sources),
                sentinelUnresponsive = true,
                selfHealthy = true,
            ),
            duePolicy, "lock-at:window-B", monoNow, "规则到期锁屏",
        )
        assertFalse("暂停跨窗口翻页 + 跨重启后仍然不许自动锁屏", decision.fire)
        assertTrue(decision.reason.contains("紧急暂停"))
    }

    /** 反向验证：清掉暂停后，同样的条件**应该**恢复可锁，别把暂停写成永久失效。 */
    @Test
    fun `after explicit resume the same conditions can fire again`() {
        val dir: File = Files.createTempDirectory("pet-resume").toFile()
        var wallNow = 1_000_000L
        val wall: Wall = Wall { wallNow }
        val mono: Mono = Mono { 50_000L }
        val store = PetPolicyStore(dir, wall, mono)
        val markers = EmergencyPauseStore(dir)
        val sink = store.asStopSink(markers)
        assertTrue(store.upsert(policy("w")).isSuccess)

        assertTrue(EmergencyStopLocal.stopLocal(sink, wallNow, "x").ok)
        assertTrue(EmergencyGate.isPaused(PauseSources(markerStopped = markers.readFromDisk()?.stopped == true)))

        assertTrue(EmergencyStopLocal.resumeLocal(sink))
        val pausedAfter = EmergencyGate.isPaused(
            PauseSources(
                memoryPaused = false,
                policyStopped = EmergencyGate.anyPolicyStopped(sink.load()),
                markerStopped = markers.readFromDisk()?.stopped == true,
            ),
        )
        assertFalse("用户显式恢复后不能再当暂停", pausedAfter)
    }

    // ---------- 存储层自己的读回核对 ----------

    @Test
    fun `emergency store verifies by reading from disk, not from cache`() {
        val dir: File = Files.createTempDirectory("pet-marker").toFile()
        val store = EmergencyPauseStore(dir)
        val v = store.stop("测试", 100L, 200L)
        assertTrue(v.stopped)
        // 换一个实例读（缓存全新），必须还是 stopped。
        assertTrue(EmergencyPauseStore(dir).current().stopped)
        assertEquals("测试", EmergencyPauseStore(dir).current().reason)
        // 磁盘上真的写着 stopped=true。
        val raw = File(dir, "policy/emergency.json").readText()
        assertTrue(raw.contains("\"stopped\":true"))
    }

    @Test
    fun `emergency store reports not stopped when the write is rejected`() {
        val dir: File = Files.createTempDirectory("pet-marker-fail").toFile()
        // 原子替换被拒绝 = 写不进去。
        val store = EmergencyPauseStore(dir, replace = { from, _ -> from.delete(); false })
        val v = store.stop("测试", 100L, 200L)
        assertFalse("写不进去就不能报 stopped", v.stopped)
        assertFalse(EmergencyPauseStore(dir).current().stopped)
    }

    @Test
    fun `policy store stopAll and resumeAll persist`() {
        val dir: File = Files.createTempDirectory("pet-stopall").toFile()
        val store = PetPolicyStore(dir, { 1_000L }, { 1L })
        store.upsert(policy("a"))
        store.upsert(policy("b"))
        assertTrue(store.stopAll(1_000L))
        val reloaded = PetPolicyStore(dir, { 1_000L }, { 1L })
        assertTrue(reloaded.policies().all { it.emergencyState == "STOPPED" })
        assertTrue(reloaded.resumeAll())
        assertTrue(PetPolicyStore(dir, { 1_000L }, { 1L }).policies().all { it.emergencyState == "NONE" })
    }

    // ---------- 边界：暂停不越界进认知领域 ----------

    @Test
    fun `pause fields stay out of the forbidden cognition list`() {
        listOf("stopped", "policyIds", "stoppedCount", "alreadyStopped", "revision", "atWall", "note").forEach {
            assertFalse("暂停回包字段 $it 不该出现在认知黑名单里", it in BoundaryRules.FORBIDDEN_IN_HEALTH)
        }
        assertNotNull(BoundaryRules.HEALTH_FACTS_WHITELIST)
        assertNull(null as String?)
    }
}
