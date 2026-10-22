package com.moling.core

import com.moling.core.cycle.AutonomyBudget
import com.moling.core.cycle.CognitiveCycle
import com.moling.core.cycle.CycleState
import com.moling.core.intake.Inhale
import com.moling.core.intake.InhaleItem
import com.moling.core.intake.InhaleParse
import com.moling.core.intake.InhaleState
import com.moling.core.strategy.Prework
import com.moling.core.web.HttpWebPort
import com.moling.core.web.HtmlText
import com.moling.core.web.Rss
import com.moling.core.web.SourceAttachment
import com.moling.core.web.SourceChannel
import com.moling.core.web.SourceDocument
import com.moling.core.web.SourceRef
import com.moling.core.web.SourceState
import com.moling.core.web.SourceStatus
import com.moling.core.web.Sources
import com.moling.core.web.WebLimits
import com.moling.core.workspace.Material
import com.moling.core.workspace.PortResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.charset.Charset

class V13WebTest {

    private fun ref(id: String, url: String, title: String = "", hash: String = "", at: Long = 0) =
        SourceRef(id, SourceChannel.URL, url, title, at, contentHash = hash)

    private fun doc(id: String, bytes: Int, at: Long) = SourceDocument(id, "x", bytes, at)

    @Test
    fun urlSafetyBlocksLocalAndPrivate() {
        // 正常公网：用注入的解析器，测试不依赖真实 DNS
        assertNull(Sources.checkUrl("https://example.com/a", resolve = { listOf(InetAddress.getByName("93.184.216.34")) }))
        assertNotNull(Sources.checkUrl("http://localhost/x"))
        assertNotNull(Sources.checkUrl("http://127.0.0.1/x"))
        assertNotNull(Sources.checkUrl("http://10.1.2.3/x"))
        assertNotNull(Sources.checkUrl("http://192.168.1.2/x"))
        assertNotNull(Sources.checkUrl("http://172.20.1.2/x"))
        assertNotNull(Sources.checkUrl("http://169.254.169.254/latest/meta-data"))
        assertNotNull(Sources.checkUrl("ftp://example.com/x"))
        // F10-1：各种绕过写法
        assertNotNull(Sources.checkUrl("http://[::1]/x"))
        assertNotNull(Sources.checkUrl("http://2130706433/x"))
        assertNotNull(Sources.checkUrl("http://[fd00::]/x"))
        assertNotNull(Sources.checkUrl("http://[::ffff:127.0.0.1]/x"))
        assertNotNull(Sources.checkUrl("http://100.64.0.1/x"))
        assertNotNull(Sources.checkUrl("http://100.127.255.254/x"))
        assertNotNull(Sources.checkUrl("http://0.0.0.0/x"))
        assertNotNull(Sources.checkUrl("http://[fe80::1]/x"))
        // 域名解析到内网：任一地址是私网就拒绝（生产路径用真实解析，这里注入）
        assertNotNull(Sources.checkUrl("http://evil.example/x", resolve = { listOf(InetAddress.getByName("192.168.1.9")) }))
        assertNotNull(Sources.checkUrl("http://evil.example/x", resolve = { listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")) }))
        assertNull(Sources.checkUrl("http://ok.example/x", resolve = { listOf(InetAddress.getByName("93.184.216.34")) }))
        // 解析不出来不在这里判死（连接阶段 OkHttp Dns 会再查并拒绝内网）
        assertNull(Sources.checkUrl("http://not.resolvable.example/x", resolve = { emptyList() }))
    }



    @Test
    fun sourceDedupeAttachDelete() {
        var st = SourceState()
        val r = ref("s-1", "https://example.com/a?x=1", "标题", Sources.hash("body"), 10)
        st = Sources.put(st, r, "body")
        val again = Sources.put(st, r.copy(id = "s-2"), "body")
        assertEquals(1, again.sources.size)
        st = Sources.attach(st, "m-1", "s-1", 20)
        st = Sources.attach(st, "m-1", "s-1", 21)
        assertEquals(1, st.attachments.size)
        assertEquals("s-1", st.refOfMaterial("m-1")?.id)
        st = Sources.delete(st, "s-1")
        assertEquals(SourceStatus.DELETED, st.ref("s-1")?.status)
        assertNull(st.document("s-1"))
    }

    @Test
    fun swallowShareOnlyRealUrl() {
        val item = InhaleParse.fromShare("看这个 https://example.com/x?q=1 很好", 100)
        assertEquals("share", item.kind)
        assertEquals("https://example.com/x?q=1", item.url)
        val bare = InhaleParse.fromShare("https://example.com/y", 101)
        assertEquals("url", bare.kind)
        assertEquals("", bare.text)
        assertNotNull(Sources.checkUrl("这不是链接"))
    }

    @Test
    fun inhaleDedupesAndExpires() {
        var st = InhaleState()
        val item = InhaleItem("i-1", "share", text = "hello", dedupeKey = "k")
        val (a, added) = Inhale.add(st, item, 0)
        assertNotNull(added)
        val (b, dup) = Inhale.add(a, item, 10)
        assertNull(dup)
        assertEquals(1, b.items.size)
        assertTrue(Inhale.live(b, 100).isNotEmpty())
        assertTrue(Inhale.live(b, Inhale.TTL_MS + 1).isEmpty())
        st = Inhale.consume(b, "i-1", 50)
        assertTrue(Inhale.live(st, 60).isEmpty())
        assertTrue(Inhale.live(Inhale.sweep(st, 60), 60).isEmpty())
    }

    @Test
    fun budgetIsPersistentAndLimited() {
        val b = AutonomyBudget(day = "d1", enabled = true, maxSearches = 2)
        assertTrue(b.canSearch())
        val b1 = b.spendSearch()!!
        val b2 = b1.spendSearch()!!
        assertNull(b2.spendSearch())
        assertEquals(2, b2.searches)
        assertEquals(0, b2.fresh("d2").searches)
        assertFalse(AutonomyBudget(enabled = false).canFetch())
    }

    @Test
    fun cycleDedupesEvents() {
        val (a, first) = CognitiveCycle.once(CycleState(), "evt-1")
        assertTrue(first)
        val (b, again) = CognitiveCycle.once(a, "evt-1")
        assertFalse(again)
        assertEquals(1, b.seenEventIds.size)
    }

    @Test
    fun htmlAndRssParse() {
        val text = HtmlText.from("<html><head><title>T</title><script>var x=1</script></head><body><h1>Hi</h1><p>Hello &amp; bye</p></body></html>")
        assertEquals("T", HtmlText.title("<title>T</title>"))
        assertTrue(text.contains("Hi"))
        assertTrue(text.contains("Hello & bye"))
        assertFalse(text.contains("var x"))
        val rss = Rss.parse("<rss><channel><item><title>A</title><link>https://e.com/a</link><description>D</description></item></channel></rss>")
        assertTrue(rss is PortResult.Ok)
        assertEquals("https://e.com/a", (rss as PortResult.Ok).value.first().url)
        assertTrue(Rss.parse("not xml") is PortResult.Failed)
        // 顺手项：带 DOCTYPE 的订阅源直接拒绝（XXE 的第一道门）
        assertTrue(Rss.parse("""<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><rss><channel><item><title>A</title><link>https://e.com/a</link></item></channel></rss>""") is PortResult.Failed)
    }


    @Test
    fun fetchWithFakeServer() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("<html><title>X</title><body><p>正文</p></body></html>").setHeader("Content-Type", "text/html; charset=utf-8"))
        server.start()
        val port = HttpWebPort(allowPrivateForTest = true)
        val r = port.fetch(server.url("/a").toString())
        assertTrue(r is PortResult.Ok)
        assertEquals("正文", (r as PortResult.Ok).value.text.trim())
        server.shutdown()
    }

    @Test
    fun fetchRejectsTooLargeAndRedirects() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 10)))
        server.start()
        val port = HttpWebPort(allowPrivateForTest = true)
        assertTrue(port.fetch(server.url("/big").toString()) is PortResult.Failed)
        server.shutdown()
    }




    @Test
    fun searchWithoutKeyIsNotConfigured() = runBlocking {
        val r = HttpWebPort().search("q")
        assertTrue(r is PortResult.Failed)
        assertEquals("NOT_CONFIGURED", (r as PortResult.Failed).error.code)
    }
}
