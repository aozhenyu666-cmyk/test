package com.moling.core.web

import kotlinx.serialization.Serializable
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

    /** 相同内容/链接复用已有来源；返回 null 表示不需要新增。 */
    fun reuse(st: SourceState, url: String, contentHash: String): SourceRef? {
        val canon = canonical(url)
        return st.sources.firstOrNull { (canon.isNotBlank() && canonical(it.url) == canon) || (contentHash.isNotBlank() && it.contentHash == contentHash) }
    }

    fun put(st: SourceState, ref: SourceRef, text: String): SourceState {
        val existing = reuse(st, ref.url, ref.contentHash)
        if (existing != null) {
            // 相同链接/内容复用已有来源，不新建一条；正文跟着刷新。
            return st.copy(documents = st.documents.filterNot { it.sourceId == existing.id } + SourceDocument(existing.id, text.take(MAX_TEXT), text.toByteArray().size, ref.fetchedAt))
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

    fun canonical(raw: String): String = runCatching {
        val u = URI(raw.trim())
        val host = u.host?.lowercase()?.removePrefix("www.") ?: return ""
        val port = if (u.port > 0) ":" + u.port else ""
        (u.scheme ?: "https").lowercase() + "://" + host + port + (u.path ?: "").trimEnd('/')
    }.getOrDefault("")

    /** 公网 http(s) 且不是本机 / 内网 / 元数据地址；重定向也按这个校验。 */
    fun checkUrl(raw: String): String? {
        val u = runCatching { URI(raw.trim()) }.getOrNull() ?: return "链接看不懂"
        if (u.scheme !in setOf("http", "https")) return "只支持 http(s) 链接"
        val host = u.host?.lowercase() ?: return "链接里没有域名"
        if (host == "localhost" || host.endsWith(".local") || host == "::1") return "不访问本机地址"
        if (host == "169.254.169.254" || host.startsWith("169.254.")) return "不访问元数据地址"
        Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""").matchEntire(host)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return "域名不对"
            val b = m.groupValues[2].toIntOrNull() ?: return "域名不对"
            if (a == 10 || a == 127 || a == 0 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)) {
                return "不访问内网地址"
            }
        }
        return null
    }

    fun refId(prefix: String, canonicalUrl: String, hash: String): String =
        prefix + "-" + (if (canonicalUrl.isNotBlank()) hashOf(canonicalUrl) else hash).take(12)

    private fun hashOf(s: String): String = hash(s)
}