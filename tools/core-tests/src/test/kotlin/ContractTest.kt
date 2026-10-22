/** 墨灵 ↔ 总控 的约定：两边的 core 编在一起，检查墨灵发出去的总控都认、总控给的墨灵都读得出。 */
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkWire
import com.zongkong.core.moling.Moling
import com.zongkong.core.moling.MolingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContractTest {
    @Test fun everyValidMolingCommandPassesZongkongCheck() {
        val all = listOf(
            ZkCommand.SetMain("写申论", 1_000L, "800 字", "水道"),
            ZkCommand.SetMain("写申论", 0L, "", ""),
            ZkCommand.AddAction("背单词", 1_000L, 2_000L, "", "水道"),
            ZkCommand.StartFocus(25, "写申论", "偏了"),
            ZkCommand.EnterHq(30, "乱了"),
            ZkCommand.Tighten("block_app", "com.ss.android.ugc.aweme", "刷多了"),
            ZkCommand.RequestLoosen("unblock_app", "tv.danmaku.bili", "周末"),
            ZkCommand.RequestLoosen("play_quota", "90", "周末"),
            ZkCommand.Note("开头先亮观点", "对话"),
            ZkCommand.Tighten("strict", "90", "今晚不刷"),
            ZkCommand.Tighten("cut_play", "30", "刷多了"),
            ZkCommand.Commit("写完第二题", 5_000L, "看行动记录", "他说的"),
            ZkCommand.Directive("申论三篇", "这周写完三篇", "要做｜周三第一篇｜周三 21:00", "谋划"),
        )
        all.forEach { c -> assert(ZkWire.valid(c)); assertNull(c.toString(), Moling.check(ZkWire.toMap(c))) }
    }

    @Test fun autoFlagReachesZongkongAndGrantableKindsAgree() {
        val strict = ZkWire.toMapAuto(ZkCommand.Tighten("strict", "90", ""))
        assertEquals("true", strict["auto"])
        assertEquals(com.zongkong.core.moling.GrantKind.STRICT, com.zongkong.core.moling.Grants.kindOf(strict))
        assertNull("永久拦截不能授权", com.zongkong.core.moling.Grants.kindOf(ZkWire.toMapAuto(ZkCommand.Tighten("block_app", "a.b", ""))))
        assertNull("放宽不能授权", com.zongkong.core.moling.Grants.kindOf(ZkWire.toMapAuto(ZkCommand.RequestLoosen("unblock_app", "a.b", ""))))
    }

    @Test fun neverTouchListsAgree() {
        com.moling.core.power.Charter.neverTouch.forEach { pkg ->
            assert(pkg in com.zongkong.core.moling.MolingLink.neverBlock) { pkg }
        }
    }

    @Test fun statusRoundTrip() {
        val z = MolingStatus(at = 7, accessibilityOn = true, heartbeatAt = 6, userDisabled = true, strict = true, strictReason = "专注锁", focus = true,
            hqActive = true, quiet = true, mainTitle = "写申论", mainKey = "k1", mainPlannedAt = 9, mainStarted = true, mainDone = true, lastProgressAt = 8,
            playMinutesToday = 40, workMinutesToday = 90, playLeftMin = 10, spendTodayCents = 1234, credit = 70, stance = "审慎",
            aims = listOf("上岸"), weekDeliverables = listOf("申论 3 篇"), upcoming = listOf("背单词"), blocked = listOf("a.b"), sentinelOn = true)
        val m = ZkWire.status(z.copy(bounces = 3, emergencies = 1, gatesDone = 2, gatesTotal = 4, openCommitments = 5, holdUntil = 99, cutToday = 30, grants = "· 开专注锁").toMap())
        assertEquals(listOf(3, 1, 2, 4, 5, 30), listOf(m.bounces, m.emergencies, m.gatesDone, m.gatesTotal, m.openCommitments, m.cutToday))
        assertEquals(99L, m.holdUntil)
        assertEquals("· 开专注锁", m.grants)
        assertEquals(listOf(7L, 6L, 9L, 8L, 1234L), listOf(m.at, m.heartbeatAt, m.mainPlannedAt, m.lastProgressAt, m.spendTodayCents))
        assertEquals(listOf(true, true, true, true, true, true, true, true, true), listOf(m.accessibilityOn, m.userDisabled, m.strict, m.focus, m.hqActive, m.quiet, m.mainStarted, m.mainDone, m.sentinelOn))
        assertEquals(listOf("专注锁", "写申论", "k1", "审慎"), listOf(m.strictReason, m.mainTitle, m.mainKey, m.stance))
        assertEquals(listOf(40, 90, 10, 70), listOf(m.playMinutesToday, m.workMinutesToday, m.playLeftMin, m.credit))
        assertEquals(listOf(listOf("上岸"), listOf("申论 3 篇"), listOf("背单词"), listOf("a.b")), listOf(m.aims, m.weekDeliverables, m.upcoming, m.blocked))
    }
}

