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

/**
 * 知识库的一个节点：她“懂”的那一块事。
 *
 * 为什么要有它：没有知识，卡片只能给模板（“去和 Gemini 聊十分钟，第一问我准备好了”），
 * 用户看到的就是一个空架子。节点里放的是**这一块要能做到什么、已知什么、还缺什么、材料在哪、哪种问法管用**，
 * 出卡和开场白都从这里取，问题才会具体。
 *
 * 写入：收线（结论→known，未解→open）、夜里整理（反复出现→known）、用户说“记住【主题】：…”。
 * 读取：出卡前、开场白的三问、夜里的日记。
 */
@Serializable
data class TopicNode(
    val id: String,
    val title: String,
    /** 这一块要做到什么（一句话）。 */
    val goal: String = "",
    /** 已经确认理解 / 做到的。 */
    val known: List<String> = emptyList(),
    /** 还说不清的（下一场就从这里问）。 */
    val open: List<String> = emptyList(),
    /** 题目、材料、出处。 */
    val materials: List<String> = emptyList(),
    /** 问到点子上的问法（会被复用）。 */
    val goodAsks: List<String> = emptyList(),
    /** 问了他也答不出的问法（别再问）。 */
    val badAsks: List<String> = emptyList(),
    val sessions: Int = 0,
    val lastAt: Long = 0,
)

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
    /** 知识库：她懂的那些事。第一次会话就在建库。 */
    val topicsKB: List<TopicNode> = emptyList(),
    /** 现在主要在攻哪一块。 */
    val activeTopicId: String = "",
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

    /** 现在在攻的那一块；没指定就取最近动过的。 */
    val activeTopic: TopicNode?
        get() = topicsKB.firstOrNull { it.id == activeTopicId } ?: topicsKB.maxByOrNull { it.lastAt }

    fun topicById(id: String): TopicNode? = topicsKB.firstOrNull { it.id == id }

    fun costToday(day: String): CostDay = costs.firstOrNull { it.day == day } ?: CostDay(day)
}

/** 知识库的增删改：纯函数，方便单测，也不让引擎里到处拼列表。 */
object Knowledge {
    /** 从标题推出一个稳定的 id（中文按内容哈希，英文数字直接留）。 */
    fun idOf(title: String): String {
        val t = title.trim()
        if (t.isEmpty()) return "kb-none"
        val ascii = t.filter { it.isLetterOrDigit() && it.code < 128 }.lowercase()
        return if (ascii.length >= 3) ascii.take(20) else "kb-" + (t.hashCode().toUInt().toString(16))
    }

    fun upsert(list: List<TopicNode>, node: TopicNode): List<TopicNode> =
        (list.filter { it.id != node.id } + node).sortedByDescending { it.lastAt }

    /** 没有就建一个（第一次会话就是建库）。 */
    fun ensure(list: List<TopicNode>, title: String, goal: String, now: Long): TopicNode {
        val id = idOf(title)
        return list.firstOrNull { it.id == id } ?: TopicNode(id = id, title = title.trim(), goal = goal.trim(), lastAt = now)
    }

    /**
     * 一次收线之后的学习：结论进 known，未解进 open，顺带记一次场次。
     * 去重、限长，避免同一个知识被反复写。
     */
    fun learn(node: TopicNode, conclusion: String, open: String, ask: String, now: Long): TopicNode {
        fun add(old: List<String>, v: String): List<String> {
            val t = v.trim().take(80)
            if (t.length < 4) return old
            if (old.any { it.contains(t) || t.contains(it) }) return old
            return (old + t).takeLast(8)
        }
        return node.copy(
            known = add(node.known, conclusion),
            open = add(node.open, open),
            goodAsks = if (ask.isBlank()) node.goodAsks else add(node.goodAsks, ask),
            sessions = node.sessions + 1,
            lastAt = now,
        )
    }
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
        topicsKB = m.topicsKB.sortedByDescending { it.lastAt }.take(12),
    )
}
