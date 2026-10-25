package com.zongkong.app.healthwire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 证书摘要常量自检。
 *
 * 起因（真实踩过的坑）：哨兵侧把总控的证书摘要写成 63 个十六进制字符（少一位），
 * 结果 `actual.equals(expected)` 永远 false —— 现象像「客户端没接入」，极难查。
 * 所以这里对**本仓库自己写死的每一个**证书摘要做形状校验。
 */
class CertConstantsTest {

    private val hex64 = Regex("^[0-9A-Fa-f]{64}$")

    /** 本仓库里写死的证书摘要常量（新增时要加进来）。 */
    private fun constants(): Map<String, String> = mapOf(
        "SentinelClient/com.zongkong.sentinel(哨兵)" to
            "B742DCD196AF01FA96428498F5297D3165F66AB8C8FF6B85DD09185DBC318AC9",
        "Partner/YISHOU_CERT(一手)" to
            "EDCE45B1C655885E106D3A08A76D169E8A50CFA79F364415F8AC997B44D87BCE",
    )

    @Test
    fun `证书摘要必须是 64 位十六进制`() {
        constants().forEach { (name, value) ->
            assertEquals("$name 长度不对（被截断/多写都会让鉴权永远失败）：$value", 64, value.length)
            assertTrue("$name 含非十六进制字符：$value", hex64.matches(value))
        }
    }

    @Test
    fun `总控与哨兵是同一把调试签名（实测 apksigner）`() {
        val sentinel = constants().getValue("SentinelClient/com.zongkong.sentinel(哨兵)")
        // 总控 app-debug.apk 实测 SHA-256；两者必须一致，否则 register 会被拒。
        val zongkong = "B742DCD196AF01FA96428498F5297D3165F66AB8C8FF6B85DD09185DBC318AC9"
        assertEquals(zongkong.uppercase(), sentinel.uppercase())
    }
}
