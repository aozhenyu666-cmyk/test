package cn.companion.pet.core.companion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一条伙伴事件。事件一旦写入就不可变：纠正、遗忘通过追加新的纠错/遗忘事件表达，
 * 不回头改写历史。这样重放、恢复和审计都能看到真实发生过什么。
 *
 * [day] 是写入时按设备时区算出的归属日（ISO-8601），不是每次读取时重新推算的日期。
 * [text] 只存已经清洗过的一行文本；原始屏幕、剪贴板、完整 prompt 不允许进入这里。
 */
@Serializable
data class CompanionEvent(
    val id: String,
    val occurredAt: Long,
    val day: String,
    val kind: EventKind,
    val text: String,
    val source: String = "chat",
    /** 同一次交流里的开口、回应、接话共用一个 id，方便夜间整理还原成一行。 */
    val conversationId: String? = null,
    /** 这条事件由哪些更早的事件派生（例如夜间摘要引用的事件）。 */
    val sourceIds: List<String> = emptyList(),
    /** 稳定事实键；同键的新事实会让旧事实失效。 */
    val factKey: String? = null,
    val confidence: Confidence = Confidence.SAID,
    /** Optional source lifetime. Derived material stops being usable even before disk cleanup runs. */
    val expiresAt: Long? = null,
)

@Serializable
enum class EventKind {
    @SerialName("user_input") USER_INPUT,
    @SerialName("pet_reply") PET_REPLY,
    @SerialName("proactive") PROACTIVE,
    @SerialName("exchange") EXCHANGE,
    @SerialName("correction") CORRECTION,
    @SerialName("forget") FORGET,
    @SerialName("fact") FACT,
    @SerialName("material") MATERIAL,
    @SerialName("material_summary") MATERIAL_SUMMARY,
    @SerialName("note") NOTE,
    @SerialName("session") SESSION,
    @SerialName("ignored") IGNORED,
    @SerialName("legacy_line") LEGACY_LINE,
}

@Serializable
enum class Confidence {
    @SerialName("said") SAID,
    @SerialName("guessed") GUESSED,
}

/** 事件文件的唯一编解码器。未知字段忽略，方便以后加字段而不破坏旧日志。 */
val EventJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
