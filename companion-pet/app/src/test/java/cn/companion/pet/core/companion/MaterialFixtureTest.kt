package cn.companion.pet.core.companion

import cn.companion.pet.core.probe.ProbeMoment
import cn.companion.pet.core.scene.Scene
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class MaterialFixtureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-23T04:00:00Z"), zone)

    private fun moment(material: String?) = ProbeMoment(
        timeText = "10:00",
        scene = if (material == null) Scene.NORMAL else Scene.LEARNING,
        foregroundLabel = "桌面",
        trajectory = "",
        screenText = material,
        topic = null,
    )

    @Test
    fun fixtureWithoutSubtitlesSaysSo() {
        val item = MaterialItem("https://example.invalid/v/1", "v1", 1_700_000_000_000, "一只在雨里的猫", hasSubtitles = false)
        val text = item.render()
        assertTrue(text.contains("字幕：没有"))
        assertTrue(text.contains("不要说看完了视频"))
    }

    @Test
    fun noMaterialMeansNoBrowsingClaim() {
        val events = EventLog(tmp.root, clock, zone)
        val facts = FactStore(tmp.root, clock, zone)
        val assembler = CompanionContextAssembler(events, facts, zone)
        val messages = assembler.assemble("人设", moment(null), null, clock.millis())
        val user = messages[1].content
        assertFalse(user.contains("刚刚刷到"))
        assertFalse(user.contains("刚看完"))
        assertFalse(user.contains("B站"))
    }

    @Test
    fun fixtureSourceIsVisibleInMaterialBlock() {
        val events = EventLog(tmp.root, clock, zone)
        val facts = FactStore(tmp.root, clock, zone)
        val assembler = CompanionContextAssembler(events, facts, zone)
        val item = MaterialItem("https://example.invalid/v/1", "v1", 1_700_000_000_000, "一只在雨里的猫", hasSubtitles = true)
        val messages = assembler.assemble("人设", moment(item.summary), item.render(), clock.millis())
        assertTrue(messages[1].content.contains("https://example.invalid/v/1"))
    }
}
