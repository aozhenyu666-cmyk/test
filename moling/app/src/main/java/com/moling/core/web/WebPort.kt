package com.moling.core.web

import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/*
 * 第十批 T2：一个真的 WebPort。搜不到/没配置/超时都明确失败，生产路径不返回 mock 内容。
 * 安全：只公网 http(s)、拒绝本机/内网/元数据；重定向≤3、响应≤2MB、请求≤15s。
 *
 * 第十四批 F10-1：每一跳都用 Sources.checkUrl 解析地址再判；连接侧再套一个 OkHttp Dns 钩子，
 * 只用“这里认可过的地址”去连，避免解析两次被 DNS 重绑定绕过。
 */

data class SearchHit(val url: String, val title: String, val snippet: String = "")
data class FeedItem(val url: String, val title: String, val summary: String = "", val publishedAt: Long = 0)
data class WebPage(val url: String, val finalUrl: String, val title: String, val text: String, val fetchedAt: Long, val complete: Boolean)

interface WebPort {
    suspend fun search(query: String, limit: Int = 5): PortResult<List<SearchHit>>
    suspend fun fetch(url: String): PortResult<WebPage>
    suspend fun readFeed(url: String): PortResult<List<FeedItem>>
}

object WebLimits {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_REDIRECTS = 3

    /** 整次请求（连接 + 每一跳 + 读完正文）的总时限。 */
    const val TIMEOUT_MS = 15_000
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 10_000
}

/** F10-4：Content-Type 只认网页 / 纯文本；RSS 只认 XML 类。 */
object WebContent {
    fun mediaType(ct: String): String = ct.substringBefore(';').trim().lowercase()

    fun isHtmlLike(ct: String): Boolean {
        val m = mediaType(ct)
        // 有些服务器不给 Content-Type：按网页试一次，解析不出正文会明确失败（NO_BODY）
        if (m.isBlank()) return true
        return m == "text/html" || m == "application/xhtml+xml" || m == "text/plain"
    }

    fun isXml(ct: String): Boolean {
        val m = mediaType(ct)
        if (m.isBlank()) return true
        return m == "application/rss+xml" || m == "application/atom+xml" || m == "application/xml" ||
            m == "text/xml" || m.endsWith("+xml")
    }

    fun label(ct: String): String = mediaType(ct).ifBlank { "没写类型" }
}

/** F10-4：charset 先看头部，再看 meta charset / XML 声明（GBK 页面靠这个）。 */
object WebText {
    private val CHARSET_RE = Regex("""charset\s*=\s*["']?([\w.:+-]+)""", RegexOption.IGNORE_CASE)
    private val XML_ENCODING_RE = Regex("""encoding\s*=\s*["']([\w.:+-]+)["']""", RegexOption.IGNORE_CASE)

    fun headerCharset(contentType: String): String? =
        CHARSET_RE.find(contentType)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    fun sniff(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        return CHARSET_RE.find(head)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: XML_ENCODING_RE.find(head)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    fun decode(bytes: ByteArray, contentType: String): String {
        val name = headerCharset(contentType) ?: sniff(bytes) ?: "UTF-8"
        val charset = runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        return runCatching { String(bytes, charset) }.getOrDefault(String(bytes, Charsets.UTF_8))
    }
}

class HttpWebPort(
    private val searchKey: String = "",
    private val searchEndpoint: String = "https://api.search.brave.com/res/v1/web/search",
    /** 只给测试用：允许访问本机假服务器；生产路径永远 false。 */
    private val allowPrivateForTest: Boolean = false,
) : WebPort {

    /** 解析到本机 / 内网时抛这个：外层能给出 BLOCKED，而不是含糊的“网络没接上”。 */
    private class BlockedHost(message: String) : UnknownHostException(message)

    // F10-1：OkHttp 的 Dns 只查一次；查到的地址里有一条是内网就整条拒绝，连接只用这份结果，防 DNS 重绑定。
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(WebLimits.CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(WebLimits.READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addrs = InetAddress.getAllByName(hostname).toList()
                if (!allowPrivateForTest && addrs.any { Sources.isBlockedAddress(it) }) {
                    throw BlockedHost(hostname + " 指向本机或内网，不访问")
                }
                return addrs
            }
        })
        .build()

