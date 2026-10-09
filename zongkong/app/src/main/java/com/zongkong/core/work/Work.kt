package com.zongkong.core.work

import kotlinx.serialization.Serializable

/**
 * 三类共享记录，围绕“同一件事”串起来：
 *  - 事项（THREAD）：一件要持续推进的事。为什么做、当前判断、断点、下一步、完成依据都挂在它上面。
 *  - 行动（ACTION）：排进时间的具体一步，有完成依据、结果、卡点。
 *  - 记录（NOTE）：材料、问题、念头、结论、结果、断点……原始内容保留，挂到事项上。
 *
 * 三类都同步到 Notion 的三个数据库（字段名就是 Notion 里的列名），本机保留一份镜像。
 * 每条记录有一个本机 key；同步后再记下 Notion 页面 ID。
 */
@Serializable
enum class Kind(val label: String) { THREAD("事项"), ACTION("行动"), NOTE("记录") }

/** Notion 列名。中文列名，GPT 和人看 Notion 时都能直接读懂。 */
object F {
    const val TITLE = "标题"
    const val CODE = "编号"
    const val STATUS = "状态"
    const val WHY = "为什么"
    const val JUDGE = "当前判断"
    const val UNSURE = "不确定"
    const val BREAK = "断点"
    const val NEXT = "下一步"
    const val CRITERIA = "完成依据"
    const val PRIORITY = "优先级"
    const val SOURCE = "更新来源"
    const val ZKID = "总控ID"
    const val THREAD = "事项"
    const val THREAD_CODE = "事项编号"
    const val PLAN = "计划时间"
    const val RESULT = "结果"
    const val STUCK = "卡点"
    const val TYPE = "类型"
    const val URL = "来源"
    const val RAW = "原文"
    const val DATE = "日期"

    /** 每类记录有哪些列、在 Notion 里是什么类型。建库和写入都按这张表。 */
    val SCHEMA: Map<Kind, Map<String, String>> = mapOf(
        Kind.THREAD to linkedMapOf(
            TITLE to "title", CODE to "rich_text", STATUS to "select", WHY to "rich_text", JUDGE to "rich_text",
            UNSURE to "rich_text", BREAK to "rich_text", NEXT to "rich_text", CRITERIA to "rich_text",
            PRIORITY to "select", SOURCE to "select", ZKID to "rich_text",
        ),
        Kind.ACTION to linkedMapOf(
            TITLE to "title", THREAD to "relation", THREAD_CODE to "rich_text", STATUS to "select", PLAN to "date",
            CRITERIA to "rich_text", RESULT to "rich_text", BREAK to "rich_text", STUCK to "select",
            SOURCE to "select", ZKID to "rich_text",
        ),
        Kind.NOTE to linkedMapOf(
            TITLE to "title", THREAD to "relation", THREAD_CODE to "rich_text", TYPE to "select", URL to "url",
            RAW to "rich_text", DATE to "date", SOURCE to "select", ZKID to "rich_text",
        ),
    )
}

@Serializable
enum class ThreadStatus(val label: String) {
    COLLECTING("收集中"), THINKING("思考中"), READY("待行动"), ACTIVE("推进中"),
    STUCK("卡住"), DONE("已完成"), PAUSED("搁置"), CANCELLED("取消");

    val open: Boolean get() = this != DONE && this != CANCELLED && this != PAUSED

    companion object {
        fun of(label: String) = entries.firstOrNull { it.label == label.trim() } ?: COLLECTING
    }
}

@Serializable
enum class ActionStatus(val label: String) {
    TODO("待做"), DOING("进行中"), CONFIRM("待确认"), DONE("完成"), STUCK("卡住"), RESCHEDULED("改期"), CANCELLED("取消");

    /** 还需要去做的。 */
    val open: Boolean get() = this == TODO || this == DOING || this == STUCK || this == RESCHEDULED

    companion object {
        fun of(label: String) = when (label.trim()) {
            "已完成", "完成", "Done", "done" -> DONE
            else -> entries.firstOrNull { it.label == label.trim() } ?: TODO
        }
    }
}

@Serializable
enum class NoteType(val label: String) {
    MATERIAL("材料"), QUESTION("问题"), IDEA("念头"), CONCLUSION("结论"), RESULT("结果"),
    BREAKPOINT("断点"), STUCK("卡点"), REVIEW("验收"), CHECKIN("报到");

    companion object {
        fun of(label: String) = entries.firstOrNull { it.label == label.trim() } ?: IDEA
    }
}

/** 没有推进的原因。不同原因给不同的帮助。 */
@Serializable
enum class StuckReason(val label: String, val hint: String) {
    FORGOT("忘了", "没想起来"),
    CANT("不会做", "不知道怎么下手"),
    UNCLEAR("不清楚", "这一步到底要做什么、做到什么程度不清楚"),
    CONFLICT("时间冲突", "这个时间有别的事"),
    DISTRACTED("去娱乐了", "刷手机、看视频去了"),
    RELUCTANT("不想做", "知道该做，就是不想动"),
    ;

