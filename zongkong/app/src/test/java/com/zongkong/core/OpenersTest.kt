package com.zongkong.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 话头：按状况起开场白，每一句都带前情和「先反驳我」的规则。 */
class OpenersTest {

    @Test fun nothingKnownStillGivesAnOpener() {
        val keys = Openers.build(Openers.Ctx(hour = 10)).map { it.key }
        assertEquals(listOf("stuck", "think"), keys)
    }

    @Test fun missesComeFirstAndCarryTheQuestions() {
        val o = Openers.build(
            Openers.Ctx(
                mainTitle = "写论文第二章", stepTitle = "列提纲", misses = listOf("q1", "q2", "q3", "q4"),
                yishouLine = "刷题 4（接住 0）", hour = 22,
            ),
        )
        assertEquals(listOf("misses", "step", "argue", "think", "night"), o.map { it.key })
        val first = o.first().text
        assertTrue(first, "1. q2" in first && "3. q4" in first && "q1" !in first)
        assertTrue(o.all { "先指出我想法里最弱的一处" in it.text })
        assertTrue(o[1].text, o[1].text.startsWith("今天的主线：写论文第二章\n当前这一步：列提纲"))
    }

    @Test fun strictAddsTheAvoidanceOpener() {
        val o = Openers.build(Openers.Ctx(headline = "严管", strict = true, mainTitle = "主线", hour = 15))
        assertTrue(o.any { it.key == "stuck" && "被总控严管中" in it.text })
    }
}
