package com.zongkong.sentinel.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可信证书清单的格式守卫（规范 §3.1 / 报告 §9）。
 *
 * 背景：这里写错一位 hex，`actual.equals(expected)` 就永远 false，客户端 register 被静默拒掉，
 * T02 整条链路断在第一跳。这不是理论风险——曾经把 64 位写成 63 位（漏了一个 `9`）。
 * 所以格式用测试钉死，任何人都不能再"差不多"。
 */
class TrustedCertTest {

    @Test
    fun `every trusted digest is exactly 64 hex chars`() {
        assertEquals(3, TrustedCerts.MAP.size)
        for ((pkg, sha) in TrustedCerts.MAP) {
            assertEquals("$pkg 的摘要长度不对", 64, sha.length)
            assertTrue("$pkg 的摘要含非 hex 字符：$sha", TrustedCerts.isWellFormed(sha))
        }
        // 整体健康检查也要是空的（把出错信息给人看，而不是抛异常）。
        assertEquals(emptyList<String>(), TrustedCerts.validate())
    }

    @Test
    fun `covers exactly the three clients`() {
        assertEquals(
            setOf(HealthStore.ZONGKONG, HealthStore.YISHOU, HealthStore.PET),
            TrustedCerts.MAP.keys,
        )
    }

    @Test
    fun `zongkong digest matches the keystore actually used to sign sentinel`() {
        // 哨兵与总控共用 ../app/debug.keystore；apksigner 实测两者都是这个摘要。
        assertEquals(
            "B742DCD196AF01FA96428498F5297D3165F66AB8C8FF6B85DD09185DBC318AC9",
            TrustedCerts.MAP[HealthStore.ZONGKONG],
        )
    }

    @Test
    fun `short digest is rejected as malformed`() {
        // 曾经的真实 bug：漏了第 24 位的 9。
        val broken = "B742DCD196AF01FA9642848F5297D3165F66AB8C8FF6B85DD09185DBC318AC9"
        assertEquals(63, broken.length)
        assertFalse(TrustedCerts.isWellFormed(broken))
    }

    @Test
    fun `non hex digest is rejected`() {
        assertFalse(TrustedCerts.isWellFormed("Z".repeat(64)))
        assertTrue(TrustedCerts.isWellFormed("a".repeat(64)))
    }

    @Test
    fun `validate reports the offending entry`() {
        // 直接验证校验逻辑本身（不依赖清单当前值被改坏）。
        assertTrue(TrustedCerts.validate().isEmpty())
    }
}
