package com.zongkong.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-5：三条出口叠成的死锁。
 *
 * 死锁长这样：关卡从开放一直拦到当天结束（没尽头）+ 严格态度不给紧急放行 +
 * 休假要等 24 小时。于是「交不了 / 放不了 / 休不了」，唯一出路是卸载或关无障碍。
 *
 * 这里锁住两件事：
 * 1. 关卡拦截有尽头（[Config.blockMaxHours]），到期后放行但账照记；
 * 2. 上限只解除「关卡」这一种拦截，额度用超了照样拦。
 */
class BlockCapTest {

    /** 06:00 开放就拦、22:00 截止的关卡；默认上限 6 小时 → 12:00 后不再拦。 */
    private fun fromOpen(capHours: Int = Config.DEFAULT_BLOCK_MAX_HOURS) = Config(
        gates = listOf(gate(open = 6 * 60, deadline = 22 * 60, block = BlockMode.FROM_OPEN)),
        silenceHours = 0,
        dailyQuotaMin = 0,
        blockMaxHours = capHours,
    )

    private val date = "2026-10-09"

    @Test
    fun blocksRightUpToTheCap() {
        val s = Engine.evaluate(fromOpen(), DayLog(date), at(11), ZONE)
        assertTrue("06:00 开放，11:00 还在 6 小时上限内，必须照常拦", s.strict)
        assertTrue(s.gates[0].blocking)
        assertFalse(s.gates[0].blockExpired)
        assertTrue(s.reasons.single() is Reason.GateDue)
    }

    @Test
    fun stopsBlockingAfterTheCap() {
        // 06:00 开始拦 + 6 小时 = 12:00 到期
        val s = Engine.evaluate(fromOpen(), DayLog(date), at(13), ZONE)
        assertFalse("拦到上限就不该再挡手机：${s.reasons.map { it.text }}", s.strict)
        assertFalse(s.gates[0].blocking)
        assertTrue(s.gates[0].blockExpired)
        assertTrue(s.blockExpired)
        assertTrue("到期后不该再有 GateDue 原因", s.reasons.none { it is Reason.GateDue })
    }

    @Test
    fun capExpiryIsNotCompletion() {
        val s = Engine.evaluate(fromOpen(), DayLog(date), at(13), ZONE)
        assertEquals("到期放行不等于交了", 0, s.doneCount)
        assertFalse(s.gates[0].done)
        assertNull(s.gates[0].passed)
        assertEquals(GatePhase.OPEN, s.gates[0].phase)
        assertEquals("欠账放行", s.headline)
        assertTrue("通知里要说清是放行不是做到：${Engine.summary(s)}", Engine.summary(s).contains("还欠着"))
    }

    @Test
    fun zeroCapKeepsTheOldEndlessBehaviour() {
        val s = Engine.evaluate(fromOpen(capHours = 0), DayLog(date), at(21), ZONE)
        assertTrue("0 = 不设上限（旧行为），21:00 仍拦", s.strict)
        assertFalse(s.gates[0].blockExpired)
        assertEquals("严管", s.headline)
    }

    @Test
    fun submittingAfterTheCapClearsEverything() {
        val c = fromOpen()
        val expired = Engine.evaluate(c, DayLog(date), at(13), ZONE)
        val day = DayOps.submit(DayLog(date), expired.gates[0], "补上的内容", passVerdict(), at(13))
        val s = Engine.evaluate(c, day, at(13, 30), ZONE)
        assertFalse(s.strict)
        assertFalse(s.gates[0].blockExpired)
        assertEquals(GatePhase.DONE, s.gates[0].phase)
        assertEquals(1, s.doneCount)
        assertEquals("放行", s.headline)
    }

    @Test
    fun capOnlyEndsGateBlockingNotQuota() {
        // 关卡到期了，但今天的娱乐额度也用完了 —— 额度这条照样拦。
        val c = Config(
            gates = listOf(gate(open = 6 * 60, deadline = 22 * 60, block = BlockMode.FROM_OPEN)),
            silenceHours = 0,
            dailyQuotaMin = 60,
            blockMaxHours = 6,
        )
        val day = DayLog(date, playSeconds = mapOf("com.douyin" to 61 * 60L))
        val s = Engine.evaluate(c, day, at(13), ZONE)
        assertTrue("额度用完必须继续拦", s.strict)
        assertTrue(s.gates[0].blockExpired)
        assertTrue(s.reasons.any { it is Reason.Quota })
    }

    @Test
    fun shorterCapIsARelaxationAndWaits24h() {
        val c = Config()
        val out = Policy.setRules(c, Policy.rulesOf(c).copy(blockMaxHours = 3), at(9))
        assertTrue("调短拦截上限 = 放宽，要等 24 小时：$out", out.later.isNotEmpty())
        assertEquals("没生效前配置不能变", Config.DEFAULT_BLOCK_MAX_HOURS, out.config.blockMaxHours)
    }

