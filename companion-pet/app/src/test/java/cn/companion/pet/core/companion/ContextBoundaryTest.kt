package cn.companion.pet.core.companion

import cn.companion.pet.core.probe.ProbeMoment
import cn.companion.pet.core.probe.ProbePrompt
import cn.companion.pet.core.scene.Scene
import cn.companion.pet.core.text.UnicodeText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class ContextBoundaryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneId.of("UTC"))

    @Test fun tightContextBudgetRetainsClosedMaterialMarkersAndUnicode() {
        val events = EventLog(tmp.root, clock)
        repeat(12) { events.append(EventKind.EXCHANGE, "合成交流".repeat(60)) }
        val material = "中文😀".repeat(400)
        val messages = CompanionContextAssembler(events, FactStore(tmp.root, clock)).assemble("合成角色",
            ProbeMoment("12:00", Scene.LEARNING, "阅读器", "", material), material, clock.millis(),
            limits = ContextLimits(dynamicMaxChars = 700))
        val text = messages.last().content
        assertTrue(UnicodeText.count(text) <= 700)
        assertTrue(text.contains(ProbePrompt.SCREEN_OPEN))
        assertTrue(text.contains(ProbePrompt.SCREEN_CLOSE))
        assertFalse(text.last().isHighSurrogate())
    }

    @Test fun forgottenSourceInvalidatesMultipleLevelsOfParaphrasedDerivatives() {
        val events = EventLog(tmp.root, clock)
        val source = events.append(EventKind.NOTE, "合成秘密")
        val first = events.append(EventKind.MATERIAL_SUMMARY, "第一层转述", sourceIds = listOf(source.id))
        val second = events.append(EventKind.EXCHANGE, "第二层完全不同的转述", sourceIds = listOf(first.id))
        events.append(EventKind.FORGET, "合成秘密", sourceIds = listOf(source.id))
        val visibility = EventVisibility(events.all(), setOf("合成秘密"))
        assertFalse(visibility.allows(source))
        assertFalse(visibility.allows(first))
        assertFalse(visibility.allows(second))
    }
}
