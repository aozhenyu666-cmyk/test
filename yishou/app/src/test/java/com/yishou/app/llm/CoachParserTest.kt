package com.yishou.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachParserTest {

    private val fullJudge = """
        {
          "effective": true,
          "move_type": "比较",
          "reason": "指出了两种增长率算法的差别",
          "feedback": "注意基期要用去年的值",
          "breakpoint": {"known": "已知A", "stuck": "卡在B", "next_question": "问C"},
          "next_coach_move": "先看第二段。增长量怎么算？"
        }
    """.trimIndent()

    @Test
    fun parsesFullJudgement() {
        val j = CoachParser.parseJudgement(fullJudge)
        assertTrue(j.effective)
        assertEquals("比较", j.moveType)
        assertEquals("指出了两种增长率算法的差别", j.reason)
        assertEquals("注意基期要用去年的值", j.feedback)
        assertEquals(BreakpointUpdate("已知A", "卡在B", "问C"), j.breakpoint)
        assertEquals("先看第二段。增长量怎么算？", j.nextCoachMove)
    }

    @Test
    fun acceptsCodeFenceAndSurroundingText() {
        val j = CoachParser.parseJudgement("好的，结果如下：\n```json\n$fullJudge\n```\n")
        assertEquals("比较", j.moveType)
    }

    @Test
    fun acceptsBooleanAsString() {
        val j = CoachParser.parseJudgement("""{"effective": "false", "move_type": "无", "next_coach_move": "下一手"}""")
        assertFalse(j.effective)
    }

    @Test
    fun ineffectiveAlwaysHasMoveTypeNone() {
        val j = CoachParser.parseJudgement("""{"effective": false, "move_type": "比较", "next_coach_move": "下一手"}""")
        assertEquals("无", j.moveType)
    }

    @Test
    fun unknownMoveTypeBecomesNone() {
        val j = CoachParser.parseJudgement("""{"effective": true, "move_type": "中间结论 | 比较", "next_coach_move": "下一手"}""")
        assertEquals("无", j.moveType)
    }

    @Test
    fun missingBreakpointIsNull() {
        val j = CoachParser.parseJudgement("""{"effective": true, "move_type": "预测", "next_coach_move": "下一手"}""")
        assertNull(j.breakpoint)
        assertEquals("", j.reason)
    }

    @Test(expected = BadJsonException::class)
    fun missingNextMoveFails() {
        CoachParser.parseJudgement("""{"effective": true, "move_type": "预测"}""")
    }

    @Test(expected = BadJsonException::class)
    fun missingEffectiveFails() {
        CoachParser.parseJudgement("""{"move_type": "预测", "next_coach_move": "下一手"}""")
    }

    @Test(expected = BadJsonException::class)
    fun nullNextMoveFails() {
        CoachParser.parseJudgement("""{"effective": true, "next_coach_move": null}""")
    }

    @Test(expected = BadJsonException::class)
    fun plainTextFails() {
        CoachParser.parseJudgement("我觉得这一手有效。")
    }

    @Test(expected = BadJsonException::class)
    fun brokenJsonFails() {
        CoachParser.parseJudgement("""{"effective": true, "next_coach_move": "缺右括号" """)
    }

    @Test
    fun parsesOpening() {
        val o = CoachParser.parseOpening("""{"coach_move": "已知 A 和 B。它们怎样联系？", "stuck_type": "缺关系"}""")
        assertEquals("已知 A 和 B。它们怎样联系？", o.coachMove)
        assertEquals("缺关系", o.stuckType)
    }

    @Test(expected = BadJsonException::class)
    fun openingWithoutMoveFails() {
        CoachParser.parseOpening("""{"coach_move": "  ", "stuck_type": "缺关系"}""")
    }

    @Test
    fun startersAreOptionalAndCleaned() {
        val j = CoachParser.parseJudgement(
            """{"effective": true, "move_type": "比较", "next_coach_move": "下一手",
               "starters": ["我先确定的是……", "", "我先确定的是……", "如果……那么……", "第三个"]}""",
        )
        assertEquals(listOf("我先确定的是……", "如果……那么……"), j.starters)
        assertEquals(emptyList<String>(), CoachParser.parseOpening("""{"coach_move": "问"}""").starters)
    }

    @Test
    fun parsesObserved() {
        val o = CoachParser.parseObserved("""{"observation": "在做第 3 题", "coach_move": "你为什么选 B？", "starters": ["我选 B 是因为……"]}""")
        assertEquals(ObservedMove("在做第 3 题", "你为什么选 B？", listOf("我选 B 是因为……")), o)
    }
}
