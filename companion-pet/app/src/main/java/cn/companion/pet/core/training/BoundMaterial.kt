package cn.companion.pet.core.training

import cn.companion.pet.core.companion.MaterialContentType
import cn.companion.pet.core.companion.SharedMaterial
import java.security.MessageDigest

/**
 * 绑定的材料：把“这一次要围绕哪份材料、哪个版本”固定下来。
 *
 * 为什么必须绑版本：同一份材料被重新导入/编辑后，正文变了，
 * 旧的题目和评分依据就不再对应当前材料，必须重新生成，不能套用旧题。
 *
 * 诚实边界：
 * - 只有链接、没有正文（LINK_ONLY）时 `hasReadableBody = false`，
 *   **不允许**据此生成“理解文章内容”的问题；只能说读不到正文。
 * - 摘录不等于全文，提示词和 UI 都要保留这个证据等级。
 */
data class BoundMaterial(
    val materialId: String,
    val version: Int,
    val bodyHash: String,
    val contentType: MaterialContentType,
    val title: String,
    val body: String,
    val url: String?,
    val receivedAt: Long,
) {
    /** 有没有可读正文。只有链接时为 false。 */
    val hasReadableBody: Boolean
        get() = contentType != MaterialContentType.LINK_ONLY && body.trim().length >= MIN_BODY_CHARS

    /** 证据等级说明，直接给 UI 和提示词用，避免把摘录当全文。 */
    val evidenceNote: String
        get() = when {
            !hasReadableBody -> "只有链接和标题，没有正文内容"
            contentType == MaterialContentType.EXCERPT -> "用户分享的摘录，不等于全文"
            contentType == MaterialContentType.TRANSCRIPT -> "用户分享的字幕/全文"
            else -> "用户分享的正文"
        }

    companion object {
        /** 少于这个字数就不足以出“理解内容”的题。 */
        const val MIN_BODY_CHARS = 40

        fun of(material: SharedMaterial): BoundMaterial {
            val body = material.body.trim()
            val hash = shortHash(material.contentType.name + "\u0000" + material.title + "\u0000" + body)
            return BoundMaterial(
                materialId = material.id,
                // 版本取自内容哈希：正文/标题/类型任一变化，版本就变。
                version = hash.hashCode(),
                bodyHash = hash,
                contentType = material.contentType,
                title = material.title,
                body = body,
                url = material.url,
                receivedAt = material.receivedAt,
            )
        }

        private fun shortHash(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return digest.take(8).joinToString("") { b -> "%02x".format(b) }
        }
    }
}

/** 从真实正文里选出的一句可追问的证据片段。 */
data class EvidenceSpan(
    val index: Int,
    val text: String,
    /** 命中的可核查线索：范围词或因果词。 */
    val cue: String,
)

/**
 * 一份材料生成出来的训练计划：问题、引用位置、评分依据、下一步。
 *
 * 这不是“模型理解了文章”，而是**从真实正文里按固定规则抽出一句**，
 * 再要求用户指出这一句的可核查特征。规则是确定性的、可复述的。
 */
data class MaterialQuestionPlan(
    val materialId: String,
    val materialVersion: Int,
    val questionId: String,
    val question: String,
    val span: EvidenceSpan,
    val allSpans: List<EvidenceSpan>,
    /** 评分依据：本地可判的要点。 */
    val rubricPoints: List<String>,
    /** 通过后进入的下一步。 */
    val nextStep: String,
)
