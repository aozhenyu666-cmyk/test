package com.zongkong.core

import com.zongkong.core.boss.Boss
import com.zongkong.core.boss.BossConfig
import com.zongkong.core.boss.ClaimVerdict
import com.zongkong.core.boss.CommitStatus
import com.zongkong.core.boss.DevEvent
import com.zongkong.core.boss.Dossier
import com.zongkong.core.boss.Stance
import com.zongkong.core.boss.Timeline
import com.zongkong.core.hq.Hq
import com.zongkong.core.hq.HqConfig
import com.zongkong.core.hq.HqTrigger
import com.zongkong.core.work.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BossTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun silenceCostsCredibility() {
        val now = at(12, 20)
        // 问了 3 次：一次及时回了，一次过了 3 小时才回，一次一直没回；还有一次刚问，还没到 2 小时
        var d = Boss.asked(Dossier(), "早上", at(12, 8))
        d = Boss.answered(d, at(12, 8, 30))
        d = Boss.asked(d, "中午", at(12, 12))
        d = Boss.answered(d, at(12, 15))
        d = Boss.asked(d, "回访", at(12, 16))
        d = Boss.asked(d, "问候", at(12, 19))
        val asks = Boss.ledger(d, emptyList(), now).filter { it.why.startsWith("问了没回") }
        // 中午那次 15:00 才回（晚了），16:00 那次一直没回；19:00 那次还没到 2 小时
        assertEquals(listOf(at(12, 14), at(12, 18)), asks.map { it.at })
        assertTrue(asks.all { it.delta == -3 })
        d = Boss.asked(Dossier(), "回访", at(12, 16))
        assertEquals(-3, Boss.ledger(d, emptyList(), now).sumOf { it.delta })
        assertEquals(Boss.BASE - 3, Boss.credibility(d, emptyList(), now))

        // 提醒了没动：5 次提醒，一次后面开始了；没动的 4 次一天只扣 3 次
        val ev = mutableListOf<Event>()
        (9..13).forEach { h -> ev += Event(at(12, h), "remind", "a$h") }
        ev += Event(at(12, 9, 20), "start", "a9")
        // 做完 5 步只加 4 次
        (14..18).forEach { h -> ev += Event(at(12, h, 10), "done:YES", "b$h") }
        ev += Event(at(12, 19, 40), "remind", "late") // 还没过 45 分钟，不扣
        val items = Boss.ledger(Dossier(), emptyList(), now, ev)
        assertEquals(3, items.count { it.why == "提醒了没动" })
        assertEquals(4, items.count { it.why == "做完一步" })
        assertEquals(Boss.BASE - 6 + 4, Boss.credibility(Dossier(), emptyList(), now, ev))
        // 一直不理会把态度压下去
        val ignored = (1..9).fold(Dossier()) { acc, i -> Boss.asked(acc, "问候", at(12, 6) + i * 60_000L) }
        assertEquals(Stance.WARY, Boss.stance(ignored, emptyList(), now))
        assertTrue(Boss.brief(ignored, emptyList(), now, zone).contains("沉默"))
    }

    @Test
    fun credibilityMovesWithEvidenceAndPromises() {
        val now = at(12, 20)
        var d = Dossier()
        assertEquals(70, Boss.credibility(d, emptyList(), now))
        assertEquals(Stance.NORMAL, Boss.stance(d, emptyList(), now))
        d = Boss.addClaim(d, "s1", "做了 5 小时饭", at(12, 14), at(12, 19), ClaimVerdict.CONTRADICTED, "抖音 2 小时", now)
        d = Boss.commit(d, "y1", "10 点前做一套题", at(12, 22), "拍照", now)
        d = Boss.resolve(d, "y1", CommitStatus.BROKEN, "没照片", Stance.WARY, now).getOrThrow()
        // 70 − 12 − 10 = 48
        assertEquals(48, Boss.credibility(d, emptyList(), now))
        assertEquals(Stance.WARY, Boss.stance(d, emptyList(), now))
        // 总控时间做完 +3，提前退出 −5
        val cfg = HqConfig()
        val ok = Hq.finish(Hq.start("a", HqTrigger.SCHEDULED, cfg, at(11, 21, 30)), at(11, 22, 0))
        val quit = Hq.exitEarly(Hq.start("b", HqTrigger.SCHEDULED, cfg, at(12, 21, 30)), at(12, 21, 40), "出门")
        assertEquals(46, Boss.credibility(d, listOf(ok, quit), now + 3_600_000))
        // 14 天前的不算
        assertEquals(70, Boss.credibility(d, emptyList(), now + 15 * 86_400_000L))
        // 一路做到 → 信任
        var good = Dossier()
        repeat(4) { i ->
            good = Boss.commit(good, "k$i", "第 $i 件", now + 1000, "看记录", now)
            good = Boss.resolve(good, "k$i", CommitStatus.KEPT, "时间线里看到了", Stance.NORMAL, now).getOrThrow()
        }
        assertEquals(86, Boss.credibility(good, emptyList(), now))
        assertEquals(Stance.TRUST, Boss.stance(good, emptyList(), now))
    }

    @Test
    fun rulesTheModelCannotTalkAround() {
        val now = at(12, 20)
        var d = Boss.addClaim(Dossier(), "s1", "我一直在学习", at(12, 14), at(12, 18), ClaimVerdict.PENDING, "", now)
        // 属实要依据
        assertTrue(Boss.judge(d, "s1", ClaimVerdict.VERIFIED, "", now).isFailure)
        d = Boss.judge(d, "s1", ClaimVerdict.VAGUE, "说不出学了什么", now).getOrThrow()
        // 判过的只能改差，不能改好
        assertTrue(Boss.judge(d, "s1", ClaimVerdict.VERIFIED, "他保证是真的", now).isFailure)
        assertTrue(Boss.judge(d, "s1", ClaimVerdict.CONTRADICTED, "截图里是游戏", now).isSuccess)

        // 免约定：要理由，七天一次，严格时不行
        d = Boss.commit(d, "y1", "做一套题", at(12, 22), "拍照", now)
        d = Boss.commit(d, "y2", "背 30 个单词", at(12, 23), "截图", now)
        assertTrue(Boss.resolve(d, "y1", CommitStatus.EXCUSED, "太累", Stance.NORMAL, now).isFailure)
        assertTrue(Boss.resolve(d, "y1", CommitStatus.EXCUSED, "发烧 38.5 度，有体温计照片", Stance.STRICT, now).isFailure)
        d = Boss.resolve(d, "y1", CommitStatus.EXCUSED, "发烧 38.5 度，有体温计照片", Stance.NORMAL, now).getOrThrow()
        assertTrue(Boss.resolve(d, "y2", CommitStatus.EXCUSED, "还是不舒服，明天再背吧", Stance.NORMAL, now).isFailure)
        // 不回应 = 没做到
        val swept = Boss.sweep(d, at(14, 0))
        assertEquals(CommitStatus.BROKEN, swept.commitments.first { it.id == "y2" }.status)
        assertEquals(CommitStatus.EXCUSED, swept.commitments.first { it.id == "y1" }.status)

        // 态度只能自己收紧，到点恢复
        assertTrue(Boss.escalate(d, Stance.NORMAL, 5, "放松一下", Stance.WARY, now).isFailure)
        val esc = Boss.escalate(d, Stance.STRICT, 5, "又对不上", Stance.WARY, now).getOrThrow()
        assertEquals(Stance.STRICT, Boss.stance(esc, emptyList(), now))
        assertTrue(Boss.stance(esc, emptyList(), now + 6 * 3_600_000L) != Stance.STRICT)
    }

    @Test
    fun checksComeDueOnce() {
        val now = at(12, 20)
        var d = Boss.commit(Dossier(), "y1", "做一套题", at(12, 22), "拍照", now)
        d = Boss.schedule(d, "k1", at(12, 21), "看 21 点后有没有刷手机", now)
        assertTrue(Boss.dueChecks(d, at(12, 20, 30)).isEmpty())
        assertEquals(listOf("k1"), Boss.dueChecks(d, at(12, 21, 1)).map { it.id })
        assertEquals(2, Boss.dueChecks(d, at(12, 22, 6)).size)
        d = Boss.markDone(d, "k1")
        assertEquals(listOf("ky1"), Boss.dueChecks(d, at(12, 22, 6)).map { it.id })
    }

    @Test
    fun clockPhrases() {
        val now = at(12, 19, 30)
        assertEquals(at(12, 14), Boss.clock("14:00", now, zone))
        assertEquals(at(12, 15), Boss.clock("下午3点", now, zone))
        assertEquals(at(11, 22, 30), Boss.clock("昨天 22:30", now, zone))
        // 晚于现在的“今天”时间算昨天
        assertEquals(at(11, 21), Boss.clock("21:00", now, zone))
        assertEquals(now - 3 * 3_600_000L, Boss.clock("3小时前", now, zone))
        assertEquals(now, Boss.clock("现在", now, zone))
        assertNull(Boss.clock("一会儿", now, zone))
    }

    @Test
    fun timelineSummaryForInterrogation() {
        val douyin = "com.ss.android.ugc.aweme"
        val ev = listOf(
            DevEvent(at(12, 13, 0), Timeline.OFF),
            DevEvent(at(12, 14, 20), douyin),
            DevEvent(at(12, 14, 50), Timeline.OFF),
            DevEvent(at(12, 14, 50), Timeline.OFF),
            DevEvent(at(12, 14, 50) + 30_000, douyin), // 熄屏不到 1 分钟：算连着
            DevEvent(at(12, 16, 0), "com.tencent.mm"),
            DevEvent(at(12, 16, 10), Timeline.GAP),
            DevEvent(at(12, 17, 0), Timeline.OFF),
        )
        val segs = Timeline.segments(ev, at(12, 14), at(12, 19))
        assertEquals(Timeline.OFF, segs.first().pkg)
        assertEquals(at(12, 14), segs.first().from)
        val text = Timeline.summarize(segs, at(12, 14), at(12, 19), zone, { mapOf(douyin to "抖音", "com.tencent.mm" to "微信")[it] ?: it }, { it == douyin })
        assertTrue(text, text.contains("14:00–19:00（5 小时）"))
        assertTrue(text, text.contains("抖音 1 小时 39 分〔娱乐〕"))
        assertTrue(text, text.contains("最长连续：抖音 14:20–16:00（1 小时 40 分）"))
        assertTrue(text, text.contains("没有记录 50 分钟"))
        assertTrue(text, text.contains("流水："))
        // 没有任何事件：整段都是“没有记录”
        val none = Timeline.summarize(Timeline.segments(emptyList(), at(12, 14), at(12, 15)), at(12, 14), at(12, 15), zone, { it }, { false })
        assertTrue(none, none.contains("没有记录 1 小时"))
    }

    @Test
    fun watchingLessWaitsAndModelIsLocked() {
        val now = at(12, 20)
        val c0 = Config(ai = AiConfig("https://api.deepseek.com", "sk-1", "deepseek-chat"))
        // 多看立即生效
        val on = Policy.setBoss(c0, BossConfig(shots = true, shotsPerHour = 4), now)
        assertTrue(on.config.boss.shots)
        // 关掉截图要等 24 小时
        val off = Policy.setBoss(on.config, on.config.boss.copy(shots = false), now)
        assertTrue(off.config.boss.shots)
        assertEquals(1, off.config.pending.size)
        assertEquals(false, Policy.applyDue(off.config, now + 25 * 3_600_000L).boss.shots)
        // 能用的模型不能临时换
        val swap = Policy.setAi(c0, AiConfig("https://api.deepseek.com", "sk-1", "some-obedient-model"), currentWorks = true, now = now)
        assertEquals("deepseek-chat", swap.config.ai.model)
        assertEquals("some-obedient-model", Policy.applyDue(swap.config, now + 25 * 3_600_000L).ai.model)
        // 坏了可以马上换；只加看图模型也马上生效
        assertEquals("deepseek-reasoner", Policy.setAi(c0, c0.ai.copy(model = "deepseek-reasoner"), currentWorks = false, now = now).config.ai.model)
        assertEquals("qwen-vl-max", Policy.setAi(c0, c0.ai.copy(visionModel = "qwen-vl-max"), currentWorks = true, now = now).config.ai.visionModel)
        assertNotNull(swap.later.firstOrNull())
        assertFalse(Policy.setAi(Config(), c0.ai, currentWorks = false, now = now).config.pending.isNotEmpty())
    }
}
