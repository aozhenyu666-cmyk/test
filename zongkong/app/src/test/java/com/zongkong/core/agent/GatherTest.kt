package com.zongkong.core.agent

import com.zongkong.core.work.F
import com.zongkong.core.work.Kind
import com.zongkong.core.work.WorkOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlin.random.Random

/**
 * gather：把手上能查的事实一次摆出来。
 *
 * 它解决的不是“查得准”，是“根本不去查”——用户只说一句话时，模型拿着那一句话就开始给建议。
 * 所以测试的重点是：该查的都查到了、查不到的老实说查不到、绝不编。
 */
class GatherTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** 一件事，带为什么 / 完成依据 / 下一步，外加一条行动。 */
    private fun envWithThread(): Triple<FakeEnv, String, String> {
        val e = FakeEnv(nowMs = at(17, 9))
        val (w1, t) = WorkOps.create(
            e.w, Kind.THREAD,
            mapOf(F.TITLE to "国考计划", F.WHY to "想上岸", F.CRITERIA to "笔试过线", F.NEXT to "查报名时间"),
            e.nowMs, Random(11),
        )
        val (w2, a) = WorkOps.create(
            w1, Kind.ACTION,
            mapOf(F.TITLE to "查国考报名时间", F.THREAD to t.key, F.CRITERIA to "记下确切日期", F.PLAN to "2026-10-17T10:00"),
            e.nowMs, Random(12),
        )
        e.w = w2
        return Triple(e, t.key, a.key)
    }

    @Test
    fun gatherBringsTheThreadAndItsActions() = runTest {
        val (e, _, _) = envWithThread()
        val res = ZkTools(e).run("gather", """{"about":"国考计划"}""")
        assertTrue(res.ok)
        assertTrue(res.text, res.text.contains("国考计划"))
        // 事项的底子必须带出来：为什么、完成依据、下一步
        assertTrue(res.text, res.text.contains("为什么"))
        assertTrue(res.text, res.text.contains("笔试过线"))
        // 行动也在
        assertTrue(res.text, res.text.contains("查国考报名时间"))
    }

    @Test
    fun gatherSaysPlainlyWhenTheThingWasNeverRecorded() = runTest {
        val (e, _, _) = envWithThread()
        val res = ZkTools(e).run("gather", """{"about":"健身计划"}""")
        assertTrue(res.ok)
        // 没有就说没有——这比编一个更像样，也正好是该追问的地方
        assertTrue(res.text, res.text.contains("记录里没有这件事"))
        assertTrue(res.text, res.text.contains("该问他"))
    }

    @Test
    fun gatherAdmitsItCannotSeeThePhoneInsteadOfGuessing() = runTest {
        val (e, _, _) = envWithThread()
        // 没有设备事件、也没授权：必须说“查不到”，不能凭空给一段时间线
        e.events.clear()
        val res = ZkTools(e).run("gather", """{"about":"国考计划","days":"3"}""")
        assertTrue(res.ok)
        assertTrue(res.text, res.text.contains("查不到设备记录"))
        assertTrue(res.text, res.text.contains("不要猜"))
    }

    @Test
    fun gatherCarriesPlanAimsAndDossier() = runTest {
        val (e, _, _) = envWithThread()
        e.book = com.zongkong.core.plan.PlanBook(
            aims = listOf(com.zongkong.core.plan.Aim(id = "a1", title = "上岸", deadline = "2026-12-20", created = e.nowMs)),
        )
        val res = ZkTools(e).run("gather", """{"about":"国考计划"}""")
        assertTrue(res.ok)
        assertTrue("长期目标要带出来", res.text.contains("上岸"))
        assertTrue("档案要带出来（说法和约定）", res.text.contains("档案"))
    }

    @Test
    fun gatherWorksWithoutAnyTopic() = runTest {
        val (e, _, _) = envWithThread()
        // 不指定对象也要能查：他不知道该问什么的时候，先把全局摆出来
        val res = ZkTools(e).run("gather", """{}""")
        assertTrue(res.ok)
        assertTrue(res.text, res.text.contains("能查到的事实"))
        assertTrue(res.text, res.text.contains("今天的记录"))
    }

    @Test
    fun gatherIsReadOnly() = runTest {
        val (e, _, _) = envWithThread()
        val before = e.w
        ZkTools(e).run("gather", """{"about":"国考计划"}""")
        // 只读工具：不许改任何记录
        assertEquals(before, e.w)
        assertFalse(e.w.actions().isEmpty())
    }

    @Test
    fun gatherRejectsNothingAndReportsItsSummary() = runTest {
        val (e, _, _) = envWithThread()
        val res = ZkTools(e).run("gather", """{"about":"国考计划"}""")
        assertEquals("把能查的都查了一遍", res.summary)
    }
}