/** 以思代锁：墨灵的真实客户端 ↔ 总控的真实处理，中间只有字符串键值（和 ContentProvider 一样）。 */
class TrainingContractTest {
    private val zone = com.zongkong.core.DayClock.BEIJING
    private val now0 = java.time.LocalDateTime.of(2026, 10, 22, 15, 0).atZone(zone).toInstant().toEpochMilli()
    private val douyin = "com.ss.android.ugc.aweme"
    private val policy = com.zongkong.core.moling.TrainingPolicy(enabled = true, packages = setOf(douyin), revision = 3)
    private fun status(vararg r: com.zongkong.core.Reason) = com.zongkong.core.Status(
        now = now0, date = "2026-10-22", strict = r.isNotEmpty(), reasons = r.toList(), gates = emptyList(), emergencyUntil = null,
        emergencyLeft = 1, degradedLeft = 2, paused = false, lastActivity = null, silenceDueAt = null, playMin = 40, quotaMin = 40,
    )

    private class Zk(var policy: com.zongkong.core.moling.TrainingPolicy, var status: com.zongkong.core.Status, val zone: java.time.ZoneId) {
        var book = com.zongkong.core.moling.TrainingBook()
        var now = 0L
        val rnd = java.util.Random(5)
        fun call(method: String, req: Map<String, String>): Map<String, String> {
            val (b, resp) = com.zongkong.core.moling.TrainingWire.handle(method, req, policy, book, status, now, zone, rnd)
            book = b
            return resp
        }
    }

    private fun client(zk: Zk) = com.moling.core.redeem.TrainingRedeemClient(com.moling.core.redeem.WireGateV2Port({ m, r -> zk.call(m, r) }, { zk.now }))

    @Test fun fullLoopPassesThroughMolingsReceiptCheck() {
        val zk = Zk(policy, status(com.zongkong.core.Reason.Budget(40, 40)), zone).apply { now = now0 }
        val c = client(zk)
        val bundle = (c.start(douyin, now0) as com.moling.core.workspace.PortResult.Ok).value
        assertEquals(4, bundle.questions.size)
        assertEquals(3L, bundle.policyRevision)
        // 墨灵只拿到题干和选项；正确答案只有总控账本里有
        val key = zk.book.challenges.single().questions.associate { it.id to it.answer }
        val answers = bundle.questions.map { com.moling.core.redeem.ChallengeAnswer(it.id, key.getValue(it.id)) }
        zk.now = now0 + 60_000
        val r = (c.submit(answers, zk.now) as com.moling.core.workspace.PortResult.Ok).value
        assertEquals("PASSED", r.state)
        val pass = c.activePass(zk.now)!!
        assertEquals(10, pass.minutes)
        assertEquals(douyin, pass.packageName)
        assertEquals("总控那边记了同一张通行", zk.book.passes.single().passId, pass.passId)
        // 再交一次：同一张回执，总控不多发
        c.submit(answers, zk.now + 1_000)
        assertEquals(1, zk.book.passes.size)
    }

    @Test fun wrongAnswersAndStrictModeGiveNoPass() {
        val zk = Zk(policy, status(com.zongkong.core.Reason.Budget(40, 40)), zone).apply { now = now0 }
        val c = client(zk)
        val bundle = (c.start(douyin, now0) as com.moling.core.workspace.PortResult.Ok).value
        val key = zk.book.challenges.single().questions.associate { it.id to it.answer }
        val wrong = bundle.questions.map { q -> com.moling.core.redeem.ChallengeAnswer(q.id, q.options.first { it.id != key[q.id] }.id) }
        c.submit(wrong, now0 + 30_000)
        assertNull(c.activePass(now0 + 31_000))
        assertEquals(0, zk.book.passes.size)

        val strict = Zk(policy, status(com.zongkong.core.Reason.Budget(40, 40), com.zongkong.core.Reason.Debt("k", "写申论")), zone).apply { now = now0 }
        val r = client(strict).start(douyin, now0)
        assert(r is com.moling.core.workspace.PortResult.Failed)
        assertEquals("STRICT_MODE", (r as com.moling.core.workspace.PortResult.Failed).error.code)
    }

    @Test fun oldZongkongWithoutV2MeansNotAvailable() {
        val c = com.moling.core.redeem.TrainingRedeemClient(com.moling.core.redeem.WireGateV2Port({ _, _ -> mapOf("error" to "不认识的请求") }))
        val r = c.start(douyin, now0)
        assertEquals("NOT_AVAILABLE", (r as com.moling.core.workspace.PortResult.Failed).error.code)
    }
}
