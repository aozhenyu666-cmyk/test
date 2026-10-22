package com.moling.core.web

import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest

/*
 * 第十批 T1：生产来源模型。引用证明“这段话来自哪里”，不证明“这段话是真的”；
 * 正文单独存，避免把大段网页塞进 BrainState / sayQueue。
 */

@Serializable
enum class SourceChannel { PASTE, SHARE, URL, RSS, SEARCH, WEB }

@Serializable
enum class SourceStatus { OK, PARTIAL, FAILED, DELETED }

@Serializable
data class SourceRef(
    val id: String,
    val channel: SourceChannel,
    val url: String = "",
    val title: String = "",
    val fetchedAt: Long = 0,
    val snippet: String = "",
    val contentHash: String = "",
    val status: SourceStatus = SourceStatus.OK,
    val complete: Boolean = false,
    val focusId: String = "",
    val error: String = "",
)

@Serializable
data class SourceDocument(
    val sourceId: String,
    val text: String,
    val bytes: Int,
    val at: Long,
)

/** 真实 Material.id → SourceRef.id；旧 sourceIds 解析器继续看到 Material.id。 */
@Serializable
data class SourceAttachment(val materialId: String, val sourceId: String, val at: Long)

@Serializable
data class SourceState(
    val sources: List<SourceRef> = emptyList(),
    val documents: List<SourceDocument> = emptyList(),
    val attachments: List<SourceAttachment> = emptyList(),
) {
    fun ref(id: String): SourceRef? = sources.firstOrNull { it.id == id }
    fun document(sourceId: String): SourceDocument? = documents.firstOrNull { it.sourceId == sourceId }
    fun attachmentOfMaterial(materialId: String): SourceAttachment? = attachments.lastOrNull { it.materialId == materialId }
    fun refOfMaterial(materialId: String): SourceRef? = attachmentOfMaterial(materialId)?.let { ref(it.sourceId) }
    fun materialsOfSource(sourceId: String): List<String> = attachments.filter { it.sourceId == sourceId }.map { it.materialId }
}

object Sources {
    const val MAX_TEXT = 200_000

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    /**
     * 同一个规范化 URL 才算同一个来源；**不按正文 hash 合并**——
     * 两篇只差 mid= 的文章、两个都返回登录墙的链接，都是两个来源。
     */
    fun reuse(st: SourceState, url: String): SourceRef? {
        val canon = canonical(url)
        if (canon.isBlank()) return null
        return st.sources.lastOrNull { canonical(it.url) == canon }
    }

    fun put(st: SourceState, ref: SourceRef, text: String): SourceState {
        val existing = reuse(st, ref.url)
        if (existing != null) {
            // 同一个 URL：更新 hash / 标题 / 时间 / 状态，不悄悄换掉已经存下的正文（换正文要显式刷新）
            val updated = existing.copy(
                title = ref.title.ifBlank { existing.title },
                fetchedAt = ref.fetchedAt,
                contentHash = ref.contentHash.ifBlank { existing.contentHash },
                status = ref.status,
                complete = ref.complete,
                focusId = ref.focusId.ifBlank { existing.focusId },
                snippet = ref.snippet.ifBlank { existing.snippet },
            )
            val docs = if (st.documents.any { it.sourceId == existing.id }) st.documents
            else st.documents + SourceDocument(existing.id, text.take(MAX_TEXT), text.toByteArray().size, ref.fetchedAt)
            return st.copy(
                sources = st.sources.map { if (it.id == existing.id) updated else it },
                documents = docs,
            )
        }
        return st.copy(
            sources = (st.sources.filterNot { it.id == ref.id } + ref).takeLast(500),
            documents = (st.documents.filterNot { it.sourceId == ref.id } + SourceDocument(ref.id, text.take(MAX_TEXT), text.toByteArray().size, ref.fetchedAt)).takeLast(500),
        )
    }

    /** 删除正文，来源对象保留为 DELETED（标签显示“原文已删除”，不能假装还有）。 */
    fun delete(st: SourceState, sourceId: String): SourceState = st.copy(
        sources = st.sources.map { if (it.id == sourceId) it.copy(status = SourceStatus.DELETED, complete = false) else it },
        documents = st.documents.filterNot { it.sourceId == sourceId },
    )

    fun attach(st: SourceState, materialId: String, sourceId: String, now: Long): SourceState {
        if (st.attachments.any { it.materialId == materialId && it.sourceId == sourceId }) return st
        return st.copy(attachments = (st.attachments + SourceAttachment(materialId, sourceId, now)).takeLast(1000))
    }

