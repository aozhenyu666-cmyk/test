package cn.companion.pet.core.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneClassifierTest {
    private val launcher = "com.bbk.launcher2"
    private val lists = AppLists(
        learning = setOf("reader"),
        feed = setOf("feed"),
        focus = setOf("focus"),
        exclude = setOf("bank"),
    )
    private val longText = "这是一段很长的正文。".repeat(40)

    private fun classify(trail: ActivityTrail, now: Long, text: String = "", onRead: () -> Unit = {}) =
        SceneClassifier.classify(now, trail, lists, setOf(launcher)) { onRead(); text }

    @Test
    fun excludedNeverReadsScreen() {
        val t = ActivityTrail().apply { onForeground("bank", 0) }
        var read = false
        val d = classify(t, 1000, longText) { read = true }
        assertEquals(Scene.EXCLUDED, d.scene)
        assertFalse(read)
    }

    @Test
    fun fourAppsInOneMinuteIsWanderingAndNoScreenRead() {
        val t = ActivityTrail()
        listOf("a", "b", "c", "reader").forEachIndexed { i, p -> t.onForeground(p, i * 10_000L) }
        var read = false
        val d = classify(t, 40_000, longText) { read = true }
        assertEquals(Scene.WANDERING, d.scene)
        assertNull(d.screenText)
        assertFalse(read)
    }

    @Test
    fun threeAppsIsNotWandering() {
        val t = ActivityTrail()
        listOf("a", "b", "c").forEachIndexed { i, p -> t.onForeground(p, i * 10_000L) }
        assertEquals(Scene.NORMAL, classify(t, 30_000).scene)
    }

    @Test
    fun switchesOlderThanOneMinuteDoNotCount() {
        val t = ActivityTrail()
        listOf("a", "b", "c", "d").forEachIndexed { i, p -> t.onForeground(p, i * 30_000L) }
        // at 100s, window [40s,100s] has c(60s) and d(90s) only
        assertEquals(Scene.NORMAL, classify(t, 100_000).scene)
    }

    @Test
    fun feedAppIsWandering() {
        val t = ActivityTrail().apply { onForeground("feed", 0) }
        assertEquals(Scene.WANDERING, classify(t, 5_000, longText).scene)
    }

    @Test
    fun launcherSwipingFortySecondsIsWandering() {
        val t = ActivityTrail().apply { onForeground(launcher, 0) }
        for (s in 0..40 step 3) t.onScroll(launcher, s * 1000L)
        assertEquals(Scene.WANDERING, classify(t, 41_000).scene)
    }

    @Test
    fun launcherSwipingWithLongPauseResets() {
        val t = ActivityTrail().apply { onForeground(launcher, 0) }
        for (s in 0..20 step 3) t.onScroll(launcher, s * 1000L)
        for (s in 30..45 step 3) t.onScroll(launcher, s * 1000L)
        assertEquals(Scene.NORMAL, classify(t, 46_000).scene)
    }

    @Test
    fun learningListCarriesText() {
        val t = ActivityTrail().apply { onForeground("reader", 0) }
        val d = classify(t, 1000, "短")
        assertEquals(Scene.LEARNING, d.scene)
        assertEquals("短", d.screenText)
    }

    @Test
    fun longTextAnywhereIsLearning() {
        val t = ActivityTrail().apply { onForeground("browser", 0) }
        val d = classify(t, 1000, longText)
        assertEquals(Scene.LEARNING, d.scene)
        assertEquals(longText, d.screenText)
    }

    @Test
    fun normalHasNoText() {
        val t = ActivityTrail().apply { onForeground("browser", 0) }
        val d = classify(t, 1000, "一点点字")
        assertEquals(Scene.NORMAL, d.scene)
        assertNull(d.screenText)
    }

    @Test
    fun focusNeedsFifteenMinutes() {
        val t = ActivityTrail().apply { onForeground("focus", 0) }
        assertFalse(SceneClassifier.focusActive(14 * 60_000L, t, lists))
        assertTrue(SceneClassifier.focusActive(15 * 60_000L, t, lists))
    }

    @Test
    fun summaryMentionsDwell() {
        val t = ActivityTrail()
        t.onForeground("a", 0)
        t.onForeground("b", 5 * 60_000L)
        val s = t.summary(8 * 60_000L, { it.uppercase() })
        assertTrue(s, s.contains("A 5 分钟"))
        assertTrue(s, s.contains("现在在B"))
    }

    @Test
    fun stuckFiresOncePerPageAndResetsOnScroll() {
        val d = StuckDetector(90_000)
        d.onPage("p1", 0)
        assertFalse(d.check(89_000))
        assertTrue(d.check(90_000))
        assertFalse(d.check(200_000))
        d.onScroll(210_000)
        assertFalse(d.check(250_000))
        assertTrue(d.check(300_000))
        d.onPage("p2", 310_000)
        assertFalse(d.check(350_000))
        assertTrue(d.check(400_000))
    }
}
