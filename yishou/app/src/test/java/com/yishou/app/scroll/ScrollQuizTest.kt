package com.yishou.app.scroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollQuizTest {

    @Test
    fun parsesAQuiz() {
        val text = """
            好的。
            【看到】一个博主说县城咖啡店三年倒了一半
            【原文】县城咖啡店为什么活不过三年？
            字幕：房租不贵，但没人喝
            评论：我们县新开的那家天天排队
            【角度】缺口
            【问题】“没人喝”这个结论，靠的是哪个没说的前提？
            【参考】默认倒闭只因需求不足，忽略了选址、价格和客群差异
        """.trimIndent()
        val look = ScrollQuiz.parseLook(text, Lens.CLAIM) as Look.Ask
        val q = look.quiz
        assertEquals(Lens.GAP, q.lens)
        assertEquals("一个博主说县城咖啡店三年倒了一半", q.seen)
        assertTrue(q.question.startsWith("“没人喝”"))
        assertTrue(q.text.contains("天天排队"))
        assertTrue(q.reference.contains("客群"))
        assertNotNull(ScrollQuiz.material(q))
    }

    @Test
    fun skipAndBroken() {
        assertEquals(Look.Skip("桌面"), ScrollQuiz.parseLook("【跳过】桌面", Lens.CLAIM))
        assertNull(ScrollQuiz.parseLook("我看不太清这张图", Lens.CLAIM))
        val q = (ScrollQuiz.parseLook("【问题】评论区会怎么吵？\n【原文】无", Lens.PREDICT) as Look.Ask).quiz
        assertEquals(Lens.PREDICT, q.lens)
        assertEquals("", q.text)
        assertNull(ScrollQuiz.material(q))
    }

    @Test
    fun lensRotationWraps() {
        assertEquals(Lens.CLAIM, Lens.at(0))
        assertEquals(Lens.CLAIM, Lens.at(Lens.entries.size))
        assertEquals(Lens.EVIDENCE, Lens.at(-1))
        assertEquals(Lens.MOTIVE, Lens.of("动机"))
        assertTrue(ScrollQuiz.lookSystem(Lens.PREDICT).contains("评论区"))
    }

    @Test
    fun quickJudge() {
        assertEquals(Verdict.MISS, ScrollQuiz.quick("不知道")?.verdict)
        assertEquals(Verdict.MISS, ScrollQuiz.quick("  ")?.verdict)
        assertEquals(Verdict.MISS, ScrollQuiz.quick("对")?.verdict)
        assertNull(ScrollQuiz.quick("他默认了大家都不喝咖啡"))
    }

    @Test
    fun parsesJudge() {
        assertEquals(Judged(Verdict.HIT, "抓到了需求这个前提"), ScrollQuiz.parseJudge("【判】接住了\n【一句】抓到了需求这个前提"))
        assertEquals(Verdict.MISS, ScrollQuiz.parseJudge("【判】没接住\n【一句】你只复述了内容")?.verdict)
        assertEquals(Verdict.NEAR, ScrollQuiz.parseJudge("差一点。少了价格这一层")?.verdict)
        assertNull(ScrollQuiz.parseJudge("嗯"))
        val q = Quiz("咖啡店", "前提是什么？", Lens.GAP, "需求不足")
        assertTrue(ScrollQuiz.feedback(q, Judged(Verdict.NEAR, "太泛")).contains("参考：需求不足"))
        assertFalse(ScrollQuiz.feedback(q, Judged(Verdict.HIT, "好")).contains("参考"))
        assertTrue(ScrollQuiz.judgeUser(q, "没人喝").endsWith("他的回答：没人喝"))
        assertTrue(ScrollQuiz.deepPrompt(q, "没人喝", null).contains("一次只问一个问题"))
    }

    @Test
    fun autoPaceCountsOnlyWatchedTime() {
        val p = AutoPace(5)
        var t = 0L
        assertFalse(p.tick(t, true))
        // 刷 3 分钟，切走 10 分钟，再刷 2 分钟：一共刷了 5 分钟
        repeat(12) { t += 15_000; assertFalse(p.tick(t, true)) }
        repeat(40) { t += 15_000; assertFalse(p.tick(t, false)) }
        var fired = false
        repeat(8) { t += 15_000; fired = p.tick(t, true) }
        assertTrue(fired)
    }

    @Test
    fun autoPaceWaitsForAnswerAndBacksOff() {
        val p = AutoPace(1)
        var t = 0L
        p.tick(t, true)
        fun run(ms: Long): Boolean {
            var any = false
            var left = ms
            while (left > 0) { t += 15_000; left -= 15_000; if (p.tick(t, true)) { any = true; p.asked(t) } }
            return any
        }
        assertTrue(run(60_000))
        // 没回：10 分钟内不再问
        assertFalse(run(9 * 60_000L))
        // 过了 10 分钟算没理一次，接着问
        assertTrue(run(3 * 60_000L))
        // 又没理：第二次没理之后间隔翻倍
        assertFalse(run(10 * 60_000L))
        assertEquals(2, p.backoff)
        p.answered()
        assertEquals(1, p.backoff)
        // 回了以后恢复原来的间隔
        assertTrue(run(60_000))
        // 关掉自动出题
        p.setInterval(0)
        assertFalse(run(5 * 60_000L))
    }

    @Test
    fun longGapIsNotWatching() {
        val p = AutoPace(1)
        p.tick(0, true)
        assertFalse(p.tick(10 * 60_000L, true))
        assertTrue(p.tick(10 * 60_000L + 60_000, true))
    }
}
