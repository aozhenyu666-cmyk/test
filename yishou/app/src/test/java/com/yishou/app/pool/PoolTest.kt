package com.yishou.app.pool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakeTest {

    @Test
    fun questionsGoToBank() {
        val q = """
            1. 以下哪项最能解释上述现象？
            A. 租金低
            B. 客群不同
            C. 交通好
            D. 人少
            答案：B
            2. 下列说法正确的是
            A. 甲
            B. 乙
            C. 丙
            D. 丁
            答案：A
        """.trimIndent()
        assertTrue(Intake.route(q) is Intake.Route.Bank)
    }

    @Test
    fun articleIsSplitIntoPieces() {
        val para = "县城咖啡店近几年开得很快，新区的店大多活过了一年，老城区的半年关了一半。".repeat(12)
        val text = (1..5).joinToString("\n") { "第${it}段：$para" }
        val r = Intake.route(text)
        assertTrue(r is Intake.Route.Article)
        val pieces = (r as Intake.Route.Article).pieces
        assertTrue(pieces.size >= 3)
        assertTrue(pieces.all { it.text.length <= Intake.PIECE + 10 })
        assertTrue(pieces.first().title.endsWith("（1/${pieces.size}）"))
        // 整篇内容一个字都没丢
        assertEquals(text.replace("\n", ""), pieces.joinToString("") { it.text.replace("\n", "") })
    }

    @Test
    fun shortTextStaysOnePiece() {
        val r = Intake.route("县城咖啡店开在新区的大多活过了一年，开在老城区的半年内关了一半，店主都说是租金的问题。")
        val pieces = (r as Intake.Route.Article).pieces
        assertEquals(1, pieces.size)
        assertTrue(!pieces[0].title.contains("（"))
        assertTrue(Intake.route("太短了") is Intake.Route.TooShort)
    }
}

class NextTest {

    @Test
    fun priorities() {
        val full = Next.Snapshot(
            endgameRedo = 2, endgameDue = 5, soupCard = 7L to "为什么", freshMaterial = 3L to "咖啡店",
            oldMaterial = 1L to "旧文", bankLeft = 30, line = 9L to "下一问",
        )
        assertEquals(Next.Mode.ENDGAME_REDO, Next.pick(full).mode)
        // 刚做过残局：换成海龟汤
        assertEquals(Next.Mode.SOUP, Next.pick(full.copy(recent = listOf(Next.Mode.ENDGAME))).mode)
        // 点了“换一个”
        assertEquals(Next.Mode.DRILL, Next.pick(full, skip = setOf(Next.Mode.ENDGAME_REDO, Next.Mode.SOUP)).mode)
        val soup = Next.pick(full.copy(endgameRedo = 0))
        assertEquals(7L, soup.id)
    }

    @Test
    fun drillRotatesLenses() {
        val s = Next.Snapshot(freshMaterial = 3L to "咖啡店")
        assertEquals("GIST", Next.pick(s).lens)
        assertEquals("ASSUME", Next.pick(s.copy(drillsDone = 1)).lens)
        assertEquals("GIST", Next.pick(s.copy(drillsDone = Next.LENSES.size)).lens)
    }

    @Test
    fun emptyPoolSaysSo() {
        val p = Next.pick(Next.Snapshot())
        assertEquals(Next.Mode.EMPTY, p.mode)
        assertTrue(p.reason.contains("分享"))
        assertEquals(Next.Mode.CARDS, Next.pick(Next.Snapshot(bankLeft = 3)).mode)
    }

    @Test
    fun recentRoundTrip() {
        val r = listOf(Next.Mode.SOUP, Next.Mode.DRILL)
        assertEquals(r, Next.decode(Next.encode(r)))
        assertEquals(emptyList<Next.Mode>(), Next.decode(null))
    }

    @Test
    fun scrollMissComesBackBeforeSoup() {
        val s = Next.Snapshot(scrollMiss = 7L to "咖啡店倒闭", soupCard = 1L to "为什么")
        val p = Next.pick(s)
        assertEquals(Next.Mode.SCROLL_REDO, p.mode)
        assertEquals(7L, p.id)
        assertEquals(Next.Mode.SOUP, Next.pick(s.copy(recent = listOf(Next.Mode.SCROLL_REDO))).mode)
    }
}
