package com.yishou.app.judge

import com.yishou.app.card.Card
import com.yishou.app.card.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题库题能不能进入口。粉笔这类题包只有「解析」、没有【要点】，
 * 所以要能从解析里拆出判据，否则整库题都进不了收费站。
 */
class LockedQuestionsTest {

    private fun card(
        explain: String,
        points: List<String> = emptyList(),
        answer: Int = 0,
    ) = Card(
        skill = Skill.MAIN,
        difficulty = 2,
        sentences = listOf("文段开篇引出话题。", "尾句通过“需要”提出对策。"),
        question = "这段文字意在强调：",
        options = listOf("应进一步推动高质量发展", "包括涉及多领域", "与制造业息息相关", "更好服务制造业"),
        answer = answer,
        evidence = emptySet(),
        flaws = emptyMap(),
        explain = explain,
        points = points,
    )

    /** 真机库里那份解析：正面说理 + 干扰项逐条排除。判据只能取正面说理那几句 */
    private val realExplain =
        "文段开篇介绍进人新石器时代后，中国大部地区经过几千年的文化交流整合成四个文化系统并开始接触融合，" +
            "随后通过：对文化系统之间接触融合的过程进行解释，接着指出在裴李岗文化的作用下，四个文化系统初步联结为一个相对的文化共同体，" +
            "从而构成了早期的中国文化圈。故文段意在强调四个文化系统融合之后形成了早期的中国文化圈，对应D项。" +
            "A项：对应文段开篇部分，非重点，文段重在强调各文化系统融合是早期中国文化圈的起源，而非强调“新石器时代”的重要性，排除。" +
            "B项：“地理范围不断演变发展”对应解释说明部分的内容，非重点，排除。" +
            "C项：“裴李岗文化”对应结论之前的内容，非重点，排除。" +
            "故正确答案为D。〔原书定位：小节01第5题；题本PDF第16页，解析PDF第9页。〕"

    @Test
    fun bankCardWithoutPointsFallsBackToExplain() {
        val q = LockedQuestions.fromCard(1L, card(realExplain))
        assertNotNull("没有【要点】的题库题也要能进入口", q)
        q!!
        assertTrue("判据至少两条", q.points.size >= 2)
        assertEquals(Locks.need(q.points.size), q.need)
    }

    /** 干扰项分析不能当判据：那是错误选项的理由，拿它判人会判反 */
    @Test
    fun distractorAnalysisIsNotUsedAsPoint() {
        val q = LockedQuestions.fromCard(1L, card(realExplain))!!
        val all = q.points.joinToString("；") { it.criterion }
        assertTrue("不应包含 A项 的排除理由", !all.contains("对应文段开篇部分"))
        assertTrue("不应包含 B项 的排除理由", !all.contains("地理范围不断演变发展"))
        assertTrue("不应包含 C项 的排除理由", !all.contains("裴李岗文化”对应结论之前"))
    }

    /** 出处标注不算判据 */
    @Test
    fun sourceMarkerIsNotUsedAsPoint() {
        val q = LockedQuestions.fromCard(1L, card(realExplain))!!
        val all = q.points.joinToString("；") { it.criterion }
        assertTrue(!all.contains("原书定位"))
        assertTrue(!all.contains("题本PDF"))
    }

    /** 题包自带【要点】时优先用要点，不退回解析 */
    @Test
    fun ownPointsWinOverExplain() {
        val q = LockedQuestions.fromCard(
            2L,
            card("这段解析里有一句很长很长的正面说理，足够长到能被当成判据使用。还有第二句也够长的正面说理内容。",
                points = listOf("要点甲内容够长了吧", "要点乙内容也够长吧")),
        )!!
        assertEquals(2, q.points.size)
        assertTrue(q.points[0].criterion.contains("要点甲"))
    }

    /** 解析全是干扰项和答案交代，拆不出判据：宁可这道题不进，也不拿错的东西凑数 */
    @Test
    fun explainWithOnlyDistractorsIsRejected() {
        val bad = "A项：对应开篇部分，非重点，排除。B项：对应解释说明部分，非重点，排除。故正确答案为D。"
        assertNull(LockedQuestions.fromCard(3L, card(bad)))
    }

    /** 解析为空：同样拒绝，不崩 */
    @Test
    fun emptyExplainIsRejected() {
        assertNull(LockedQuestions.fromCard(4L, card("")))
    }

    /** 答案字母不该留在判据里 */
    @Test
    fun answerLetterIsStrippedFromPoint() {
        val q = LockedQuestions.fromCard(5L, card("故文段意在强调四个文化系统融合之后形成了早期的中国文化圈，对应D项。尾句通过“需要”提出对策即要推动产业向价值链高端延伸。"))!!
        val all = q.points.joinToString("；") { it.criterion }
        assertTrue("判据里不该带“对应D项”", !all.contains("对应D项"))
    }
}
