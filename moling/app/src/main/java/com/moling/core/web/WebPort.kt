package com.moling.core.web

import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/*
 * 第十批 T2：一个真的 WebPort。搜不到/没配置/超时都明确失败，生产路径不返回 mock 内容。
 * 安全：只公网 http(s)、拒绝本机/内网/元数据；重定向≤3、响应≤2MB、请求≤15s。
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
    const val TIMEOUT_MS = 15_000
}

class HttpWebPort(
    private val searchKey: String = "",
    private val searchEndpoint: String = "https://api.search.brave.com/res/v1/web/search",
    /** 只给测试用：允许访问本机假服务器；生产路径永远 false。 */
    private val allowPrivateForTest: Boolean = false,
) : WebPort {

    override suspend fun search(query: String, limit: Int): PortResult<List<SearchHit>> {
        if (searchKey.isBlank()) return PortResult.Failed(PortError("NOT_CONFIGURED", "还没有配置搜索服务"))
        if (query.isBlank()) return PortResult.Failed(PortError("BAD_QUERY", "搜索词是空的"))
        val u = searchEndpoint + "?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&count=" + limit.coerceIn(1, 10)
        val body = get(u, mapOf("X-Subscription-Token" to searchKey, "Accept" to "application/json"))
        return when (body) {
            is Fetch -> {
                val arr = runCatching {
                    Json.parseToJsonElement(body.text).jsonObject["web"]?.jsonObject?.get("results")?.jsonArray
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
            is Failed -> PortResult.Failed(body.error)
            else -> PortResult.Failed(PortError("UNKNOWN", "搜索返回了看不懂的结果"))
        }
    }

    override suspend fun fetch(url: String): PortResult<WebPage> = when (val r = get(url, mapOf("Accept" to "text/html,application/xhtml+xml,text/plain"))) {
        is Failed -> PortResult.Failed(r.error)
        is Fetch -> {
            val text = HtmlText.from(r.text)
            if (text.isBlank()) PortResult.Failed(PortError("NO_BODY", "这个页面没有可读正文（可能需要登录，或不是网页）"))
            else PortResult.Ok(WebPage(url, r.finalUrl, HtmlText.title(r.text), text, System.currentTimeMillis(), text.length < r.text.length))
        }
        else -> PortResult.Failed(PortError("UNKNOWN", "取网页返回了看不懂的结果"))
    }

    override suspend fun readFeed(url: String): PortResult<List<FeedItem>> = when (val r = get(url, mapOf("Accept" to "application/rss+xml,application/atom+xml,application/xml,text/xml"))) {
        is Failed -> PortResult.Failed(r.error)
        is Fetch -> Rss.parse(r.text)
        else -> PortResult.Failed(PortError("UNKNOWN", "读订阅返回了看不懂的结果"))
    }

    private data class Fetch(val finalUrl: String, val text: String)
    private data class Failed(val error: PortError)

    private fun get(raw: String, headers: Map<String, String>): Any {
        var current = raw.trim()
        repeat(WebLimits.MAX_REDIRECTS + 1) { hop ->
            val bad = Sources.checkUrl(current)
            if (bad != null && !(allowPrivateForTest && bad.contains("内网"))) return Failed(PortError("BLOCKED", bad))
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = WebLimits.TIMEOUT_MS
                    readTimeout = WebLimits.TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Moling/1.0 (personal assistant)")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return Failed(PortError("BAD_REDIRECT", "重定向没有目标"))
                    if (hop >= WebLimits.MAX_REDIRECTS) return Failed(PortError("BAD_REDIRECT", "跳转太多"))
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code == 401 || code == 403) return Failed(PortError("HTTP_" + code, "需要登录或没有权限（" + code + "）"))
                if (code == 429) return Failed(PortError("HTTP_429", "请求太频繁了，等一会儿再试"))
                if (code !in 200..299) return Failed(PortError("HTTP_" + code, "服务器返回 " + code))
                val stream = conn.inputStream
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > WebLimits.MAX_BYTES) return Failed(PortError("TOO_LARGE", "页面超过 2MB，先不读"))
                }
                val charset = conn.contentType?.substringAfter("charset=", "")?.trim()?.trim('"')?.ifBlank { null } ?: "UTF-8"
                val text = runCatching { buf.toString(charset) }.getOrElse { buf.toString("UTF-8") }
                return Fetch(current, text)
            } catch (e: Exception) {
                return Failed(PortError("NETWORK", "网络没接上：" + (e.message ?: e.javaClass.simpleName)))
            } finally {
                conn?.disconnect()
            }
        }
        return Failed(PortError("BAD_REDIRECT", "跳转太多"))
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
        return decode(text).replace(Regex("[ \\t]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
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
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            f.setFeature("http://xml.org/sax/features/external-general-entities", false)
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
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