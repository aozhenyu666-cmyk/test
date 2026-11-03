package com.dshdriver.core

import kotlin.test.*

class InteractionTest {
    @Test fun neverRepeatsLastThreeEvenAcrossRestoredHistory() {
        val recent = mutableListOf("recall", "teach", "challenge")
        repeat(100) { n ->
            val next = Interaction.pick(recent, { (n % 10) / 10.0 })
            assertTrue(next in Interaction.ALL)
            assertFalse(next in recent.takeLast(3))
            recent += next
        }
    }
    @Test fun materialPrecedesDifferentActionsAndNoInventedHistory() {
        val questions = Interaction.ALL.map { Gate.pick(null, "第3题选D，因为黑点递增", "第3题", Profile(), kind = it).text }
        assertEquals(5, questions.toSet().size)
        questions.forEach { assertTrue(it.substringBefore('\n').contains("第3题选D")); assertFalse(Interaction.formulaic(it)) }
        assertTrue(Gate.pick(null, null, null, Profile()).text.contains("没有保存"))
    }
    @Test fun rejectsTemplatesEvenWithLongPersonalMaterial() {
        for (template in Interaction.BLACKLIST) assertTrue(Interaction.formulaic("你曾经说过".repeat(30) + "。\n$template？"))
        assertTrue(Interaction.formulaic("下一步是什么呀？"))
        assertFalse(Interaction.formulaic("你上次选择了D。找个能推翻它的例子。"))
        assertFalse(Interaction.formulaic("你留下的疑问：下一步是什么\n找一个能推翻原结论的例子。"))
    }
    @Test fun driverSendsOptionalKindAndContextOnFallback() {
        val requests = mutableListOf<ComposeRequest>()
        val driver = Driver(brain = { requests += it; null }, profile = Profile(goals = listOf("12月省考")))
        val c = driver.start(0, task = "黑点数量").first().card!!
        assertTrue(requests.single().kind in Interaction.ALL)
        assertTrue(c.text.substringBefore('\n').contains("12月省考"))
    }
}
