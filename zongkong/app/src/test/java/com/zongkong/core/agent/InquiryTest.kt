package com.zongkong.core.agent

import com.zongkong.core.DayClock
import com.zongkong.core.plan.Aim
import com.zongkong.core.work.F
import com.zongkong.core.work.Kind
import com.zongkong.core.work.WorkOps
import com.zongkong.core.ZkJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * 追问体系：诊断缺口、排序、一次只问一个、问过的不重问、答案落到哪。
 *
 * 全是纯逻辑，不碰 Android。
 *
 * 为什么这些测试值得写：这套东西的全部价值就在「只问一个、问过的别再问」这两条上。
 * 一旦退化成每次开口把缺口念一遍，用户感受到的就是唠叨，而不是被追问——那比不问更糟。
 */
class InquiryTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** 一份工作簿 + 环境，日期固定在 10 月 17 日上午 9 点。 */
    private fun env(): FakeEnv = FakeEnv(nowMs = at(17, 9))

    private fun envWith(vararg threads: Pair<String, Map<String, String>>): FakeEnv {
        val e = env()
        var w = e.w
        threads.forEach { (title, fields) ->
            w = WorkOps.create(w, Kind.THREAD, fields + (F.TITLE to title), e.nowMs, Random(1)).first
        }
        e.w = w
        return e
    }

    // ---------- 诊断：只报记录里真没有的 ----------

    @Test
    fun emptyRecordAsksWhatIsOnHandNotWhatIsTodayPlan() {
        val plan = Inquiry.plan(env())
        assertNotNull(plan.gap)
        // 什么都没有的时候，问“今天做什么”他答不出来；要问“手上有什么”
        assertEquals(Inquiry.Kind.TODAY, plan.gap!!.kind)
        assertEquals("today:empty", plan.gap!!.key)
        assertTrue(plan.gap!!.ask.contains("手上"))
    }

    @Test
    fun openThreadWithoutMainLineAsksForTheMainFirst() {
        val e = envWith("国考计划" to mapOf(F.WHY to "想上岸", F.CRITERIA to "笔试过线", F.NEXT to "查报名时间"))
        val plan = Inquiry.plan(e)
        assertEquals("today:no-main", plan.gap!!.key)
        assertEquals(Inquiry.Kind.TODAY, plan.gap!!.kind)
    }

    @Test
    fun thinProjectIsDiagnosedWithItsMissingField() {
        // 只有标题：为什么和完成依据都缺，行动也不到两步 → 薄
        val e = envWith("秋招" to mapOf(F.TITLE to "秋招"))
        val gaps = Inquiry.diagnose(e)
        val project = gaps.filter { it.kind == Inquiry.Kind.PROJECT }
        assertTrue("应该诊断出「这件事的底子」缺口", project.isNotEmpty())
        assertTrue(project.first().subject.contains("秋招"))
        // 缺的第一个字段是「为什么」，问的就是为什么
        assertEquals(F.WHY, project.first().landsIn)
        assertTrue(project.first().ask.contains("为什么"))
    }

    @Test
    fun nothingToAskOnceTheBasicsAreFilled() {
        // 有为什么、有完成依据、有下一步、行动也排了今天、定了主线 → 不该再问
        val e = env()
        val (w1, t) = WorkOps.create(
            e.w, Kind.THREAD,
            mapOf(F.TITLE to "行测", F.WHY to "想上岸", F.CRITERIA to "两套对 70%", F.NEXT to "做资料分析"),
            e.nowMs, Random(2),
        )
        val (w2, a) = WorkOps.create(
            w1, Kind.ACTION,
            mapOf(F.TITLE to "做两套资料分析", F.THREAD to t.key, F.CRITERIA to "对 70%", F.PLAN to "2026-10-17T10:00|2026-10-17T12:00"),
            e.nowMs, Random(3),
        )
        e.w = w2
        e.book = com.zongkong.core.plan.PlanBook(
            days = listOf(com.zongkong.core.plan.DayPlan(DayClock.dateKey(e.nowMs, zone), mainActionKey = a.key)),
        )
        val plan = Inquiry.plan(e)
        assertNull("底子齐了就不该再找话题问", plan.gap)
    }

    @Test
    fun stuckActionWithoutReasonAsksWhereItIsStuck() {
        val e = env()
        val (w1, t) = WorkOps.create(e.w, Kind.THREAD, mapOf(F.TITLE to "论文", F.WHY to "毕业", F.CRITERIA to "过答辩"), e.nowMs, Random(4))
        val (w2, _) = WorkOps.create(
            w1, Kind.ACTION,
            mapOf(F.TITLE to "写第二章", F.THREAD to t.key, F.STATUS to "卡住", F.CRITERIA to "写完"),
            e.nowMs, Random(5),
        )
        e.w = w2
        val gaps = Inquiry.diagnose(e)
        val stuck = gaps.firstOrNull { it.kind == Inquiry.Kind.STUCK }
        assertNotNull("标了卡住又没写卡点，就该问", stuck)
        assertTrue(stuck!!.ask.contains("卡在"))
        assertEquals(F.STUCK, stuck.landsIn)
    }

    @Test
    fun stuckActionWithReasonIsNotAskedAgain() {
        val e = env()
        val (w1, t) = WorkOps.create(e.w, Kind.THREAD, mapOf(F.TITLE to "论文", F.WHY to "毕业", F.CRITERIA to "过答辩"), e.nowMs, Random(6))
        val (w2, _) = WorkOps.create(
            w1, Kind.ACTION,
            mapOf(F.TITLE to "写第二章", F.THREAD to t.key, F.STATUS to "卡住", F.STUCK to "文献没看完", F.CRITERIA to "写完"),
            e.nowMs, Random(7),
        )
        e.w = w2
        // 卡点已经写在记录里了：再问一遍就是明知故问
        assertTrue(Inquiry.diagnose(e).none { it.kind == Inquiry.Kind.STUCK })
    }

    @Test
    fun aimWithoutDeadlineAsksForTheDate() {
        val e = env()
        e.book = com.zongkong.core.plan.PlanBook(
            aims = listOf(Aim(id = "a1", title = "拿到 offer", why = "不想再拖", created = e.nowMs)),
        )
        val gap = Inquiry.diagnose(e).firstOrNull { it.kind == Inquiry.Kind.AIM }
        assertNotNull(gap)
        assertEquals("aim:a1:deadline", gap!!.key)
        assertTrue(gap.ask.contains("什么时候"))
    }

    @Test
    fun aimWithDeadlineButNoMilestonesAsksForTheMiddleSteps() {
        val e = env()
        e.book = com.zongkong.core.plan.PlanBook(
            aims = listOf(Aim(id = "a1", title = "拿到 offer", deadline = "2026-12-20", created = e.nowMs)),
        )
        val gap = Inquiry.diagnose(e).firstOrNull { it.kind == Inquiry.Kind.AIM }
        assertNotNull(gap)
        assertEquals("aim:a1:milestones", gap!!.key)
        assertTrue(gap.ask.contains("中间"))
    }

    // ---------- 一次只问一个 ----------

    @Test
    fun onlyTheMostUrgentGapBecomesTheQuestion() {
        // 同时存在：没主线（20）、薄事项（30）、没排时间的下一步（40）
        val e = envWith(
            "国考计划" to mapOf(F.TITLE to "国考计划"),
            "论文" to mapOf(F.TITLE to "论文", F.WHY to "毕业", F.CRITERIA to "过答辩", F.NEXT to "写第三章"),
        )
        val plan = Inquiry.plan(e)
        assertTrue("应该算得出不止一个缺口", plan.gaps.size >= 2)
        assertEquals("today:no-main", plan.gap!!.key)
        // 提示词里明确写「只问这一个」，并说明后面还有几个（但不要问）
        val text = Inquiry.prompt(plan)
        assertTrue(text, text.contains("只问这一个"))
        assertTrue(text, text.contains("这一轮不要问它们"))
    }

    @Test
    fun promptCarriesAskWhyAndWhereTheAnswerLands() {
        val e = envWith("秋招" to mapOf(F.TITLE to "秋招"))
        val plan = Inquiry.plan(e)
        val text = Inquiry.prompt(plan)
        assertTrue(text, text.contains("问什么："))
        assertTrue(text, text.contains("为什么问它："))
        assertTrue(text, text.contains("答案落到哪："))
        assertTrue(text, text.contains("缺口类型："))
    }

    // ---------- 问过的不重问 ----------

    @Test
    fun askedGapIsSkippedDuringCooldown() {
        val e = envWith("秋招" to mapOf(F.TITLE to "秋招"))
        val first = Inquiry.plan(e)
        assertNotNull(first.gap)
        val answer = mapOf(first.gap!!.key to e.nowMs)

        // 刚问过：同一个口子不该再出现
        val second = Inquiry.plan(e, now = e.nowMs + 60_000L, asked = answer)
        assertTrue(
            "冷却期内不能再问同一个口子",
            second.gaps.none { it.key == first.gap!!.key },
        )
    }

    @Test
    fun cooldownExpiresAndTheQuestionComesBack() {
        val e = envWith("秋招" to mapOf(F.TITLE to "秋招"))
        val first = Inquiry.plan(e).gap!!
        val asked = mapOf(first.key to e.nowMs)

        // PROJECT 的冷却是一天；刚好过线就该重新出现
        val after = Inquiry.plan(e, now = e.nowMs + Inquiry.cooldown(first.kind) + 1_000L, asked = asked)
        assertTrue(
            "过了冷却期，同一个口子应该重新算进来",
            after.gaps.any { it.key == first.key } || after.gap?.key == first.key,
        )
    }

    @Test
    fun cooldownOfTodayIsShorterThanOthers() {
        // 今天怎么动要问得勤一点，别的问一天一次就够
        assertTrue(Inquiry.cooldown(Inquiry.Kind.TODAY) < Inquiry.cooldown(Inquiry.Kind.PROJECT))
        assertEquals(24 * 3600_000L, Inquiry.cooldown(Inquiry.Kind.AIM))
    }

    // ---------- 追问的时机：他提到什么就先问什么 ----------

    @Test
    fun gapAboutTheThingHeJustMentionedJumpsTheQueue() {
        val e = envWith(
            "国考计划" to mapOf(F.TITLE to "国考计划"),
            "秋招" to mapOf(F.TITLE to "秋招"),
        )
        // 不提任何事：按优先级，先问今天的主线
        assertEquals("today:no-main", Inquiry.plan(e).gap!!.key)
        // 他刚说到「秋招」：就先问秋招那件事的底子
        val focused = Inquiry.plan(e, focus = "秋招")
        assertNotNull(focused.gap)
        assertTrue(
            "提到哪件事就先问那件事",
            focused.gap!!.subject.contains("秋招") || focused.gap!!.key.contains("秋招"),
        )
    }

    @Test
    fun focusOfPicksTheTitleHeMentioned() {
        val e = envWith("国考计划" to mapOf(F.TITLE to "国考计划"), "秋招" to mapOf(F.TITLE to "秋招"))
        assertEquals("秋招", Inquiry.focusOf(e, "秋招那边简历还没改"))
        assertEquals("国考计划", Inquiry.focusOf(e, "今天国考计划一点没动"))
        assertEquals("", Inquiry.focusOf(e, "今天有点累"))
    }

    // ---------- 空转：没缺口时不要为了问而问 ----------

    @Test
    fun noGapMeansTellItNotToAsk() {
        val e = env()
        val (w1, t) = WorkOps.create(
            e.w, Kind.THREAD,
            mapOf(F.TITLE to "行测", F.WHY to "想上岸", F.CRITERIA to "两套对 70%", F.NEXT to "做资料分析"),
            e.nowMs, Random(8),
        )
        val (w2, a) = WorkOps.create(
            w1, Kind.ACTION,
            mapOf(F.TITLE to "做两套资料分析", F.THREAD to t.key, F.CRITERIA to "对 70%", F.PLAN to "2026-10-17T10:00|2026-10-17T12:00"),
            e.nowMs, Random(9),
        )
        e.w = w2
        e.book = com.zongkong.core.plan.PlanBook(
            days = listOf(com.zongkong.core.plan.DayPlan(DayClock.dateKey(e.nowMs, zone), mainActionKey = a.key)),
        )
        val text = Inquiry.prompt(Inquiry.plan(e))
        assertTrue("没缺口时要明确说别为了问而问", text.contains("别为了问而问"))
    }

    @Test
    fun allGapsCoolingDownSaysDoNotRepeat() {
        val e = envWith("秋招" to mapOf(F.TITLE to "秋招"))
        val gaps = Inquiry.diagnose(e)
        assertTrue(gaps.isNotEmpty())
        // 假设所有缺口都刚问过
        val asked = gaps.associate { it.key to e.nowMs }
        val text = Inquiry.prompt(Inquiry.plan(e, asked = asked))
        assertTrue("都在冷却期就明说别重问", text.contains("冷却期") || text.contains("不要重问"))
    }

    // ---------- 提示词进系统提示（真实接线的验证） ----------

    @Test
    fun systemPromptCarriesTheInquirySection() {
        val e = envWith("秋招" to mapOf(F.TITLE to "秋招"))
        val agent = Agent(Scripted(), e)
        assertTrue("当下这条线必须带追问", agent.nowPrompt().contains("# 追问"))
        assertTrue("整体那条线也要带（整体同样会缺期限、缺里程碑）", agent.overallPrompt().contains("# 追问"))
    }

    @Test
    fun envPinnedInquiryWinsOverRecompute() {
        // App 层在消息开头钉死了一份；同一轮里反复拼提示必须是同一份
        val e = object : FakeEnv(nowMs = at(17, 9)) {
            var calls = 0
            override fun inquiryPrompt(): String {
                calls++
                return "# 追问（钉死的那一份）\n问什么：PINNED"
            }
        }
        val agent = Agent(Scripted(), e)
        assertEquals(agent.nowPrompt(), agent.nowPrompt())
        assertTrue(agent.nowPrompt().contains("PINNED"))
        assertTrue("不该绕过 env 自己重算", e.calls >= 2)
    }

    // ---------- 「问过」的存取：重启后还记得 ----------

    @Test
    fun askedSurvivesRoundTripThroughJson() {
        // ChatLog 的这个字段是老数据里没有的，必须靠默认值平滑升级
        val log = com.zongkong.app.data.ChatLog(asked = listOf("today:no-main|1730000000000"))
        val json = ZkJson.encodeToString(com.zongkong.app.data.ChatLog.serializer(), log)
        val back = ZkJson.decodeFromString(com.zongkong.app.data.ChatLog.serializer(), json)
        assertEquals(listOf("today:no-main|1730000000000"), back.asked)
    }

    @Test
    fun oldChatJsonWithoutAskedFieldStillLoads() {
        val json = """{"threads":[],"activeId":"","memory":[]}"""
        val log = ZkJson.decodeFromString(com.zongkong.app.data.ChatLog.serializer(), json)
        assertTrue("老 chat.json 里没有这个字段，读出来要是空的而不是崩", log.asked.isEmpty())
    }
}