    override suspend fun search(query: String, limit: Int): PortResult<List<SearchHit>> {
        if (searchKey.isBlank()) return PortResult.Failed(PortError("NOT_CONFIGURED", "还没有配置搜索服务"))
        if (query.isBlank()) return PortResult.Failed(PortError("BAD_QUERY", "搜索词是空的"))
        val u = searchEndpoint + "?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&count=" + limit.coerceIn(1, 10)
        val body = get(u, mapOf("X-Subscription-Token" to searchKey, "Accept" to "application/json"))
        return when (body) {
            is Failed -> PortResult.Failed(body.error)
            is Fetch -> {
                val arr = runCatching {
                    Json.parseToJsonElement(body.text()).jsonObject["web"]?.jsonObject?.get("results")?.jsonArray
                }.getOrNull() ?: return PortResult.Failed(PortError("PARSE", "搜索服务返回的格式看不懂"))
                val hits = arr.mapNotNull { e ->
                    val o = e.jsonObject
                    val url = o["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    SearchHit(url, o["title"]?.jsonPrimitive?.content.orEmpty(), o["description"]?.jsonPrimitive?.content.orEmpty())
                }
                when {
                    hits.isNotEmpty() -> PortResult.Ok(hits.take(limit))
                    else -> PortResult.Failed(PortError("EMPTY", "没有搜到结果"))
                }
            }
        }
    }

    override suspend fun fetch(url: String): PortResult<WebPage> {
        val r = get(url, mapOf("Accept" to "text/html,application/xhtml+xml,text/plain"))
        return when (r) {
            is Failed -> PortResult.Failed(r.error)
            is Fetch -> {
                if (!WebContent.isHtmlLike(r.contentType)) {
                    PortResult.Failed(PortError("UNSUPPORTED", "这个链接不是网页（" + WebContent.label(r.contentType) + "），没有当材料"))
                } else {
                    val raw = r.text()
                    val text = HtmlText.from(raw)
                    if (text.isBlank()) PortResult.Failed(PortError("NO_BODY", "这个页面没有可读正文（可能需要登录，或不是网页）"))
                    else PortResult.Ok(WebPage(url, r.finalUrl, HtmlText.title(raw), text, System.currentTimeMillis(), text.length < raw.length))
                }
            }
        }
    }

    override suspend fun readFeed(url: String): PortResult<List<FeedItem>> =
        when (val r = get(url, mapOf("Accept" to "application/rss+xml,application/atom+xml,application/xml,text/xml"))) {
            is Failed -> PortResult.Failed(r.error)
            is Fetch -> if (!WebContent.isXml(r.contentType)) {
                PortResult.Failed(PortError("UNSUPPORTED", "这个链接不是订阅源（" + WebContent.label(r.contentType) + "）"))
            } else Rss.parse(r.text())
        }

    private data class Fetch(val finalUrl: String, val bytes: ByteArray, val contentType: String) : Got {
        fun text(): String = WebText.decode(bytes, contentType)
    }

    private data class Failed(val error: PortError) : Got

    private sealed interface Got

    private suspend fun get(raw: String, headers: Map<String, String>): Got =
        withContext(Dispatchers.IO) { hopLoop(raw, headers) }

    private fun hopLoop(raw: String, headers: Map<String, String>): Got {
        var current = raw.trim()
        var hop = 0
        while (true) {
            // F10-1：每一跳（含重定向后的地址）都重新校验，并解析一次域名
            val bad = Sources.checkUrl(current, allowPrivateForTest)
            if (bad != null) return Failed(PortError("BLOCKED", bad))
            val request = try {
                Request.Builder().url(current).apply {
                    header("User-Agent", "Moling/1.0 (personal assistant)")
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
            } catch (e: Exception) {
                return Failed(PortError("BAD_URL", "链接看不懂"))
            }
            val response = try {
                client.newCall(request).execute()
            } catch (e: BlockedHost) {
                return Failed(PortError("BLOCKED", e.message ?: "不访问内网地址"))
            } catch (e: Exception) {
                return Failed(PortError("NETWORK", "网络没接上：" + (e.message ?: e.javaClass.simpleName)))
            }
            // null = 这一跳是重定向，继续下一跳
            val next: Got? = response.use { resp ->
                val code = resp.code
                if (code in 300..399) {
                    val loc = resp.header("Location")
                    if (loc.isNullOrBlank()) return@use Failed(PortError("BAD_REDIRECT", "重定向没有目标"))
                    if (hop >= WebLimits.MAX_REDIRECTS) return@use Failed(PortError("TOO_MANY_REDIRECTS", "跳转太多"))
                    current = runCatching { URI(current).resolve(loc).toString() }
                        .getOrElse { return@use Failed(PortError("BAD_REDIRECT", "重定向目标看不懂")) }
                    hop += 1
                    null
                } else if (code == 401 || code == 403) {
                    Failed(PortError("HTTP_" + code, "需要登录或没有权限（" + code + "）"))
                } else if (code == 429) {
                    Failed(PortError("HTTP_429", "请求太频繁了，等一会儿再试"))
                } else if (code !in 200..299) {
                    Failed(PortError("HTTP_" + code, "服务器返回 " + code))
                } else {
                    val contentType = resp.header("Content-Type").orEmpty()
                    val body = resp.body
                    if (body == null) {
                        Failed(PortError("EMPTY_BODY", "服务器没有返回正文"))
                    } else {
                        val bytes = readCapped(body.byteStream(), WebLimits.MAX_BYTES)
                        if (bytes == null) Failed(PortError("TOO_LARGE", "页面超过 2MB，先不读"))
                        else Fetch(current, bytes, contentType)
                    }
                }
            }
            if (next != null) return next
        }
    }

    companion object {
        /** 读正文，超过 [max] 直接返回 null（TOO_LARGE），不把超大页面读进内存再判断。 */
        private fun readCapped(stream: InputStream, max: Int): ByteArray? {
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > max) return null
            }
            return buf.toByteArray()
        }
    }
}

