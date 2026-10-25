package cn.companion.pet.core.presence

import cn.companion.pet.core.companion.CompanionEvent
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventVisibility
import cn.companion.pet.core.companion.PetStateStore
import cn.companion.pet.core.companion.StateEvent
import cn.companion.pet.core.companion.Topic
import cn.companion.pet.core.probe.ProbeMoment
import cn.companion.pet.core.probe.ProbePrompt
import cn.companion.pet.core.scene.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

/** C3 本机主动性：三层反应、证据门槛、克制策略、当天记忆、状态影响行为、写入可靠。 */
class PresenceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneOffset.UTC
    // 2026-10-25 10:00 UTC
    private val t0 = Instant.parse("2026-10-25T10:00:00Z").toEpochMilli()
    private val min = 60_000L

    private fun policy(cap: Int = 20, store: PresencePolicyStore = PresencePolicyStore(tmp.root)) =
        PresencePolicy(PresenceConfig(dailyCap = cap), store, zone)

    private fun engine(p: PresencePolicy = policy()) = LocalPresenceEngine(p, random = Random(7))

    private fun sig(
        now: Long, fg: String? = "com.app", since: Long? = now - 30_000, apps: Int = 1, switches: Int = 1,
        feed: Boolean = false, learning: Boolean = false, focus: Boolean = false, stuck: Boolean = false,
        due: Boolean = false, quiet: Boolean = false, active: Boolean = true, paused: Boolean = false,
        busy: String? = null, model: Boolean = true, sinceInteraction: Long? = 10 * min, excluded: Boolean = false,
        energy: Double = 0.6, interest: Double = 0.5, label: String = "测试",
    ) = PresenceSignals(now, fg, label, since, apps, switches, feed, learning, focus, excluded, false, stuck, due,
        quiet, active, paused, busy, model, sinceInteraction, energy, interest)

    // 1) 微反应和本机提示都不调模型、不消耗预算；2) 没有证据就安静
    @Test
    fun microAndNudgeNeedNoModelAndNoEvidenceIsSilent() {
        val e = engine()
        assertEquals(PresenceKind.SILENT, e.evaluate(sig(t0)).kind)
        val nudge = e.evaluate(sig(t0 + 1000, apps = 6, switches = 8, model = false))
        assertEquals(PresenceKind.LOCAL_NUDGE, nudge.kind)
        assertTrue(nudge.nudgeText!!.contains("应用"))
        assertNull(nudge.reasoning)
        val look = engine().evaluate(sig(t0, apps = 4, switches = 4, model = false))
        assertEquals(PresenceKind.MICRO_REACTION, look.kind)
        assertEquals(MicroReaction.LOOK, look.micro)
    }

    // 5) 阈值：快速切换、信息流停留、学习卡壳
    @Test
    fun thresholdsForSwitchingFeedDwellAndStuck() {
        assertEquals("4 apps is only a micro reaction", PresenceKind.MICRO_REACTION, engine().evaluate(sig(t0, apps = 4, switches = 9)).kind)
        assertNotEquals("5 apps but only 3 switches is not enough for a nudge", PresenceKind.LOCAL_NUDGE, engine().evaluate(sig(t0, apps = 5, switches = 3)).kind)
        assertEquals(PresenceKind.LOCAL_NUDGE, engine().evaluate(sig(t0, apps = 5, switches = 6)).kind)

        val feed = engine()
        assertEquals(PresenceKind.SILENT, feed.evaluate(sig(t0, feed = true, since = t0 - 5 * min)).kind)
        val yawn = feed.evaluate(sig(t0 + 6 * min, feed = true, since = t0 - 5 * min))
        assertEquals(MicroReaction.YAWN, yawn.micro)
        feed.commit(yawn, t0 + 6 * min)
        val nudge = feed.evaluate(sig(t0 + 16 * min, feed = true, since = t0 - 5 * min))
        assertEquals(PresenceKind.LOCAL_NUDGE, nudge.kind)
        assertTrue(nudge.nudgeText!!.contains("21 分钟"))

        assertEquals(ReasoningReason.STUCK, engine().evaluate(sig(t0, learning = true, stuck = true)).reasoning)
        assertEquals("stuck outside learning is ignored", PresenceKind.SILENT, engine().evaluate(sig(t0, stuck = true)).kind)
        val noBudget = engine().evaluate(sig(t0, learning = true, stuck = true, model = false))
        assertEquals("no budget falls back to a local nudge", PresenceKind.LOCAL_NUDGE, noBudget.kind)
    }

    // 3) 同一件事不会两次调模型；4) 每秒评估不写盘、不重复发言
    @Test
    fun sameSituationNeverTriggersTwiceAndEvaluationDoesNotWrite() {
        val store = PresencePolicyStore(tmp.root)
        val p = policy(store = store)
        val e = engine(p)
        val first = e.evaluate(sig(t0, learning = true, stuck = true, since = t0 - 2 * min))
        assertEquals(PresenceKind.MODEL_REASONING, first.kind)
        e.commit(first, t0); p.recordShown(t0)
        val writesAfterShow = store.writes
        repeat(120) { i -> // 两分钟每秒一次
            val d = e.evaluate(sig(t0 + (i + 1) * 1000L, learning = true, stuck = true, since = t0 - 2 * min))
            assertNotEquals(PresenceKind.MODEL_REASONING, d.kind)
            assertNotEquals(PresenceKind.LOCAL_NUDGE, d.kind)
        }
        assertEquals("evaluating must not write to disk", writesAfterShow, store.writes)
        assertEquals(1, e.stats.modelCalls)
        assertEquals(121, e.stats.evaluations)
    }

    // 6) 安静时段、锁屏、暂停、排除 App、深聊/思考/技能期间都不主动
    @Test
    fun protectedStatesStaySilent() {
        val loud = sig(t0, apps = 6, switches = 9, learning = true, stuck = true)
        listOf(
            loud.copy(quiet = true, stuck = false),
            loud.copy(userActive = false),
            loud.copy(paused = true),
            loud.copy(excluded = true),
            loud.copy(busy = "深聊"),
            loud.copy(busy = "想一想"),
            loud.copy(busy = "技能"),
        ).forEach { s ->
            val d = engine().evaluate(s)
            assertTrue(s.toString(), d.kind == PresenceKind.SILENT || d.kind == PresenceKind.MICRO_REACTION)
            assertFalse(d.kind == PresenceKind.LOCAL_NUDGE || d.kind == PresenceKind.MODEL_REASONING)
        }
        assertEquals("paused allows not even micro", PresenceKind.SILENT, engine().evaluate(loud.copy(paused = true)).kind)
    }

    // 7) 连续三次忽略后冷却，冷却期间不追问；8) 明确拒绝/暂停不会被退避逻辑覆盖
    @Test
    fun ignoreCooldownAndDismissalPriority() {
        val p = policy()
        repeat(3) { i -> p.recordShown(t0 + i * 4 * min); p.feedback(PresenceFeedback.IGNORED, t0 + i * 4 * min + 3 * min) }
        val afterThird = t0 + 2 * 4 * min + 3 * min
        assertFalse(p.check(afterThird + 5 * min).allowed)
        assertTrue(p.check(afterThird + 5 * min).reason.contains("冷却"))
        val e = engine(p)
        assertEquals(PresenceKind.SILENT, e.evaluate(sig(afterThird + 6 * min, apps = 6, switches = 9)).kind.let {
            if (it == PresenceKind.MICRO_REACTION) PresenceKind.SILENT else it
        })
        assertTrue(p.check(afterThird + 31 * min).allowed)

        val q = policy(store = PresencePolicyStore(tmp.newFolder()))
        q.feedback(PresenceFeedback.DISMISSED, t0)
        q.feedback(PresenceFeedback.INTERACTED, t0 + min) // 用户回话不缩短拒绝冷却
        q.userInitiated(t0 + 2 * min)
        assertFalse(q.check(t0 + 10 * min).allowed)
        assertTrue(q.check(t0 + 10 * min).reason.contains("关掉"))
        assertTrue(q.check(t0 + 31 * min).allowed)
        assertFalse("pause flag wins over everything", q.check(t0 + 31 * min, paused = true).allowed)
    }

    // 9) 每日上限跨午夜重置
    @Test
    fun dailyCapResetsAtLocalMidnight() {
        val p = policy(cap = 2)
        val late = Instant.parse("2026-10-25T23:40:00Z").toEpochMilli()
        p.recordShown(late - 20 * min); p.recordShown(late - 10 * min)
        assertFalse(p.check(late).allowed)
        assertTrue(p.check(late).reason.contains("上限"))
        val next = Instant.parse("2026-10-26T00:05:00Z").toEpochMilli()
        assertTrue(p.check(next).allowed)
        assertEquals(0, p.spokenToday(next))
        // 跨重启也保留当天计数
        val reopened = PresencePolicy(PresenceConfig(dailyCap = 2), PresencePolicyStore(tmp.root), zone)
        assertEquals(2, reopened.spokenToday(late))
    }

    // 到点不必然调模型：没有值得说的就顺延
    @Test
    fun scheduleOnlyCallsModelWhenWorthIt() {
        val nothing = engine().evaluate(sig(t0, due = true, sinceInteraction = 10 * min)) { false }
        assertEquals(PresenceKind.SILENT, nothing.kind)
        assertTrue(nothing.consumeSchedule)
        assertEquals(PresenceKind.MODEL_REASONING, engine().evaluate(sig(t0, due = true)) { true }.kind)
        assertEquals(PresenceKind.MODEL_REASONING, engine().evaluate(sig(t0, due = true, learning = true)) { false }.kind)
        var topicReads = 0
        engine().evaluate(sig(t0)) { topicReads++; true }
        assertEquals("topics are only read when due", 0, topicReads)
    }

    // 重复模式：半小时内三次从学习切回同一信息流
    @Test
    fun repeatedReturnToFeedIsAPattern() {
        val e = engine()
        var now = t0
        repeat(3) {
            e.evaluate(sig(now, fg = "study", since = now, learning = true)); now += 2 * min
            val d = e.evaluate(sig(now, fg = "feed", since = now, feed = true)); now += 2 * min
            if (it == 2) assertEquals(ReasoningReason.PATTERN, d.reasoning)
        }
    }

    // 12) 状态影响行为：精力低只做低强度表现；兴趣低提高提示门槛
    @Test
    fun petStateChangesBehaviour() {
        val tired = engine().evaluate(sig(t0, apps = 4, switches = 4, energy = 0.2))
        assertEquals(MicroReaction.YAWN, tired.micro)
        assertFalse(tired.intense)
        assertEquals(PresenceKind.LOCAL_NUDGE, engine().evaluate(sig(t0, apps = 5, switches = 6, interest = 0.9)).kind)
        assertNotEquals("low interest raises the bar", PresenceKind.LOCAL_NUDGE,
            engine().evaluate(sig(t0, apps = 5, switches = 6, interest = 0.0)).kind)
    }

    // 微反应有冷却，不会每秒重复
    @Test
    fun microReactionsHaveCooldown() {
        val e = engine()
        val first = e.evaluate(sig(t0, apps = 4, switches = 4))
        e.commit(first, t0)
        assertEquals(PresenceKind.SILENT, e.evaluate(sig(t0 + 10_000, apps = 4, switches = 4)).kind)
        assertEquals(PresenceKind.MICRO_REACTION, e.evaluate(sig(t0 + 91_000, apps = 4, switches = 4)).kind)
    }

    // 10) 当天记忆召回真实事件；删除的内容不会复活
    @Test
    fun todayDigestRecallsRealEventsAndHonoursForget() {
        fun ev(id: String, kind: EventKind, text: String, day: String = "2026-10-25", sources: List<String> = emptyList()) =
            CompanionEvent(id, t0, day, kind, text, sourceIds = sources)
        val events = listOf(
            ev("a", EventKind.EXCHANGE, "- 10:01 | 学习 | 我说：卡住了吗 | 回应：在看第三章"),
            ev("b", EventKind.NOTE, "晚上想把第三章看完"),
            ev("c", EventKind.NOTE, "银行卡号别记"),
            ev("f", EventKind.FORGET, "忘掉", sources = listOf("c")),
            ev("old", EventKind.NOTE, "昨天的事", day = "2026-10-24"),
        )
        val vis = EventVisibility(events, emptySet(), t0)
        val topics = listOf(
            Topic("t1", "第三章的例题", listOf("b"), t0 - min, t0 + 60 * min),
            Topic("t2", "银行卡的事", listOf("c"), t0 - min, t0 + 60 * min),
        )
        val lines = TodayDigest.build(events, vis, "2026-10-25", topics, listOf("技能「打开番茄钟」成功"), now = t0)
        val all = lines.joinToString("\n")
        assertTrue(all.contains("在看第三章"))
        assertTrue(all.contains("晚上想把第三章看完"))
        assertTrue(all.contains("第三章的例题"))
        assertTrue(all.contains("番茄钟"))
        assertFalse("forgotten note must not come back", all.contains("银行卡"))
        assertFalse(all.contains("昨天"))
        assertTrue(lines.size <= TodayDigest.MAX_LINES)
    }

    // 13) 关掉 C3 时提示词与原来一致（新字段为空就不出现）
    @Test
    fun promptsUnchangedWhenC3FieldsEmpty() {
        val m = ProbeMoment("10:00", Scene.NORMAL, "测试", "没有记录", null)
        val text = ProbePrompt.opening("p", "", emptyList(), "", m).joinToString { it.content }
        assertFalse(text.contains("今天的前情"))
        assertFalse(text.contains("这次开口的原因"))
        val withC3 = ProbePrompt.opening("p", "", emptyList(), "", m.copy(today = listOf("他记下的念头：看完第三章"), presenceReason = "学习页卡壳"))
            .joinToString { it.content }
        assertTrue(withC3.contains("今天的前情") && withC3.contains("学习页卡壳"))
    }

    // 11) 状态跨重启、衰减；写入失败不丢旧状态
    @Test
    fun petStateSurvivesRestartAndFailedWrites() {
        val clock = object : Clock() {
            var t = t0
            override fun getZone(): ZoneId = zone
            override fun withZone(z: ZoneId?): Clock = this
            override fun instant(): Instant = Instant.ofEpochMilli(t)
        }
        val store = PetStateStore(tmp.root, clock)
        store.apply(StateEvent.USER_REPLIED, t0)
        val saved = PetStateStore(tmp.root, clock).current(t0).closeness
        assertTrue(saved > 0.35)
        val failing = PetStateStore(tmp.root, clock, replace = { _, _ -> false })
        failing.apply(StateEvent.USER_REPLIED, t0 + 1000)
        assertTrue(failing.lastError!!.contains("保留旧状态"))
        assertEquals("old state kept on disk", saved, PetStateStore(tmp.root, clock).current(t0).closeness, 1e-9)
        val decayed = PetStateStore(tmp.root, clock).current(t0 + 12 * 60 * min).closeness
        assertTrue("decays toward baseline", decayed < saved && decayed > 0.35)

        File(tmp.root, "state.json").writeText("{broken")
        PetStateStore(tmp.root, clock).current(t0)
        assertEquals(1, tmp.root.listFiles { f -> f.name.startsWith("state.corrupt-") }!!.size)
    }

    // 克制状态写入失败也不丢旧状态
    @Test
    fun policyStoreFailureKeepsOldFile() {
        val ok = PresencePolicyStore(tmp.root)
        PresencePolicy(PresenceConfig(), ok, zone).recordShown(t0)
        val failing = PresencePolicyStore(tmp.root, replace = { _, _ -> false })
        PresencePolicy(PresenceConfig(), failing, zone).recordShown(t0 + min)
        assertTrue(failing.lastError!!.contains("保留旧状态"))
        assertEquals(1, PresencePolicyStore(tmp.root).read().spokenToday)
    }
}

