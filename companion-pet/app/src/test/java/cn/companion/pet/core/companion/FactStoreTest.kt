package cn.companion.pet.core.companion

import cn.companion.pet.core.memory.ProfileRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class FactStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-23T04:00:00Z"), zone)

    private fun store() = FactStore(tmp.root, clock, zone)

    @Test
    fun guessDoesNotOverwriteSaid() {
        val s = store()
        val said = s.recordSaid("喜欢猫")!!
        val guess = s.recordGuess("喜欢猫")!!
        assertEquals(said.id, guess.id)
        assertEquals(Confidence.SAID, s.live().single().confidence)
    }

    @Test
    fun correctionSupersedesOldVersion() {
        val s = store()
        val old = s.recordSaid("喜欢猫")!!
        val corrected = s.correct(old.id, "现在喜欢狗")!!
        assertEquals(old.factKey, corrected.factKey)
        assertEquals(1, s.live().size)
        assertEquals("现在喜欢狗", s.live().single().text)
        assertEquals(corrected.id, s.all().first { it.id == old.id }.supersededBy)
    }

    @Test
    fun forgottenFactStaysDeletedAcrossReloadAndCannotBeReadded() {
        val s = store()
        val f = s.recordSaid("喜欢猫")!!
        s.forget(f.id)
        assertTrue(s.live().isEmpty())
        // 重启后仍然是删除状态
        val reopened = FactStore(tmp.root, clock, zone)
        assertTrue(reopened.live().isEmpty())
        // 旧来源重放：带 tombstone 再写一次不会复活
        assertNull(reopened.recordSaid("喜欢猫", tombstones = setOf(ProfileRules.normalize("喜欢猫"))))
    }

    @Test
    fun confirmGuessPromotesToSaid() {
        val s = store()
        val guess = s.recordGuess("晚上效率低")!!
        assertEquals(Confidence.GUESSED, guess.confidence)
        val confirmed = s.confirm(guess.id)!!
        assertEquals(Confidence.SAID, confirmed.confidence)
        assertEquals(2, confirmed.version)
    }

    @Test
    fun syncFromProfileFiltersTombstonesAndKeepsSaid() {
        val s = store()
        s.recordSaid("喜欢猫")
        val tombstones = setOf(ProfileRules.normalize("怕狗"))
        val added = s.syncFromProfile(
            listOf("[他说过] 喜欢猫", "[他说过] 怕狗", "[我推测] 喜欢猫", "[我推测] 怕黑"),
            listOf("e1"),
            tombstones,
        )
        assertEquals(1, added)
        val texts = s.live().map { it.text }.toSet()
        assertTrue("喜欢猫" in texts)
        assertTrue("怕黑" in texts)
        assertFalse("怕狗" in texts)
        assertEquals(Confidence.SAID, s.live().first { it.text == "喜欢猫" }.confidence)
    }

    @Test
    fun versionHashChangesWhenFactsChange() {
        val s = store()
        val before = s.versionHash()
        s.recordSaid("喜欢猫")
        assertTrue(before != s.versionHash())
    }
}
