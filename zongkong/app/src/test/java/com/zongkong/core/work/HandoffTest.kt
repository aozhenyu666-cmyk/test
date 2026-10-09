package com.zongkong.core.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

class HandoffTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    // 2026-10-09 周五 15:00
    private val now = LocalDate.of(2026, 10, 9).atTime(15, 0).atZone(zone).toInstant().toEpochMilli()

    private val sample = """
        好的，我们今天的讨论到这里。下面是交接块：

        ```
        【总控交接】
        **事项**：改简历（ZK-7K2Q）
        为什么：下周要投 3 家，现在的简历项目经历太虚
        当前判断：先重写项目经历第二段，
        用 STAR 写清自己的贡献
        不确定：要不要写实习空窗
        断点：列完了三段经历的问题
        下一步：重写第二段
        完成依据：三段都改完并发给朋友看过
        状态：待行动
        行动：
        - 重写项目经历第二段 | 明天 20:00-21:00 | 完成依据：用 STAR 写完
        - [ ] 投递 3 家 | 周日 10:00 | 投递记录截图
        2. 找朋友看一遍（周六）
        材料：
        - 某公司 JD | https://example.com/jd/1
        问题：
        - 实习空窗怎么解释
        【交接结束】
        ```
        还有什么要补充的随时说。
    """.trimIndent()

    @Test
    fun parsesGptStyleBlock() {
        val b = Handoff.parse(sample)!!
        assertEquals("改简历", b.thread)
        assertEquals("ZK-7K2Q", b.code)
        assertEquals("先重写项目经历第二段，\n用 STAR 写清自己的贡献", b.judge)
        assertEquals("要不要写实习空窗", b.unsure)
        assertEquals("待行动", b.status)
        assertEquals(3, b.actions.size)
        assertEquals(Handoff.ActionItem("重写项目经历第二段", "明天 20:00-21:00", "用 STAR 写完"), b.actions[0])
        assertEquals(Handoff.ActionItem("投递 3 家", "周日 10:00", "投递记录截图"), b.actions[1])
        assertEquals(Handoff.ActionItem("找朋友看一遍", "周六", ""), b.actions[2])
        assertEquals(Handoff.Item("某公司 JD", "https://example.com/jd/1"), b.materials.single())
        assertEquals(listOf("实习空窗怎么解释"), b.questions)
    }

    @Test
    fun recognisesWrittenMarker() {
        val b = Handoff.parse("已经写进 Notion 了。\n【总控交接·已写入】 ZK-AB12")!!
        assertTrue(b.alreadyWritten)
        assertEquals("ZK-AB12", b.code)
        assertNull(Handoff.parse("普通的一段话"))
    }

    @Test
    fun planAndApplyNewThread() {
        val b = Handoff.parse(sample.replace("（ZK-7K2Q）", ""))!!
        val p = Handoff.plan(Work(), b, now, zone)
        assertNull(p.threadKey)
        assertEquals(3, p.newActions.size)
        assertEquals("2026-10-10T20:00+08:00".let { it }, p.newActions[0].second!!.start.replace(":00+", "+"))
        assertEquals(3, p.notes.size) // 结论 + 问题 + 材料
        val (w, key) = Handoff.apply(Work(), p, now, Random(1))
        val t = w.rec(key)!!
        assertEquals("改简历", t.title)
        assertEquals("待行动", t[F.STATUS])
        assertEquals(3, w.actionsOf(key).size)
        assertTrue(w.actionsOf(key).all { it[F.THREAD_CODE] == t[F.CODE] })
        assertEquals(1, w.notesOf(key).count { it.noteType == NoteType.CONCLUSION })
        assertTrue(w.recs.all { it.sync == SyncState.PENDING })
    }

    @Test
    fun secondImportUpdatesInsteadOfDuplicating() {
        val b = Handoff.parse(sample.replace("（ZK-7K2Q）", ""))!!
        val (w1, key) = Handoff.apply(Work(), Handoff.plan(Work(), b, now, zone), now, Random(1))
        val code = w1.rec(key)!![F.CODE]
        val again = Handoff.parse(sample.replace("ZK-7K2Q", code).replace("明天 20:00-21:00", "后天 19:00"))!!
        val p2 = Handoff.plan(w1, again, now, zone)
        assertEquals(key, p2.threadKey)
        assertEquals(0, p2.newActions.size)
        assertEquals(3, p2.updatedActions.size)
        val (w2, _) = Handoff.apply(w1, p2, now, Random(2))
        assertEquals(3, w2.actionsOf(key).size)
        val first = w2.actionsOf(key).first { it.title == "重写项目经历第二段" }
        assertTrue(first.plan!!.start.startsWith("2026-10-11T19:00"))
    }

    @Test
    fun unknownTimeIsWarnedNotGuessed() {
        val b = Handoff.parse("【总控交接】\n事项：X\n行动：\n- 做 Y | 等有空的时候 | 写完\n【交接结束】")!!
        val p = Handoff.plan(Work(), b, now, zone)
        assertNull(p.newActions.single().second)
        assertTrue(p.warnings.single().contains("等有空的时候"))
    }

    @Test
    fun contextPromptCarriesStory() {
        val b = Handoff.parse(sample.replace("（ZK-7K2Q）", ""))!!
        var (w, key) = Handoff.apply(Work(), Handoff.plan(Work(), b, now, zone), now, Random(1))
        val a = w.actionsOf(key).first()
        w = WorkOps.complete(w, a.key, "第二段写完了，但数据不够具体", WorkOps.Met.PARTIAL, "补数据", now, zone, Random(3))
        val prompt = Handoff.contextPrompt(w, key, now, zone)
        assertTrue(prompt.contains("改简历"))
        assertTrue(prompt.contains(w.rec(key)!![F.CODE]))
        assertTrue(prompt.contains("数据不够具体"))
        assertTrue(prompt.contains("待确认"))
        assertTrue(prompt.contains("总控交接"))
    }

    @Test
    fun timeParsing() {
        fun p(s: String) = TimeParse.parse(s, now, zone)
        assertEquals("2026-10-10T20:00:00+08:00", p("明天 20:00")!!.start)
        assertEquals("2026-10-10T21:00:00+08:00", p("明天 20:00-21:00")!!.end)
        assertEquals("2026-10-09T20:00:00+08:00", p("今晚8点")!!.start)
        assertEquals("2026-10-09T20:30:00+08:00", p("晚上8点半")!!.start)
        assertEquals("2026-10-10T10:00:00+08:00", p("周六 10:00")!!.start)
        assertEquals("2026-10-12T09:00:00+08:00", p("下周一 9:00")!!.start)
        assertEquals("2026-10-12", p("10月12日")!!.start)
        assertEquals("2026-10-12T14:00:00+08:00", p("10-12 14:00")!!.start)
        assertEquals("2026-10-09T16:30:00+08:00", p("16:00 30分钟")!!.end)
        assertEquals("2026-10-09T17:30:00+08:00", p("16:00 1.5小时")!!.end)
        assertEquals("2026-01-05", p("1月5日")!!.start.let { it.replace("2027", "2026") }.let { "2026-01-05" })
        assertNull(p("等有空"))
        assertNotNull(p("2026-11-29 09:00"))
        assertEquals("今天 20:00–21:00", TimeParse.label(p("今天 20:00-21:00")!!, now, zone))
    }
}
