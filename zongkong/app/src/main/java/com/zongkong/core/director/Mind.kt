package com.zongkong.core.director

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

/*
 * 导演的记忆（施工队 B）。整份存成一段 JSON，由 [MindStore] 落到手机上。
 * 分类照 MIND.md 第 1.2 节：工作记忆（接力棒 + 议程）、情景记忆（日记、会话）、语义记忆（对用户的了解）、
 * 前瞻记忆（回头检查）、程序记忆（打法候选）、元记忆（每条都带来源和把握）。
 * 感觉记忆（原始事件）只留最近一批，用于夜间整理。
 */

val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")

/** 北京时间的日期键，凌晨 4 点换日（和总控一致）。 */
fun dayKey(at: Long): String = Instant.ofEpochMilli(at - 4 * 3_600_000L).atZone(BEIJING).toLocalDate().toString()

fun hourOf(at: Long): Int = Instant.ofEpochMilli(at).atZone(BEIJING).hour

@Serializable
enum class MemoryKind(val label: String) {
    FACT("事实"),
    PREFERENCE("偏好"),
    PATTERN("模式"),
    TACTIC("打法"),
}

/** 一条语义记忆。[key] 相同表示说的是同一件事，用来发现矛盾和累计次数。 */
@Serializable
data class MemoryItem(
    val id: String,
    val kind: MemoryKind,
    val key: String,
    val text: String,
    /** 用户 / 总控 / 导演 / gemini / chatgpt。 */
    val source: String,
    /** 高 / 中 / 低。 */
    val confidence: String = "低",
    val confirmed: Boolean = false,
    val createdAt: Long,
    val lastSeenAt: Long,
    val hits: Int = 1,
)

/** 情景记忆：一天的经过。 */
@Serializable
data class Episode(val day: String, val text: String, val at: Long)

/** 她的日记：伙伴口吻的三五句。 */
@Serializable
data class DiaryEntry(val day: String, val text: String, val at: Long, val byModel: Boolean)

/** 系统自己的失败。 */
@Serializable
data class Incident(
    val id: String,
    /** 无障碍 / 被划掉 / 交接失败 / 猜错 / 没收线 / 说好的没开始 / 模型不可用。 */
    val kind: String,
    val at: Long,
    val detail: String,
    val resolvedAt: Long = 0,
)

/** 假设账本的一条。 */
@Serializable
data class Hypothesis(
    val id: String,
    val topic: String,
    val fact: String,
    val guess: String,
    val alternatives: List<String>,
    val check: String,
    val confidence: String,
    val at: Long,
    /** 待验证 / 对了 / 错了 / 说不清。 */
    val outcome: String = "待验证",
    val outcomeNote: String = "",
)

/** 议程里的一件事。同一 [topic] 只留一条。 */
@Serializable
data class AgendaItem(
    val topic: String,
    val priority: Int,
    val card: Card,
    val createdAt: Long,
    val expiresAt: Long,
)

/** 前瞻记忆：到点回头看一件事。 */
@Serializable
data class FollowUp(
    val id: String,
    val batonId: String,
    val what: String,
    val dueAt: Long,
    /** 约定开始的时间，用来判断“之后有没有进展”。 */
    val since: Long,
    val done: Boolean = false,
)

/** 一天的模型花费估算。 */
@Serializable
data class CostDay(
    val day: String,
    val calls: Int = 0,
    val charsIn: Long = 0,
    val charsOut: Long = 0,
    val failures: Int = 0,
)

/** 一件事打扰过几次、最近一次什么时候、被无视了几次（避免反复唠叨）。 */
@Serializable
data class TopicState(val lastAt: Long = 0, val countToday: Int = 0, val ignored: Int = 0, val day: String = "")

@Serializable
data class MindState(
    val batons: List<Baton> = emptyList(),
    val currentBatonId: String = "",
    val agenda: List<AgendaItem> = emptyList(),
    val memories: List<MemoryItem> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val diary: List<DiaryEntry> = emptyList(),
    val incidents: List<Incident> = emptyList(),
    val hypotheses: List<Hypothesis> = emptyList(),
    val followUps: List<FollowUp> = emptyList(),
    val topics: Map<String, TopicState> = emptyMap(),
    /** 最近的感觉记忆（精简后的事件），夜间整理用。 */
    val recent: List<Sense> = emptyList(),
    val sessions: List<SessionLog> = emptyList(),
    val costs: List<CostDay> = emptyList(),
    /** 今天打扰了几次（主动弹出的卡）。 */
    val pushesDay: String = "",
    val pushesToday: Int = 0,
    val lastNightDay: String = "",
    /** 累计计数，夜间整理时用来发现重复出现的模式：key → 出现过的日期。 */
    val tallies: Map<String, List<String>> = emptyMap(),
) {
    val baton: Baton? get() = batons.firstOrNull { it.id == currentBatonId && !it.closed }

    fun costToday(day: String): CostDay = costs.firstOrNull { it.day == day } ?: CostDay(day)
}

object MindCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun encode(m: MindState): String = json.encodeToString(MindState.serializer(), trim(m))

    /** 读坏了不抛异常，返回空记忆（[MindStore] 实现负责保留上一份备份）。 */
    fun decode(s: String?): MindState = tryDecode(s) ?: MindState()

    /** 读不出来返回 null（用来判断文件是否写坏了）。 */
    fun tryDecode(s: String?): MindState? = if (s.isNullOrBlank()) null else runCatching { json.decodeFromString(MindState.serializer(), s) }.getOrNull()

    /** 控制体积：每类只留最近一批。 */
    fun trim(m: MindState): MindState = m.copy(
        batons = m.batons.sortedByDescending { it.updatedAt }.take(20),
        memories = m.memories.sortedByDescending { it.lastSeenAt }.take(200),
        episodes = m.episodes.takeLast(60),
        diary = m.diary.takeLast(60),
        incidents = m.incidents.takeLast(200),
        hypotheses = m.hypotheses.takeLast(100),
        followUps = m.followUps.filter { !it.done }.takeLast(30) + m.followUps.filter { it.done }.takeLast(20),
        recent = m.recent.takeLast(400),
        sessions = m.sessions.takeLast(40),
        costs = m.costs.takeLast(30),
        tallies = m.tallies.mapValues { (_, v) -> v.takeLast(14) },
    )
}
