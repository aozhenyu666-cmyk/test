package cn.companion.pet.core.companion

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * 一条可核对的事实。[confidence] 区分“他亲口说的”和“我推测的”，二者在提示词里
 * 的来源标签不同，用户也可以确认推测、更正事实或忘记。
 *
 * 版本关系用 [supersededBy] 表达：同一 factKey 的新事实让旧事实失效，但旧记录不删除，
 * 方便审计。用户点“忘记”时置 [deleted]，并在 MemoryStore 里留 tombstone，
 * 防止旧来源重放又把内容写回来。
 */
@Serializable
data class Fact(
    val id: String,
    val factKey: String,
    val text: String,
    val confidence: Confidence,
    val createdAt: Long,
    val sourceEventIds: List<String> = emptyList(),
    val version: Int = 1,
    val supersededBy: String? = null,
    val deleted: Boolean = false,
)

/**
 * factKey 的生成规则：
 * - 同一句/同一标签变体归一化后得到同一个 key；
 * - 不承诺识别所有同义改写，历史自由文本仍需要人工确认。
 */
object FactKeys {
    fun of(text: String): String {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return "fk-" + digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }.take(16)
    }

    /** 去掉来源标记、列表符号、空白，统一小写。 */
    fun normalize(text: String): String = text.trim()
        .replace(Regex("^(\\[他说过\\]|\\[我推测\\]|-)\\s*"), "")
        .replace(Regex("\\s+"), "")
        .lowercase()
}
