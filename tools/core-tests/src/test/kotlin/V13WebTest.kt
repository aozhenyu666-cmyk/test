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
        // 仅测试放行钩子：生产默认 false，测试构造时注入
        assertNull(Sources.checkUrl("http://localhost:8080/a", true))
    }



    @Test
    fun canonicalKeepsQueryAndOnlyReusesSameUrl() {
        val a = Sources.canonical("https://mp.weixin.qq.com/s?__biz=x&mid=1&idx=1&sn=aa&utm_source=wechat#rd")
        val b = Sources.canonical("https://mp.weixin.qq.com/s?__biz=x&mid=2&idx=1&sn=aa")
        assertNotEquals(a, b)
        assertTrue(a.startsWith("https://mp.weixin.qq.com/s?"))
        assertTrue(a.contains("mid=1"))
        assertFalse(a.contains("utm_"))
        assertFalse(a.contains("#"))
        assertEquals(Sources.canonical("https://example.com/a/"), Sources.canonical("https://example.com/a#x"))
        assertEquals(Sources.canonical("https://example.com:443/a"), Sources.canonical("https://example.com/a"))

        // 只差一个参数的两篇公众号文章 → 两个来源
        var st = SourceState()
        st = Sources.put(st, ref("s-1", "https://mp.weixin.qq.com/s?mid=1", "第一篇"), "正文一")
        st = Sources.put(st, ref("s-2", "https://mp.weixin.qq.com/s?mid=2", "第二篇"), "正文二")
        assertEquals(2, st.sources.size)

        // 两个 URL 返回同一段登录墙 → 仍是两个来源（不按正文 hash 合并）
        var wall = SourceState()
        wall = Sources.put(wall, ref("s-a", "https://e.com/a", "A", hash = "same"), "登录墙")
        wall = Sources.put(wall, ref("s-b", "https://e.com/b", "B", hash = "same"), "登录墙")
        assertEquals(2, wall.sources.size)

        // 同一个规范化 URL 再次抓取：只更新 hash / 标题 / 时间，不悄悄换正文
        var again = SourceState()
        again = Sources.put(again, ref("s-1", "https://e.com/a?x=1", "旧标题", "h1", 10), "old body")
        again = Sources.put(again, ref("s-2", "https://e.com/a?x=1#frag", "新标题", "h2", 20), "new body")
        assertEquals(1, again.sources.size)
        assertEquals("新标题", again.sources.first().title)
        assertEquals("h2", again.sources.first().contentHash)
        assertEquals(20, again.sources.first().fetchedAt)
        assertEquals("old body", again.document(again.sources.first().id)?.text)
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
        val page = (r as PortResult.Ok).value
        assertEquals("正文", page.text.trim())
        assertEquals("X", page.title)
        server.shutdown()
    }

    @Test
    fun fetchRejectsTooLargeAndTooManyRedirects() = runBlocking {
        val big = MockWebServer()
        big.enqueue(MockResponse().setBody("x".repeat(WebLimits.MAX_BYTES + 10)).setHeader("Content-Type", "text/html"))
        big.start()
        val bigResult = HttpWebPort(allowPrivateForTest = true).fetch(big.url("/big").toString())
        assertTrue(bigResult is PortResult.Failed)
        assertEquals("TOO_LARGE", (bigResult as PortResult.Failed).error.code)
        big.shutdown()

        val red = MockWebServer()
        red.start()
        val next = red.url("/next").toString()
        repeat(6) { red.enqueue(MockResponse().setResponseCode(302).setHeader("Location", next)) }
        val redResult = HttpWebPort(allowPrivateForTest = true).fetch(red.url("/start").toString())
        assertTrue(redResult is PortResult.Failed)
        assertEquals("TOO_MANY_REDIRECTS", (redResult as PortResult.Failed).error.code)
        red.shutdown()
    }




    @Test
    fun searchWithoutKeyIsNotConfigured() = runBlocking {
        val r = HttpWebPort().search("q")
        assertTrue(r is PortResult.Failed)
        assertEquals("NOT_CONFIGURED", (r as PortResult.Failed).error.code)
    }
}