    companion object {
        fun of(label: String) = entries.firstOrNull { it.label == label.trim() }
    }
}

/** 本机记录和 Notion 的同步状态。界面如实显示。 */
@Serializable
enum class SyncState(val label: String) {
    LOCAL("仅本机"), PENDING("待同步"), SYNCED("已同步"), FAILED("同步失败"), CONFLICT("有冲突")
}

/**
 * 一条记录。字段统一存成字符串，键是 Notion 列名（见 [F]）。
 * - [base]：上次和 Notion 对齐时的值，用来判断“谁改了什么”
 * - [dirty]：本机改过、还没写到 Notion 的字段
 * - [conflicts]：两边都改了同一字段时，没被采用的那一份
 * 关联字段 [F.THREAD] 在本机存事项的 key，同步时换成 Notion 页面 ID。
 */
@Serializable
data class Rec(
    val kind: Kind,
    val key: String,
    val notionId: String = "",
    val url: String = "",
    val f: Map<String, String> = emptyMap(),
    val base: Map<String, String> = emptyMap(),
    val dirty: Set<String> = emptySet(),
    val remoteEdited: String = "",
    val sync: SyncState = SyncState.LOCAL,
    val syncError: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val conflicts: Map<String, String> = emptyMap(),
    /** 创建请求发出过但没确认成功：下次先按总控ID查，防止重复创建。 */
    val createTried: Boolean = false,
) {
    operator fun get(field: String): String = f[field].orEmpty()
    val title: String get() = this[F.TITLE].ifBlank { "（无标题）" }
    val threadKey: String get() = this[F.THREAD]
    val synced: Boolean get() = notionId.isNotEmpty() && dirty.isEmpty()

    val threadStatus: ThreadStatus get() = ThreadStatus.of(this[F.STATUS])
    val actionStatus: ActionStatus get() = ActionStatus.of(this[F.STATUS])
    val noteType: NoteType get() = NoteType.of(this[F.TYPE])
    val plan: Plan? get() = Plan.parse(this[F.PLAN])
}

/** 计划时间：开始（可只有日期）和可选的结束。存成 "start|end"，和 Notion 的 date 对应。 */
@Serializable
data class Plan(val start: String, val end: String = "") {
    val hasTime: Boolean get() = start.contains('T')
    fun encode(): String = if (end.isBlank()) start else "$start|$end"

    companion object {
        fun parse(s: String): Plan? {
            if (s.isBlank()) return null
            val parts = s.split('|')
            return Plan(parts[0].trim(), parts.getOrNull(1)?.trim().orEmpty())
        }
    }
}

/** 从 Notion 拉下来时发现的变化，“查看结果”页里给你看。 */
@Serializable
data class Change(
    val at: Long,
    val key: String,
    val kind: Kind,
    val title: String,
    val what: String,
    val by: String,
)

/** 正在做的一步。只在本机。 */
@Serializable
data class Session(
    val actionKey: String,
    val startedAt: Long,
    val progress: String = "",
    /** 专注锁：做这一步时拦截娱乐应用，到时间自动解除。 */
    val lockUntil: Long = 0,
    /** 最近一次离开总控的时刻（回来时提示记断点）。 */
    val leftAt: Long = 0,
)

/** 提醒、开始、卡住等事件。用来看哪些提醒真的有用。 */
@Serializable
data class Event(val at: Long, val type: String, val actionKey: String = "", val detail: String = "")

@Serializable
data class Work(
    val recs: List<Rec> = emptyList(),
    /** 每类记录上次拉取到的最新 last_edited_time。 */
    val cursors: Map<String, String> = emptyMap(),
    val lastSyncAt: Long = 0,
    val lastSyncOk: Boolean = true,
    val lastSyncMessage: String = "",
    val changes: List<Change> = emptyList(),
    val session: Session? = null,
    val events: List<Event> = emptyList(),
    /** 每个数据库的列结构缓存：库 ID → (列名 → 类型)。 */
    val schemas: Map<String, Map<String, String>> = emptyMap(),
) {
    fun rec(key: String): Rec? = recs.firstOrNull { it.key == key }
    fun byNotion(id: String): Rec? = if (id.isBlank()) null else recs.firstOrNull { it.notionId.replace("-", "") == id.replace("-", "") }
    fun threads(): List<Rec> = recs.filter { it.kind == Kind.THREAD }
    fun actions(): List<Rec> = recs.filter { it.kind == Kind.ACTION }
    fun notes(): List<Rec> = recs.filter { it.kind == Kind.NOTE }
    fun actionsOf(threadKey: String) = actions().filter { it.threadKey == threadKey }
    fun notesOf(threadKey: String) = notes().filter { it.threadKey == threadKey }
    fun threadByCode(code: String): Rec? {
        val c = code.trim().uppercase()
        if (c.isEmpty()) return null
        return threads().firstOrNull { it[F.CODE].uppercase() == c }
    }
    val pendingCount: Int get() = recs.count { it.sync != SyncState.SYNCED }
}