object HtmlText {
    fun title(html: String): String =
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { decode(strip(it)).trim() }.orEmpty()

    fun from(html: String): String {
        val noHead = html.replace(Regex("(?is)<head[^>]*>.*?</head>"), " ").replace(Regex("(?is)<title[^>]*>.*?</title>"), " ")
        val noScript = noHead.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
        val noSvg = noScript.replace(Regex("(?is)<svg[^>]*>.*?</svg>"), " ")
        val text = noSvg.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|h[1-6]|tr)>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), " ")
        return decode(text).replace(Regex("[ \t]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun strip(s: String) = s.replace(Regex("(?s)<[^>]+>"), " ")
    private fun decode(s: String): String = s
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&mdash;", "—").replace("&hellip;", "…")
}

object Rss {
    fun parse(xml: String): PortResult<List<FeedItem>> {
        val doc = runCatching {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            f.newDocumentBuilder().parse(xml.byteInputStream())
        }.getOrNull() ?: return PortResult.Failed(PortError("PARSE", "这个订阅源不是合法的 XML"))
        val items = doc.getElementsByTagName("item")
        val entries = if (items.length > 0) items else doc.getElementsByTagName("entry")
        val out = ArrayList<FeedItem>()
        for (i in 0 until entries.length) {
            val e = entries.item(i) as? Element ?: continue
            val title = child(e, "title")
            val link = if (entries.item(i).nodeName == "entry") {
                val links = e.getElementsByTagName("link")
                (0 until links.length).map { links.item(it) as? Element }.firstOrNull { it?.getAttribute("rel") != "enclosure" }?.getAttribute("href").orEmpty()
            } else child(e, "link")
            val summary = child(e, "description").ifBlank { child(e, "summary") }.ifBlank { child(e, "content") }
            if (link.isNotBlank() && title.isNotBlank()) out += FeedItem(link, HtmlText.from(title), HtmlText.from(summary).take(500))
        }
        return if (out.isEmpty()) PortResult.Failed(PortError("EMPTY", "这个订阅源里没有条目")) else PortResult.Ok(out.take(50))
    }

    private fun child(e: Element, name: String): String {
        val n = e.getElementsByTagName(name)
        return if (n.length == 0) "" else n.item(0).textContent.orEmpty()
    }
}
