package com.zongkong.core.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PlaybookTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(h: Int, m: Int = 0, day: Int = 16) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private val text = """
        09:00-10:30 资料分析两套 | B: 下雨不去图书馆，在家做一套
        14:00-14:20 背单词
        23:30-00:30 复盘 | B方案：只写三行
        这一行写错了
    """.trimIndent()

    @Test
    fun parsesLinesWithPlanB() {
        val (blocks, bad) = Playbook.parse(text, 1)
        assertEquals(3, blocks.size)
        assertEquals(listOf("这一行写错了"), bad)
        assertEquals(9 * 60, blocks[0].start)
        assertEquals("下雨不去图书馆，在家做一套", blocks[0].planB)
        assertEquals("", blocks[1].planB)
        // 过了午夜还算当天
        assertEquals(24 * 60 + 30, blocks[2].end)
        assertEquals("只写三行", blocks[2].planB)
        assertTrue(Playbook.format(DayScript("2026-10-16", blocks)).contains("09:00-10:30 资料分析两套 | B: 下雨不去图书馆，在家做一套"))
    }

    @Test
    fun lockedScriptNeedsReasonToChangeAndKeepsStartedBlocks() {
        val (blocks, _) = Playbook.parse(text, 1)
        var s = Playbook.write(null, "2026-10-16", blocks, "", "你", at(8)).first!!
        s = Playbook.lock(s, at(8))
        s = Playbook.begin(s, blocks[0].id)
        val (nb, _) = Playbook.parse("10:00-11:00 别的事", 2)
        assertEquals("预案已经定稿，改要写一句为什么（至少 4 个字）", Playbook.write(s, s.date, nb, "", "你", at(9)).second)
        // 和已经开始的段重叠：不行
        assertNotNull(Playbook.write(s, s.date, nb, "临时有课", "你", at(9)).second)
        val (ok, _) = Playbook.parse("11:00-12:00 改成下午的事", 3)
        val s2 = Playbook.write(s, s.date, ok, "临时有课", "你", at(9)).first!!
        assertEquals(listOf("资料分析两套", "改成下午的事"), s2.blocks.map { it.what })
        assertEquals("临时有课", s2.revisions.last().why)
    }

    @Test
    fun skippingNeedsReasonAndPlanBNeedsOne() {
        val (blocks, _) = Playbook.parse(text, 1)
        val s = Playbook.lock(DayScript("2026-10-16", blocks), at(8))
        assertNotNull(Playbook.skip(s, blocks[1].id, "", at(14)).second)
        assertNotNull(Playbook.planB(s, blocks[1].id, "", at(14)).second)
        val (b, err) = Playbook.planB(s, blocks[0].id, "下雨", at(9))
        assertNull(err)
        assertEquals(BlockState.PLAN_B, b.block(blocks[0].id)!!.state)
        assertEquals("「资料分析两套」改走 B 方案", b.revisions.last().what)
    }

    @Test
    fun speaksAtKeyNodesOnceEach() {
        val (blocks, _) = Playbook.parse(text, 1)
        var s = DayScript("2026-10-16", blocks)
        // 草稿不出声
        assertTrue(Playbook.due(s, at(8, 56), zone).isEmpty())
        s = Playbook.lock(s, at(8))
        val pre = Playbook.due(s, at(8, 56), zone).single()
        assertEquals(Playbook.Node.PRE, pre.node)
        s = Playbook.markPrompted(s, pre.block.id, pre.node)
        assertTrue(Playbook.due(s, at(8, 57), zone).isEmpty())
        assertEquals(Playbook.Node.START, Playbook.due(s, at(9, 0), zone).single().node)
        // 一直没开机，10:20 才看：只说最近的“过半”，前面的不补
        val late = Playbook.due(s, at(9, 50), zone).single()
        assertEquals(Playbook.Node.MID, late.node)
        s = Playbook.markPrompted(s, late.block.id, late.node)
        assertTrue(Playbook.due(s, at(9, 52), zone).isEmpty())
        // 20 分钟的段不问过半
        s = Playbook.begin(s, blocks[1].id)
        assertTrue(Playbook.due(s, at(14, 10), zone).isEmpty())
        assertEquals(Playbook.Node.END, Playbook.due(s, at(14, 20), zone).single().node)
        assertEquals(at(10, 30), Playbook.nextNode(s, at(10, 0), zone))
        // 深夜那段：00:30 结束，属于 16 号的逻辑日
        assertEquals(Playbook.Node.END, Playbook.due(Playbook.begin(s, blocks[2].id), at(0, 30, day = 17), zone).single { it.block.id == blocks[2].id }.node)
    }
}
