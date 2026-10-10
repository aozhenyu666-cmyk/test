package app.jobtracker.ai

import app.jobtracker.analysis.AnalysisService
import app.jobtracker.analysis.realPrompts
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptStoreTest {
    @Test
    fun rendersPlaceholdersOnceWithoutReplacingUserText() {
        val store = PromptStore { "简历：{{resume}}\nJD：{{jd}}" }
        val out = store.render("x", mapOf("resume" to "我会写 {{jd}}", "jd" to "招人"))
        assertEquals("简历：我会写 {{jd}}\nJD：招人", out)
    }

    @Test
    fun analysisUserTemplateHasAllPlaceholders() {
        val text = realPrompts().text(AnalysisService.USER)
        listOf("{{resume}}", "{{direction}}", "{{jd}}").forEach { assertTrue(it, it in text) }
    }

    @Test
    fun analysisSchemaRequiresEveryField() {
        val schema = realPrompts().schema(AnalysisService.SCHEMA)
        val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(
            setOf("company", "title", "hardRequirements", "matchLevel", "strengths", "gaps", "advice", "reason", "resumeTips"),
            required,
        )
        assertEquals(required, schema["properties"]!!.jsonObject.keys)
    }
}