/**
 * 模拟两小时（每 5 秒评估一次）：学习 20 分钟 → 刷信息流 15 分钟 → 一分钟内乱切几次 → 再学习……
 * 旧逻辑：每次到点都调一次模型。C3：到点也要“值得”才调，其余用小动作和本机提示。
 * 数字写进 system-out，交接文档引用它；不是真机数据。
 */
class PresenceSimulationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun twoHourSimulationUsesFewerModelCalls() {
        val t0 = Instant.parse("2026-10-25T10:00:00Z").toEpochMilli()
        val min = 60_000L
        val policy = PresencePolicy(PresenceConfig(dailyCap = 20), PresencePolicyStore(tmp.root), ZoneOffset.UTC)
        val engine = LocalPresenceEngine(policy, random = Random(3))
        var dueAt = t0 + 9 * min
        var oldModelCalls = 0
        var lastInteraction = t0
        var fg = "study"; var since = t0
        val switchTimes = ArrayDeque<Pair<Long, String>>()
        var t = t0
        while (t < t0 + 120 * min) {
            val phase = ((t - t0) / min) % 40
            val want = when {
                phase < 20 -> "study"
                phase < 35 -> "feed"
                phase == 35L -> listOf("a", "b", "c", "d", "e", "f")[(((t - t0) / 5_000) % 6).toInt()]
                else -> "study"
            }
            if (want != fg) { fg = want; since = t; switchTimes.addLast(t to want) }
            while (switchTimes.isNotEmpty() && switchTimes.first().first < t - min) switchTimes.removeFirst()
            val due = t >= dueAt
            if (due) oldModelCalls++
            val s = PresenceSignals(t, fg, fg, since, switchTimes.map { it.second }.toSet().size, switchTimes.size,
                inFeed = fg == "feed", inLearning = fg == "study", inFocus = false, excluded = false, inLauncher = false,
                stuck = false, scheduledDue = due, quiet = false, userActive = true, paused = false, busy = null,
                canCallModel = true, sinceInteractionMillis = t - lastInteraction)
            val d = engine.evaluate(s) { false }
            if (d.kind != PresenceKind.SILENT) {
                engine.commit(d, t)
                if (d.kind != PresenceKind.MICRO_REACTION) { policy.recordShown(t); policy.feedback(PresenceFeedback.INTERACTED, t + min); lastInteraction = t + min }
            }
            if (due) dueAt = t + 9 * min
            t += 5_000
        }
        val st = engine.stats
        println("C3 simulation 2h: old model calls=$oldModelCalls, C3 model=${st.modelCalls}, nudges=${st.nudges}, micro=${st.micro}, evaluations=${st.evaluations}")
        assertTrue(st.modelCalls < oldModelCalls)
        assertTrue(st.micro > 0 && st.nudges > 0)
        assertTrue(policy.spokenToday(t) <= 20)
    }
}
