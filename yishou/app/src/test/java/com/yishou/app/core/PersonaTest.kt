package com.yishou.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {

    private val ctx = CompanionContext(
        taskTitle = "行测",
        stuck = "总在两个都像的选项里选错",
    )

    @Test
    fun `balanced keeps the existing wording`() {
        val q = Persona.nudge(ctx, "B站", 20, PersonaConfig())
        assertTrue(q.contains("总在两个都像的选项里选错"))
        assertTrue(q.contains("20 分钟"))
        assertTrue(q.contains("休息"))
    }

    @Test
    fun `direct is shorter and offers two choices`() {
        val q = Persona.nudge(ctx, "B站", 20, PersonaConfig(tone = PersonaConfig.Tone.DIRECT))
        assertTrue(q.contains("回去做 15 分钟，还是休息"))
    }

    @Test
    fun `gentle softens the ask`() {
        val q = Persona.nudge(CompanionContext(), "B站", 20, PersonaConfig(tone = PersonaConfig.Tone.GENTLE))
        assertTrue(q.contains("要不要"))
    }

    @Test
    fun `brief is the shortest`() {
        val balanced = Persona.nudge(CompanionContext(), "B站", 20, PersonaConfig())
        val brief = Persona.nudge(CompanionContext(), "B站", 20, PersonaConfig(tone = PersonaConfig.Tone.BRIEF))
        assertTrue(brief.length < balanced.length)
        assertTrue(brief.contains("休息/回去"))
    }

    @Test
    fun `topic follow up varies by tone`() {
        assertEquals(
            "「登录接口」到哪一步了？",
            Persona.topic("登录接口", PersonaConfig(tone = PersonaConfig.Tone.DIRECT)),
        )
        assertTrue(
            Persona.topic("登录接口", PersonaConfig(tone = PersonaConfig.Tone.GENTLE))
                .contains("进展"),
        )
        assertEquals("「登录接口」？", Persona.topic("登录接口", PersonaConfig(tone = PersonaConfig.Tone.BRIEF)))
    }

    @Test
    fun `no tone praises or agrees`() {
        val banned = listOf("很好", "不错", "棒", "赞同", "你说得对", "真棒")
        val outputs = buildList {
            PersonaConfig.Tone.values().forEach { tone ->
                add(Persona.nudge(ctx, "B站", 20, PersonaConfig(tone = tone)))
                add(Persona.nudge(CompanionContext(), "B站", 20, PersonaConfig(tone = tone)))
                add(Persona.topic("登录接口", PersonaConfig(tone = tone)))
            }
        }
        for (text in outputs) {
            for (word in banned) {
                assertFalse("不该出现「$word」：$text", text.contains(word))
            }
        }
    }

    @Test
    fun `follow up count is clamped`() {
        assertEquals(3, PersonaConfig(maxFollowUps = 99).sane().maxFollowUps)
        assertEquals(0, PersonaConfig(maxFollowUps = -5).sane().maxFollowUps)
    }
}
