package com.yishou.app.sharp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharpTest {

    private val text = "县城的咖啡店三年倒了一半，说明县城的人根本不喝咖啡。"

    @Test
    fun spotsTemplates() {
        assertEquals(Hollow.ABSOLUTE, Sharp.hollow("太绝对了"))
        assertEquals(Hollow.LOGIC, Sharp.hollow("我觉得逻辑不严密吧"))
        assertEquals(Hollow.BLANK, Sharp.hollow("不知道"))
        assertEquals(Hollow.EMPTY, Sharp.hollow("有道理，看情况"))
        assertEquals(Hollow.BLANK, Sharp.hollow("嗯 A"))
        // 说了套话，但后面跟着具体的东西：不算空
        assertNull(Sharp.hollow("太绝对了，“根本不喝”这四个字，新区那家天天排队"))
        assertNull(Sharp.hollow("倒闭可能是房租和选址，不等于没人喝"))
    }

    @Test
    fun drillMakesYouQuoteThenExample() {
        val d = Drill("太绝对了", Sharp.cuts(Hollow.ABSOLUTE, hasSource = true), text)
        assertEquals(Need.QUOTE, d.current!!.need)
        assertFalse(d.reply("说得太死了"))
        assertTrue(d.hint!!.contains("原文里找不到"))
        assertTrue(d.reply("根本不喝咖啡"))
        assertEquals(Need.EXAMPLE, d.current!!.need)
        assertFalse(d.reply("有反例"))
        assertTrue(d.reply("我们县新区那家瑞幸每天下午都排队到门口"))
        assertTrue(d.reply("应该说：县城一部分老城区的人不常喝咖啡"))
        assertTrue(d.done)
        assertEquals(5, d.cost)
        assertTrue(d.transcript().startsWith("先说：太绝对了"))
        assertTrue(d.transcript().contains("排队到门口"))
    }

    @Test
    fun becauseNeedsCausalWords() {
        val d = Drill("逻辑不严密", Sharp.cuts(Hollow.LOGIC, hasSource = false), "")
        // 没有原文：第一刀不要求照抄
        assertEquals(Need.REWRITE, d.current!!.need)
        assertTrue(d.reply("说明县城的人根本不喝咖啡这一句"))
        assertFalse(d.reply("从倒闭跳到不喝咖啡"))
        assertTrue(d.reply("因为店倒了，所以认定没人喝，中间跳过了房租和选址"))
        assertFalse(d.reply("不知道"))
        assertTrue(d.hint!!.contains("套话"))
    }

    @Test
    fun bullseyeNeedsTheRightOptionsOwnWords() {
        val options = listOf("咖啡店数量逐年增加", "倒闭与房租上涨有关", "县城居民不喝咖啡", "咖啡价格过高")
        assertTrue(Sharp.bullseye("我觉得是房租涨了导致倒闭", options, 1))
        assertFalse(Sharp.bullseye("咖啡店的问题", options, 1))
        assertFalse(Sharp.bullseye("居民不喝咖啡", options, 1))
        assertTrue(Sharp.bullseye("居民根本不喝咖啡", options, 2))
        assertFalse(Sharp.bullseye("", options, 1))
    }

    @Test
    fun cardCutsQuoteTheQuestion() {
        val d = Drill("不知道", Sharp.cardCuts(), "下列哪项最能削弱上述论证")
        assertFalse(d.reply("加强"))
        assertTrue(d.reply("削弱"))
        assertTrue(d.reply("要说明倒闭不是因为没人喝"))
        assertTrue(d.done)
        assertNotNull(d.said())
    }
}