    /** 规范化链接：保留 query（只去掉 utm_*），丢掉 #…，去掉默认端口和末尾 /。 */
    fun canonical(raw: String): String = runCatching {
        val u = URI(raw.trim())
        val scheme = (u.scheme ?: "").lowercase()
        if (scheme != "http" && scheme != "https") return ""
        val host = u.host?.lowercase()?.removePrefix("www.") ?: return ""
        if (host.isBlank()) return ""
        val port = if (u.port > 0 && !((scheme == "http" && u.port == 80) || (scheme == "https" && u.port == 443))) ":" + u.port else ""
        val query = (u.rawQuery ?: "").split('&')
            .filter { it.isNotBlank() && !it.startsWith("utm_", ignoreCase = true) }
            .joinToString("&")
        scheme + "://" + host + port + (u.rawPath ?: "").trimEnd('/') + if (query.isNotBlank()) "?" + query else ""
    }.getOrDefault("")

    /**
     * 公网 http(s) 且不是本机 / 内网 / 元数据地址；重定向每一跳都要再调一次。
     *
     * F10-1：先按字面地址判；域名则 InetAddress.getAllByName 解析一次，**任一**地址是
     * 回环 / any-local / 链路本地 / site-local / 组播 / fc00::/7 / 100.64/10 / IPv4 映射到这些就拒绝。
     * [allowPrivate] 只给测试用（MockWebServer 在 localhost），生产路径永远 false。
     * [resolve] 只给测试注入“域名解析到内网”的假解析器；解析不出来不在这里判死
     * （连接侧 OkHttp 的 Dns 钩子会再查一次并拒绝内网，那里才是最终防线）。
     */
    fun checkUrl(
        raw: String,
        allowPrivate: Boolean = false,
        resolve: (String) -> List<InetAddress> = ::resolveHost,
    ): String? {
        val u = runCatching { URI(raw.trim()) }.getOrNull() ?: return "链接看不懂"
        if (u.scheme !in setOf("http", "https")) return "只支持 http(s) 链接"
        var host = u.host?.lowercase() ?: return "链接里没有域名"
        host = host.removePrefix("[").removeSuffix("]")
        if (host.isBlank()) return "链接里没有域名"
        // F10-3：仅测试放行钩子（构造参数注入），生产路径 false
        if (allowPrivate) return null
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return "不访问本机地址"
        if (host == "169.254.169.254" || host.startsWith("169.254.")) return "不访问元数据地址"
        if (isIpLiteral(host)) {
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return "链接里的地址看不懂"
            return if (isBlockedAddress(addr)) "不访问内网或本机地址" else null
        }
        val addrs = resolve(host)
        // 任一解析结果指向本机 / 内网就拒绝：DNS 记录里混一条私网地址也不行
        if (addrs.any { isBlockedAddress(it) }) return "不访问内网或本机地址（域名解析到本机或内网）"
        return null
    }

    /** 本机 / 内网 / 链路本地 / 组播 / fc00::/7 / 100.64/10 / IPv4 映射到这些 —— 一处判定，连接时复用。 */
    fun isBlockedAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) return true
        val b = addr.address
        if (b.size == 4) {
            val a = b[0].toInt() and 0xff
            val c = b[1].toInt() and 0xff
            if (a == 0) return true                         // 0.0.0.0/8“本网络”
            if (a == 10) return true                        // 私网 10/8（isSiteLocalAddress 已覆盖，这里兜底）
            if (a == 100 && c in 64..127) return true       // 100.64/10 运营商级 NAT
            if (a == 127) return true                       // 回环
            if (a == 169 && c == 254) return true           // 链路本地（含 169.254.169.254 元数据）
            if (a == 172 && c in 16..31) return true        // 私网 172.16/12
            if (a == 192 && c == 168) return true           // 私网 192.168/16
            if (a == 192 && c == 0) return true             // 192.0.0.0/24 + 192.0.2.0/24（保留 / 文档）
            if (a == 198 && c in 18..19) return true        // 198.18/15 基准测试网
            if (a >= 240) return true                       // 240/4 保留 + 广播
        } else if (b.size == 16) {
            val first = b[0].toInt() and 0xff
            if ((first and 0xfe) == 0xfc) return true       // fc00::/7 唯一本地地址
            if (b.all { it.toInt() == 0 }) return true      // :: 未指定
            // IPv4 映射 ::ffff:a.b.c.d：拆出后 4 字节再按 IPv4 判一次
            if ((0..9).all { b[it].toInt() == 0 } &&
                (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
            ) {
                val v4 = InetAddress.getByAddress(b.copyOfRange(12, 16))
                return isBlockedAddress(v4)
            }
        }
        return false
    }

    fun refId(prefix: String, canonicalUrl: String, hash: String): String =
        prefix + "-" + (if (canonicalUrl.isNotBlank()) hashOf(canonicalUrl) else hash).take(12)

    private fun hashOf(s: String): String = hash(s)

    /** 纯数字 / 点分 IPv4 / 含冒号的 IPv6 都当字面地址；其余当域名去解析。 */
    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') ||
            Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host) ||
            host.all { it.isDigit() }

    private fun resolveHost(host: String): List<InetAddress> =
        runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
}