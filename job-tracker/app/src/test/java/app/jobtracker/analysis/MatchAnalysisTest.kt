package app.jobtracker.analysis

import app.jobtracker.data.model.MatchLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MatchAnalysisTest {
    @Test
    fun parsesAllFieldsAndCapsResumeTips() {
        val a = MatchAnalysis.parse(VALID_ANALYSIS_JSON, maxResumeTips = 3)!!
        assertEquals("示例科技", a.company)
        assertEquals("数据分析师", a.title)
        assertEquals(2, a.hardRequirements.size)
        assertEquals(MatchLevel.MEDIUM, a.level)
        assertEquals(Advice.MAYBE, a.adviceValue)
        assertEquals(listOf("三年 SQL 经验"), a.strengths)
        assertEquals(listOf("没有 Python 经验"), a.gaps)
        assertEquals(3, a.resumeTips.size)
    }

    @Test
    fun acceptsJsonWrappedInCodeFence() {
        assertNotNull(MatchAnalysis.parse("```json\n$VALID_ANALYSIS_JSON\n```", 3))
    }

    @Test
    fun rejectsNonJson() {
        assertNull(MatchAnalysis.parse("这个岗位挺适合你的", 3))
    }

    @Test
    fun rejectsMissingField() {
        assertNull(MatchAnalysis.parse(VALID_ANALYSIS_JSON.replace("\"gaps\": [\"没有 Python 经验\"],", ""), 3))
    }

    @Test
    fun rejectsUnknownEnumValues() {
        assertNull(MatchAnalysis.parse(VALID_ANALYSIS_JSON.replace("\"medium\"", "\"very high\""), 3))
        assertNull(MatchAnalysis.parse(VALID_ANALYSIS_JSON.replace("\"maybe\"", "\"go\""), 3))
    }
}
