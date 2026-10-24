package cn.companion.pet.core.companion

import cn.companion.pet.core.probe.ProbeMoment
import cn.companion.pet.core.scene.Scene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 召回要把“以前聊过、和此刻相关”的事带回来，而不是把刚说过的话再塞一遍。
 */
class RecallRelevanceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-23T08:00:00Z"), zone)
    private val today: LocalDate = LocalDate.of(2026, 10, 23)
    private val yesterday: LocalDate = today.minusDays(1)

    private fun at(d: LocalDate, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(d.atTime(hour, minute), zone).toInstant().toEpochMilli()

    private fun chat(events: EventLog, said: String, occurredAt: Long) {
        val input = events.append(EventKind.USER_INPUT, said, occurredAt = occurredAt)
        events.append(EventKind.EXCHANGE, "- 普通 | 嗯？ | $said", occurredAt = occurredAt, sourceIds = listOf(input.id))
    }

    @Test
    fun olderRelevantMemoryIsNotCrowdedOutByTodaysChatter() {
        val events = EventLog(tmp.root, clock, zone)
        val facts = FactStore(tmp.root, clock, zone)
        chat(events, "我最近在学吉他，指法好难", at(yesterday, 21))
        // 今天聊了 12 次，昨天那条已经滑出“最近交流”窗口，只能靠召回接上。
        listOf(
            "我中午吃了一碗牛肉面",
            "我下午有点困，想睡一会",
            "我刚开完一个很长的会",
            "我今天的地铁特别挤",
            "我晚上可能要加一会班",
            "我的咖啡已经喝完了",
            "我把周报写完了",
            "我在想周末去哪里玩",
            "我刚收到一个快递",
            "我的手机快没电了",
            "我把房间收拾了一下",
            "我准备点个外卖",
        ).forEachIndexed { i, said -> chat(events, said, at(today, 8, i * 4)) }

        val moment = ProbeMoment(
            timeText = "16:00",
            scene = Scene.NORMAL,
            foregroundLabel = "哔哩哔哩",
            trajectory = "在哔哩哔哩看吉他教学视频 6 分钟",
            screenText = null,
        )
        val user = CompanionContextAssembler(events, facts, zone).assemble("我是桌宠。", moment, null, clock.millis())[1].content
        val recallBlock = user.substringAfter("# 我记得的相关的事").substringBefore("# 最近的交流")

        assertTrue("昨天说过的吉他应该被召回：\n$recallBlock", recallBlock.contains("吉他"))
        assertFalse("今天刚说过、已经在最近交流里的话不该重复进召回：\n$recallBlock", recallBlock.contains("牛肉面"))
    }

    @Test
    fun commonCharactersAloneAreNotRelevance() {
        val events = EventLog(tmp.root, clock, zone)
        val facts = FactStore(tmp.root, clock, zone)
        events.append(EventKind.USER_INPUT, "我最近很喜欢猫", occurredAt = at(yesterday, 20))
        val recall = MemoryRecall(events, facts, zone)

        // 只共享“我”“了”“的”这类常用字，不算相关。
        assertTrue(recall.recall("我今天有点累了", at(today, 10)).isEmpty())
        // 真正的主题词仍然命中。
        assertEquals(1, recall.recall("你家的猫怎么样", at(today, 10)).size)
    }
}
