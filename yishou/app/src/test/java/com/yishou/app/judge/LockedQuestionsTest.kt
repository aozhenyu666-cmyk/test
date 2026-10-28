package com.yishou.app.judge

import com.yishou.app.card.Card
import com.yishou.app.card.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LockedQuestionsTest {
    private val base = Card(Skill.MAIN, 1, listOf("站点开放时间是上午八点，进入前需要实名核验。"), "参观者必须先完成哪项操作才能进入站点？",
        listOf("实名核验", "购买商品", "预约餐饮", "参加抽奖"), 0, setOf(1), emptyMap(), "")

    @Test fun usesOwnPointsFirst() {
        val q = LockedQuestions.fromCard(1, base.copy(points = listOf("给出先核验身份再进入的顺序", "说明时间不能代替身份核验"), explain = "随便写的解析，不会用到这里。"))!!
        assertEquals("给出先核验身份再进入的顺序", q.points[0].criterion)
    }

    @Test fun oldCardWithoutPointsFallsBackToExplain() {
        val q = LockedQuestions.fromCard(2, base.copy(explain = "身份核验是进入站点的前提条件。没有通过核验，就算在开放时间内到达也进不去。"))
        assertNotNull(q)
        assertEquals(2, q!!.points.size)
        assertEquals(2, q.need)
    }

    @Test fun explainSentencesAboutOptionLettersOrCopiedMaterialAreDropped() {
        // 只剩一句能用：不进入口
        assertNull(LockedQuestions.fromCard(3, base.copy(explain = "A项正确。站点开放时间是上午八点，进入前需要实名核验。身份核验是进入站点的前提条件。")))
        val q = LockedQuestions.fromCard(4, base.copy(explain = "故选A。身份核验是进入站点的前提条件。没有通过核验，就算在开放时间内到达也进不去。"))!!
        assertFalse(q.points.any { it.criterion.contains("选A") })
    }
}
