package com.zongkong.core.agent

import com.zongkong.core.DayClock
import com.zongkong.core.work.F
import com.zongkong.core.work.Kind
import com.zongkong.core.work.Work
import com.zongkong.core.work.WorkOps
import com.zongkong.core.ZkJson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * 「当下 / 整体」两种对话的隔离：段归属、活跃段、交接摘要、每日段数、战役、冷启动默认。
 * 全是纯逻辑，不碰 Android。
 */
class ChatModeTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun user(text: String, t: Long = at(17, 9)) = ChatMsg(ChatMsg.USER, text, at = t)
    private fun said(text: String, t: Long = at(17, 10)) = ChatMsg(ChatMsg.ASSISTANT, text, at = t)

    private fun thread(
        id: String,
        mode: ChatMode,
        createdAt: Long = at(17, 9),
        messages: List<ChatMsg> = emptyList(),
        carry: String = "",
        closed: Boolean = false,
        campaign: String = "",
    ) = ChatThread(
        id = id, createdAt = createdAt, updatedAt = createdAt, messages = messages,
        carry = carry, closed = closed, mode = mode.key,
        campaign = campaign, campaignAt = if (campaign.isBlank()) 0 else createdAt,
    )

    // ---------- 旧数据兼容 ----------

    @Test
    fun oldThreadWithoutModeLoadsAsNow() {
        val json = """{"threads":[{"id":"t1","title":"旧对话","createdAt":1,"updatedAt":2,"messages":[{"role":"user","content":"早上好","at":1}],"carry":"旧交接","closed":true}],"activeId":"t1","memory":["晚上效率低"],"campaign":"大统筹","campaignTopic":"国考","campaignAt":1}"""
        val log = ZkJson.decodeFromString(com.zongkong.app.data.ChatLog.serializer(), json)
        val t = log.threads.single()
        assertEquals(ChatMode.NOW, t.chatMode)
        assertEquals("旧对话", t.title)
        assertEquals("旧交接", t.carry)
        assertTrue(t.closed)
        assertEquals(1, t.messages.size)
        assertEquals(listOf("晚上效率低"), log.memory)
        // 旧版本把战役放在全局：字段还读得出来（迁移成段的逻辑在 ChatRepo.load 里）
        assertEquals("大统筹", log.campaign)
    }

    @Test
    fun newFieldsHaveDefaultsSoOldConstructorsStillWork() {
        // 老代码 new ChatThread(id=...) 不传 mode 时必须是「当下」
        val t = ChatThread(id = "x", createdAt = 1, updatedAt = 1)
        assertEquals(ChatMode.NOW, t.chatMode)
        assertTrue(t.campaign.isBlank())
    }

    // ---------- 模式默认 ----------

    @Test
    fun defaultModeIsNowOnColdStart() {
        // 冷启动时没有任何“上次选的模式”，一律回到当下
        assertEquals(ChatMode.NOW, ChatThreads.startMode(null, emptyList()))
        val overall = listOf(thread("o1", ChatMode.OVERALL))
        assertEquals(ChatMode.NOW, ChatThreads.startMode(null, overall))
        // 本次运行里用户选过就听他的
        assertEquals(ChatMode.OVERALL, ChatThreads.startMode(ChatMode.OVERALL, overall))
    }

    // ---------- 活跃段 ----------

    @Test
    fun activeThreadIsPerMode() {
        val now = thread("n1", ChatMode.NOW, createdAt = at(17, 8))
        val overall = thread("o1", ChatMode.OVERALL, createdAt = at(17, 9))
        val all = listOf(now, overall)
        assertEquals("n1", ChatThreads.activeOf(all, ChatMode.NOW)?.id)
        assertEquals("o1", ChatThreads.activeOf(all, ChatMode.OVERALL)?.id)
        // 这一种对话一段都没有：返回 null（界面显示空对话态），不能退到另一种去
        assertNull(ChatThreads.activeOf(listOf(overall), ChatMode.NOW))
    }

    @Test
    fun activePrefersOpenThreadOfThatMode() {
        val closedOld = thread("o1", ChatMode.OVERALL, createdAt = at(17, 8), closed = true)
        val openNew = thread("o2", ChatMode.OVERALL, createdAt = at(17, 9))
        assertEquals("o2", ChatThreads.activeOf(listOf(closedOld, openNew), ChatMode.OVERALL)?.id)
        // 都收尾了：退到最近动过的那个（还能打开接着聊）
        assertEquals("o2", ChatThreads.activeOf(listOf(closedOld, openNew.copy(closed = true)), ChatMode.OVERALL)?.id)
    }

    @Test
    fun openingThreadTellsItsMode() {
        val all = listOf(thread("n1", ChatMode.NOW), thread("o1", ChatMode.OVERALL))
        assertEquals(ChatMode.OVERALL, ChatThreads.modeOf(all, "o1"))
        assertEquals(ChatMode.NOW, ChatThreads.modeOf(all, "n1"))
        assertNull(ChatThreads.modeOf(all, "nope"))
    }

    // ---------- 交接摘要不串台 ----------

    /**
     * 交接摘要只在同一种对话里传递：整体那段压出来的结论，不会被当下当成「上一段」。
     * 反例就是隔离失效——切换模式后模型会拿另一条线的结论往下聊。
     */
    @Test
    fun contextLineOnlyUsesSameModeCarry() {
        // 当下已经开了两段（前一段的结论 = 交接），整体开了一段（另一份交接）
        val nowOld = thread("n1", ChatMode.NOW, createdAt = at(17, 7), carry = "CARRY_NOW_ONLY")
        val nowActive = thread("n2", ChatMode.NOW, createdAt = at(17, 8))
        // 整体的交接按「上一段压出来的」来放：前一段带、当前那段不带
        val overallOld = thread("o0", ChatMode.OVERALL, createdAt = at(17, 8), carry = "CARRY_OVERALL_ONLY")
        val overall = thread("o1", ChatMode.OVERALL, createdAt = at(17, 9))
        val all = listOf(nowOld, nowActive, overallOld, overall)
        // 当下这一段：带的是当下自己上一段的交接，整体那份一个字都不带
        val nowLine = ChatThreads.contextLine(all, "n2", at(17, 13), zone)
        assertTrue(nowLine, nowLine.contains("CARRY_NOW_ONLY"))
        assertFalse(nowLine, nowLine.contains("CARRY_OVERALL_ONLY"))
        // 整体这一段：反过来也一样（当下那两段的交接不会进来）
        val overallLine = ChatThreads.contextLine(all, "o1", at(17, 13), zone)
        assertTrue(overallLine, overallLine.contains("CARRY_OVERALL_ONLY"))
        assertFalse(overallLine, overallLine.contains("CARRY_NOW_ONLY"))
    }

    @Test
    fun contextLineCountsOnlySameModeThreads() {
        val now1 = thread("n1", ChatMode.NOW, createdAt = at(17, 8))
        val now2 = thread("n2", ChatMode.NOW, createdAt = at(17, 9))
        val overall1 = thread("o1", ChatMode.OVERALL, createdAt = at(17, 9, 30))
        val all = listOf(now1, overall1, now2)
        // 整体只开了一段：整体第 1 段，不能因为当下开了两段就算第 3 段
        val line = ChatThreads.contextLine(all, "o1", at(17, 14), zone)
        assertTrue(line, line.contains("整体对话，今天第 1 段"))
        assertTrue(ChatThreads.contextLine(all, "n2", at(17, 14), zone).contains("当下对话，今天第 2 段"))
        assertEquals(2, ChatThreads.indexToday(all, now2, at(17, 14), zone))
        assertEquals(1, ChatThreads.indexToday(all, overall1, at(17, 14), zone))
    }

    @Test
    fun newThreadInheritsCarryOfItsOwnModeOnly() {
        val now1 = thread("n1", ChatMode.NOW, carry = "当下的结论")
        val overall1 = thread("o1", ChatMode.OVERALL, carry = "整体的结论")
        // 换段时新段带的是同一种对话的交接
        val (_, freshOverall) = ChatThreads.rollover(overall1, listOf(user("接着说")), "o2", at(17, 20))
        assertEquals(ChatMode.OVERALL, freshOverall.chatMode)
        assertTrue(freshOverall.carry, freshOverall.carry.contains("整体的结论"))
        assertFalse(freshOverall.carry.contains("当下的结论"))
        val (_, freshNow) = ChatThreads.rollover(now1, listOf(user("接着说")), "n2", at(17, 20))
        assertEquals(ChatMode.NOW, freshNow.chatMode)
        assertTrue(freshNow.carry, freshNow.carry.contains("当下的结论"))
    }

    // ---------- 每日段数各算各的 ----------

    @Test
    fun dailyCountIsPerMode() {
        val today = at(17, 8)
        val all = listOf(
            thread("n1", ChatMode.NOW, createdAt = today),
            thread("n2", ChatMode.NOW, createdAt = today + 3_600_000),
            thread("o1", ChatMode.OVERALL, createdAt = today + 7_200_000),
        )
        val nowMs = at(17, 20)
        assertEquals(2, ChatThreads.todayCount(all, nowMs, zone, ChatMode.NOW))
        assertEquals(1, ChatThreads.todayCount(all, nowMs, zone, ChatMode.OVERALL))
        assertEquals(3, ChatThreads.todayCount(all, nowMs, zone))
        // 当下用掉了 2 段额度，不影响整体还能不能新开
        assertEquals(0, ChatThreads.remaining(2, ChatThreads.todayCount(all, nowMs, zone, ChatMode.NOW)))
        assertEquals(1, ChatThreads.remaining(2, ChatThreads.todayCount(all, nowMs, zone, ChatMode.OVERALL)))
    }

    @Test
    fun blankSegmentDoesNotEatQuotaButRealOneDoes() {
        // 刚切到整体、还没说话：自动建出来的空段不占额度
        val blank = thread("o1", ChatMode.OVERALL, createdAt = at(17, 8))
        assertEquals(0, ChatThreads.openedToday(listOf(blank), at(17, 9), zone, ChatMode.OVERALL, 0))
        // 说过话的段才算用掉一段
        val said = thread("o2", ChatMode.OVERALL, createdAt = at(17, 8), messages = listOf(user("你好", at(17, 8))))
        assertEquals(1, ChatThreads.openedToday(listOf(blank, said), at(17, 9), zone, ChatMode.OVERALL, 0))
        // 记账说 3 段（有段被删掉了）：听记账，删掉的额度不还回来
        assertEquals(3, ChatThreads.openedToday(listOf(blank), at(17, 9), zone, ChatMode.OVERALL, 3))
        // 两种模式互不影响
        assertEquals(0, ChatThreads.openedToday(listOf(said), at(17, 9), zone, ChatMode.NOW, 0))
    }

    @Test
    fun limitTextNamesTheMode() {
        assertTrue(ChatThreads.limitText(1, 1, ChatMode.OVERALL).contains("整体"))
        assertTrue(ChatThreads.limitText(1, 0, ChatMode.NOW).contains("当下"))
    }

    // ---------- 战役不串台 ----------

    @Test
    fun campaignDoesNotLeakAcrossModes() {
        val overall = thread("o1", ChatMode.OVERALL, campaign = Campaign.GRAND_THINK.name)
        val nowThread = thread("n1", ChatMode.NOW)
        // 整体那段的战役只属于整体段；当下这一段没有战役
        assertTrue(Campaign.of(overall.campaign) != null)
        assertTrue(nowThread.campaign.isBlank())
        assertNull(Campaign.of(nowThread.campaign))
        // 换段时战役跟着段走（同一模式内继续），不会跑到另一种对话
        val (_, fresh) = ChatThreads.rollover(overall, listOf(user("继续")), "o2", at(17, 20))
        assertEquals(Campaign.GRAND_THINK.name, fresh.campaign)
        assertEquals(ChatMode.OVERALL, fresh.chatMode)
    }

    // ---------- 系统提示按模式 ----------

    @Test
    fun overallPromptCarriesOverallSnapshotAndBoundary() = runTest {
        val env = FakeEnv()
        env.w = WorkOps.create(env.w, Kind.THREAD, mapOf(F.TITLE to "秋招", F.NEXT to "改简历"), env.nowMs, Random(3)).first
        val agent = Agent(Scripted(), env)
        // 默认（当下）：不带整体快照，也不带整体的边界说明
        val nowPrompt = agent.systemPrompt()
        assertTrue(nowPrompt.contains("此刻的总控"))
        assertFalse(nowPrompt.contains("这一条线是「整体」对话"))
        // 整体：带边界说明 + 整体快照，缺数据的地方说未记录
        val overall = agent.systemPrompt(ChatMode.OVERALL)
        assertTrue(overall.contains("这一条线是「整体」对话"))
        assertTrue(overall.contains("整体状态"))
        assertTrue(overall.contains("未记录") || overall.contains("秋招"))
        assertFalse(overall.contains("## 此刻的总控"))
    }

    @Test
    fun overallPromptReusesTheSnapshotFromEnv() = runTest {
        val env = object : FakeEnv() {
            var reads = 0
            override fun overallSnapshot(): String { reads++; return "已读的整体快照 \$reads" }
        }
        val agent = Agent(Scripted(), env)
        // 同一轮里反复拼系统提示，读到的是同一份（计数只加一次的那份就是缓存）
        agent.systemPrompt(ChatMode.OVERALL)
        agent.systemPrompt(ChatMode.OVERALL)
        assertEquals(2, env.reads)  // env 里每次返回同一份缓存字符串，Agent 只是取用
        assertTrue(agent.systemPrompt(ChatMode.OVERALL).contains("已读的整体快照"))
    }

    // ---------- 整体快照内容 ----------

    @Test
    fun overallSnapshotSaysUnknownAndNeverFakesSuccess() {
        val env = FakeEnv()
        val text = OverallSnapshot.build(env)
        // 什么都没记：每一块都说未记录，不能编一个进度出来
        assertTrue(text, text.contains("未记录"))
        assertTrue(text, text.contains("还没有长期目标"))
        assertTrue(text, text.contains("这一周还没有立项的计划"))
        assertTrue(text, text.contains("电脑执行层：未接入"))
        // 一手：这一轮没读就写没读，不写“练完了”
        assertTrue(text, text.contains("这一轮没有读"))
        assertFalse(text, text.contains("练完了"))
    }

    @Test
    fun overallSnapshotReportsFailedExternalRead() {
        val env = FakeEnv()
        val ext = OverallSnapshot.External(source = "一手", error = "连不上", readAt = env.nowMs)
        val text = OverallSnapshot.build(env, ext)
        assertTrue(text, text.contains("读取失败"))
        assertTrue(text, text.contains("连不上"))
    }

    @Test
    fun overallSnapshotKeepsReadTimeAndFlagsStale() {
        val env = FakeEnv()
        val old = OverallSnapshot.External(source = "一手", text = "一手 1.0：今天 1/2 组", readAt = env.nowMs - OverallSnapshot.FRESH_MS - 60_000)
        val text = OverallSnapshot.build(env, old)
        assertTrue(text, text.contains("今天 1/2 组"))
        assertTrue(text, text.contains("可能过期"))
    }

    @Test
    fun overallSnapshotListsAimsAndWeeklyOutcomes() {
        val env = FakeEnv()
        val (w1, t) = WorkOps.create(env.w, Kind.THREAD, mapOf(F.TITLE to "秋招"), env.nowMs, Random(5))
        env.w = w1
        env.book = com.zongkong.core.plan.PlanBook(
            aims = listOf(com.zongkong.core.plan.Aim("a1", "拿到 offer", "不想再拖", "2026-12-20", env.nowMs, listOf(t.key))),
        )
        val text = OverallSnapshot.build(env)
        assertTrue(text, text.contains("拿到 offer"))
        assertTrue(text, text.contains("还剩"))
        assertTrue(text, text.contains("里程碑：未记录"))
    }

    // ---------- 同一条消息里工具循环的一致性（真实存在的坑） ----------

    /**
     * 整体模式下：同一条用户消息的整轮工具循环里，系统提示必须是同一份快照。
     * 反例：每次拼提示都重新生成，模型会在同一轮里读到两个不同的现实。
     */
    @Test
    fun overallSnapshotIsStableWithinOneTurn() = runTest {
        val env = object : FakeEnv() {
            var built = 0
            // 模拟 ChatRepo：消息开头生成一次并缓存，之后反复取用
            var cache = ""
            override fun chatMode(): ChatMode = ChatMode.OVERALL
            override fun overallSnapshot(): String = cache
            fun startTurn() {
                built++
                cache = "SNAP_BUILD_$built"
            }
        }
        env.startTurn()
        val api = Scripted(
            Turn("", listOf(ToolCall("c1", "get_overview", "{}"))),
            Turn("看完了。", emptyList()),
        )
        Agent(api, env).reply(emptyList(), "现在整体什么局面")
        // 两轮模型调用，拿到的是同一份
        assertEquals(2, api.systems.size)
        assertTrue(api.systems[0], api.systems[0].contains("SNAP_BUILD_1"))
        assertEquals(api.systems[0], api.systems[1])
        assertEquals(1, env.built)
    }

    @Test
    fun topicKeysAreStable() {
        assertEquals("long_term_goals", OverallSnapshot.Topic.LONG_TERM_GOALS.key)
        assertEquals("weekly_outcomes", OverallSnapshot.Topic.WEEKLY_OUTCOMES.key)
        assertEquals("blockers", OverallSnapshot.Topic.BLOCKERS.key)
        assertEquals("hypotheses", OverallSnapshot.Topic.HYPOTHESES.key)
        assertEquals("execution_feedback", OverallSnapshot.Topic.EXECUTION_FEEDBACK.key)
        assertEquals(OverallSnapshot.Topic.BLOCKERS, OverallSnapshot.Topic.of("blockers"))
        assertNull(OverallSnapshot.Topic.of("nope"))
        // 每个主题都有能直接放进输入框的话
        OverallSnapshot.Topic.entries.forEach { assertTrue(it.chip, it.prompt.isNotBlank() && it.chip.isNotBlank()) }
    }
}
