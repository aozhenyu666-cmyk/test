package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArbiterDecisionTest {
    private val base = setOf("com.bbk.launcher2", "io.github.ccch1mneyyy.dsh_app.fish", "com.vivo.gallery")
    private val isBase: (String) -> Boolean = { it in base }

    @Test
    fun `allowlist lets work apps and base through, locks the rest, browsers ask for a pass`() {
        val d = ArbiterDecision.fromWire(mapOf(
            "mode" to "allowlist",
            "allow" to listOf("com.baidu.netdisk", "md.obsidian"),
            "needPass" to listOf("com.android.chrome"),
            "why" to "局进行中",
        ))!!
        assertFalse(d.decide("com.baidu.netdisk", isBase).lock)
        assertFalse(d.decide("com.bbk.launcher2", isBase).lock)
        assertFalse(d.decide("io.github.ccch1mneyyy.dsh_app.fish", isBase).lock)
        assertTrue(d.decide("com.android.chrome", isBase).needPass)
        val v = d.decide("com.ss.android.ugc.aweme", isBase)
        assertTrue(v.lock)
        assertEquals("局进行中", v.why)
    }

    @Test
    fun `free mode only locks the deny list`() {
        val d = ArbiterDecision.fromWire(mapOf(
            "mode" to "free",
            "deny" to listOf(mapOf("app" to "com.ss.android.ugc.aweme", "why" to "今天的娱乐额度用完了")),
        ))!!
        assertEquals("今天的娱乐额度用完了", d.decide("com.ss.android.ugc.aweme", isBase).why)
        assertFalse(d.decide("tv.danmaku.bili", isBase).lock)
    }

    @Test
    fun `open mode and unreadable payloads never lock`() {
        assertFalse(ArbiterDecision.fromWire(mapOf("mode" to "open"))!!.decide("com.ss.android.ugc.aweme", isBase).lock)
        assertNull(ArbiterDecision.fromWire(mapOf("mode" to "weird")))
        assertNull(ArbiterDecision.fromWire(null))
    }

    @Test
    fun `a decision older than ten minutes is stale and must not be enforced`() {
        val now = 1_000_000_000L
        assertTrue(ArbiterDecision.isFresh(now - 60_000L, now))
        assertFalse(ArbiterDecision.isFresh(now - ArbiterDecision.FRESH_MS - 1, now))
        assertFalse(ArbiterDecision.isFresh(0L, now))
        assertFalse(ArbiterDecision.isFresh(now + 60_000L, now))
    }

    @Test
    fun wireCarriesTheLockNameForTheLockPage() {
        val d = ArbiterDecision.fromWire(
            mapOf(
                "mode" to "allowlist", "allow" to listOf("md.obsidian"), "why" to "w", "unlockHow" to "u",
                "priority" to "P1", "label" to "深夜睡眠护栏", "source" to "NIGHT_CURFEW",
            ),
        )!!
        assertEquals("深夜睡眠护栏", d.label)
        assertEquals("NIGHT_CURFEW", d.source)
        // 旧引擎不发 label：照样能读，名字为空（锁页退回旧说法）
        val old = ArbiterDecision.fromWire(mapOf("mode" to "free"))!!
        assertEquals("", old.label)
    }
}