    @Test
    fun longerCapIsATighteningAndAppliesNow() {
        val c = Config()
        val out = Policy.setRules(c, Policy.rulesOf(c).copy(blockMaxHours = 12), at(9))
        assertTrue("调长拦截上限 = 收紧，立即生效：$out", out.now.isNotEmpty())
        assertEquals(12, out.config.blockMaxHours)
    }
}

/**
 * P0-5 的第二条：严格态度不再「关闭」紧急放行。
 *
 * 「信用低 → 严格 → 不给放行 → 解除不了严管 → 继续扣分 → 信用更低」是个自我加固的环。
 * 现在严格态度照样给，但理由门槛从 20 字提高到 60 字，并留痕（日终验收里点名）。
 */
class EmergencyUnderStrictTest {

    private val cfg = Config(
        gates = listOf(gate(open = 6 * 60, deadline = 22 * 60, block = BlockMode.FROM_OPEN)),
        silenceHours = 0,
        dailyQuotaMin = 0,
    )
    private val now = at(9)
    private val day = DayLog("2026-10-09")
    private val status = Engine.evaluate(cfg, day, now, ZONE)

    /** 明显过 60 字。 */
    private val longReason =
        "今天晚上必须把客户的交付稿修完，对方明早九点前就要看图，我现在唯一能用的电脑就是这一台，" +
            "手机修不了图，只能现在打开软件做完发过去，白天其他时间都排满了没有空档"

    /** 过 20 字，不到 60 字。 */
    private val mediumReason = "今晚必须用电脑修完客户的交付图稿，明早九点要交给对方"

    /** 不到 20 字。 */
    private val shortReason = "现在就要用电脑赶稿"

    @Test
    fun setupIsActuallyStrict() {
        assertTrue("前提：此刻正在严管", status.strict)
        assertEquals("前提：今天的紧急放行还没用", 1, status.emergencyLeft)
        assertTrue("前提：话术要够 60 字", TextCheck.effectiveChars(longReason) >= DayOps.STRICT_EMERGENCY_CHARS)
        assertTrue("前提：话术要够 20 字", TextCheck.effectiveChars(mediumReason) >= DayOps.NORMAL_EMERGENCY_CHARS)
        assertTrue("前提：短发话术不够 20 字", TextCheck.effectiveChars(shortReason) < DayOps.NORMAL_EMERGENCY_CHARS)
    }

    @Test
    fun strictStanceStillGrantsWithLongerReason() {
        val (next, msg) = DayOps.emergency(day, cfg, status, longReason, now, strictStance = true)
        assertNotNull("严格态度也必须能给：$msg", next)
        assertTrue(msg.startsWith("已放行"))
        assertTrue("要说清这笔账记着：$msg", msg.contains("记着"))
        val e = next!!.emergencies.single()
        assertEquals(now + cfg.emergencyMinutes * 60_000L, e.until)
        assertEquals("严格态度下的放行要留痕", "紧急放行（严格态度）", next.outbox.last().result)
    }

    @Test
    fun strictStanceRaisesTheBarInsteadOfClosingTheDoor() {
        val (next, msg) = DayOps.emergency(day, cfg, status, mediumReason, now, strictStance = true)
        assertNull(next)
        assertTrue("要告诉用户门槛是 60 字：$msg", msg.contains("60"))
        assertFalse("不能再说「不给」：$msg", msg.contains("不给"))
    }

    @Test
    fun normalStanceKeepsTheOldTwentyCharBar() {
        val (next, msg) = DayOps.emergency(day, cfg, status, mediumReason, now, strictStance = false)
        assertNotNull("普通态度 20 字就该给：$msg", next)
        assertEquals("紧急放行", next!!.outbox.last().result)
    }

    @Test
    fun normalStanceStillRefusesTooShort() {
        val (next, msg) = DayOps.emergency(day, cfg, status, shortReason, now, strictStance = false)
        assertNull(next)
        assertTrue(msg.contains("20"))
    }

    @Test
    fun quotaOfTheDayStillCapsIt() {
        // 上一次紧急放行已经过期（否则此刻还在放行中，压根不该再来申请），但名额已经用掉了。
        val used = day.copy(emergencies = listOf(EmergencyPass(now - 120_000L, now - 60_000L, "用过了")))
        val s = Engine.evaluate(cfg, used, now, ZONE)
        assertTrue("前提：名额用完了但仍在严管", s.strict)
        assertEquals(0, s.emergencyLeft)
        val (next, msg) = DayOps.emergency(used, cfg, s, longReason, now, strictStance = true)
        assertNull("每天的次数是硬的，严格态度也不额外发", next)
        assertTrue("要说清是次数用完了：$msg", msg.contains("用完"))
    }

    @Test
    fun grantedPassActuallyLiftsTheBlock() {
        val (next, _) = DayOps.emergency(day, cfg, status, longReason, now, strictStance = true)
        val s = Engine.evaluate(cfg, next!!, now + 1_000L, ZONE)
        assertFalse("放行了就不该还在严管", s.strict)
        assertEquals("紧急放行中", s.headline)
    }
}
