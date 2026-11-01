package com.zongkong.core.buddy

import com.zongkong.core.buddy.BridgeNet.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外接大脑的地址校验、重试、出错说明。 */
class BridgeNetTest {

    private fun ok(url: String) = BridgeNet.check(url) as? Check.Ok ?: throw AssertionError("应该放行：$url → ${BridgeNet.check(url)}")
    private fun bad(url: String) = BridgeNet.check(url) as? Check.Bad ?: throw AssertionError("应该拒绝：$url → ${BridgeNet.check(url)}")

    @Test fun localHttpAllowed() {
        val c = ok(BridgeNet.LOCAL_DEFAULT)
        assertEquals(Check.Ok("http", "127.0.0.1", local = true), c)
        assertEquals(Check.Ok("http", "localhost", local = true), ok("http://localhost:7861/zongkong/event"))
        assertEquals(Check.Ok("http", "localhost", local = true), ok("HTTP://LocalHost:7861/zongkong/event"))
        ok("  http://127.0.0.1:7861/zongkong/event  ")
    }

    @Test fun httpsAllowed() {
        assertEquals(Check.Ok("https", "pc.tail1234.ts.net", local = false), ok("https://pc.tail1234.ts.net/zongkong/event"))
        ok("https://abc.trycloudflare.com/zongkong/event")
        ok("https://127.0.0.1:8443/zongkong/event")
    }

    @Test fun nonLocalHttpRejected() {
        assertTrue(bad("http://192.168.1.5:7861/zongkong/event").why.contains("https://"))
        bad("http://example.com/zongkong/event")
        bad("http://10.0.2.2:7861/zongkong/event")
        bad("http://[::1]:7861/zongkong/event")
        bad("http://0.0.0.0:7861/zongkong/event")
        bad("http://2130706433:7861/zongkong/event")
    }

    @Test fun disguisedHostsRejected() {
        bad("http://127.0.0.1.evil.com/zongkong/event")
        bad("http://localhost.evil.com:7861/zongkong/event")
        bad("http://127.0.0.1@evil.com/zongkong/event")
        bad("http://127.0.0.1:7861@evil.com/zongkong/event")
        bad("http://evil.com/127.0.0.1:7861/zongkong/event")
        bad("http://evil.com?u=http://127.0.0.1")
        bad("http://evil.com#@127.0.0.1")
        bad("http://127.0.0.1\\@evil.com/")
        bad("http://localhost./zongkong/event")
        bad("https://user:pass@pc.ts.net/zongkong/event")
    }

    @Test fun invalidUrlsRejected() {
        bad("")
        bad("   ")
        bad("127.0.0.1:7861/zongkong/event")
        bad("localhost:7861/zongkong/event")
        bad("https://")
        bad("http:///zongkong/event")
        bad("ftp://127.0.0.1/zongkong/event")
        bad("http://127.0.0.1:7861/zong kong/event")
        bad("http://my_host/zongkong/event")
        bad("://nohost")
        bad("javascript:alert(1)")
    }

    @Test fun retryBacksOffAndDropsStaleEvents() {
        val at = 1_000_000L
        assertEquals(2_000L, BridgeNet.nextDelay(0, at, at, null))
        assertEquals(5_000L, BridgeNet.nextDelay(1, at, at + 2_000, 503))
        assertEquals(30_000L, BridgeNet.nextDelay(3, at, at + 30_000, null))
        // 重试次数用完
        assertNull(BridgeNet.nextDelay(4, at, at + 60_000, null))
        // 下一次发出时已经超过 2 分钟：丢掉，不补发
        assertNull(BridgeNet.nextDelay(2, at, at + 110_000, null))
        // 口令错、路径错不重试
        assertNull(BridgeNet.nextDelay(0, at, at, 401))
        assertNull(BridgeNet.nextDelay(0, at, at, 404))
        assertTrue(BridgeNet.retryable(429) && BridgeNet.retryable(500) && BridgeNet.retryable(null))
    }

    @Test fun explainSeparatesCauses() {
        assertTrue(BridgeNet.explain(null, java.net.ConnectException("ECONNREFUSED"), local = true).startsWith("本机 DSH 没在运行"))
        assertTrue(BridgeNet.explain(null, java.net.ConnectException("x"), local = false).startsWith("连不上远程地址"))
        assertTrue(BridgeNet.explain(null, java.net.UnknownHostException("x"), local = false).startsWith("找不到这个域名"))
        assertTrue(BridgeNet.explain(null, java.net.SocketTimeoutException("x"), local = true).startsWith("本机 DSH 没及时回应"))
        val ok = BridgeNet.explain(200, null, local = true, body = "{\"ok\":true}")
        assertTrue(ok, ok.startsWith("已送达（HTTP 200）") && ok.contains("要在 QQ 或 DSH 里看"))
        assertTrue(BridgeNet.explain(401, null, local = true).startsWith("口令不对"))
        assertTrue(BridgeNet.explain(404, null, local = true).startsWith("DSH 在运行，但没有这个路径"))
        val down = BridgeNet.explain(503, null, local = true, body = "model offline")
        assertTrue(down, down.contains("在线模型暂时连不上") && down.contains("model offline"))
    }
}
