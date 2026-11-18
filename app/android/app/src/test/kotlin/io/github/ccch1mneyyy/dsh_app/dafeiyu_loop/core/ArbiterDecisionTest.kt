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
}
