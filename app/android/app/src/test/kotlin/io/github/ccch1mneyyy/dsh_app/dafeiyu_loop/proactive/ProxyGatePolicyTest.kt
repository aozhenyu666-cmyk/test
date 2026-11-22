package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyGatePolicyTest {
    @Test
    fun `vpn already up goes straight to Gemini`() {
        assertNull(ProxyGatePolicy.choose(vpnUp = true, installed = setOf("moe.nb4a")))
    }

    @Test
    fun `vpn down opens NekoBox first`() {
        assertEquals("moe.nb4a", ProxyGatePolicy.choose(vpnUp = false, installed = setOf("com.v2ray.ang", "moe.nb4a")))
    }

    @Test
    fun `no proxy app installed falls back to direct`() {
        assertNull(ProxyGatePolicy.choose(vpnUp = false, installed = emptySet()))
    }
}
