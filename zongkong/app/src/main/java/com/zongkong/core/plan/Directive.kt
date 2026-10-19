package com.zongkong.core.plan

import kotlinx.serialization.Serializable

/**
 * 外接的谋划单：用户在 Gemini / GPT / Notion / 别的对话里聊出来的报告、计划、问题清单。
 *
 * 总控不自己做专业谋划，也不替用户重想一遍；它只做三件事：
 *  1. 把外部文档拆成一条条能检查的条目（任务 / 风险 / 待决 / 关键事实）；
 *  2. 把条目挂到事项、行动、约定上，盯落实；
 *  3. 定期回头对账：哪些说要做、现在到哪了、卡在哪。
 */
@Serializable
data class Directive(
    val id: String,
    val title: String,
    /** 从哪儿来的：Gemini / GPT / Notion / 粘贴。 */
    val source: String = "粘贴",
    val at: Long = 0,
    /** 原文（截断保存，够总控回看就行）。 */
    val raw: String = "",
    /** 一句话结论：这份文档到底要用户做到什么。 */
    val summary: String = "",
    /** active / done / archived。 */
    val status: String = STATUS_ACTIVE,
    val items: List<DirectiveItem> = emptyList(),
) {
    val open: List<DirectiveItem> get() = items.filterNot { it.done }

    val doneCount: Int get() = items.count { it.done }

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_DONE = "done"
        const val STATUS_ARCHIVED = "archived"
    }
}

/** 谋划单里一条能监督的条目。 */
@Serializable
data class DirectiveItem(
    val id: String,
    /** 一句话，动词开头，能检查。 */
    val text: String,
    /** task 要做 / risk 风险 / question 待决 / fact 关键事实。 */
    val kind: String = KIND_TASK,
    /** 时间说法（“这周”“10 月 20 日前”），不排进日程就空着。 */
    val due: String = "",
    val done: Boolean = false,
    /** 挂到哪件事（事项编号 ZK-XXXX）。 */
    val threadKey: String = "",
    /** 挂到哪一步行动（行动键）。 */
    val actionKey: String = "",
) {
    companion object {
        const val KIND_TASK = "task"
        const val KIND_RISK = "risk"
        const val KIND_QUESTION = "question"
        const val KIND_FACT = "fact"

        val KINDS = listOf(KIND_TASK, KIND_RISK, KIND_QUESTION, KIND_FACT)

        fun kindLabel(kind: String): String = when (kind) {
            KIND_RISK -> "风险"
            KIND_QUESTION -> "待决"
            KIND_FACT -> "事实"
            else -> "要做"
        }
    }
}

/** 写进系统提示的简报：总控每次开口都带着它，才知道外面交进来了什么、还欠什么。 */
object Directives {
    const val MAX_ITEMS = 8

    /** 所有在办的谋划单（最多 [max] 份），每份列出未完成条目。 */
    fun brief(list: List<Directive>, max: Int = 4, maxItems: Int = MAX_ITEMS): String {
        val active = list.filter { it.status == Directive.STATUS_ACTIVE }
        if (active.isEmpty()) return ""
        val sb = StringBuilder()
        active.take(max).forEach { d ->
            sb.append("### ").append(d.title)
            if (d.source.isNotBlank()) sb.append("（来自 ").append(d.source).append("）")
            if (d.summary.isNotBlank()) sb.append("：").append(d.summary)
            sb.append('\n')
            val open = d.open
            if (open.isEmpty()) {
                sb.append("- 条目都做完了，可以问用户要不要收掉这单。\n")
            } else {
                open.take(maxItems).forEach { i ->
                    sb.append("- [").append(DirectiveItem.kindLabel(i.kind)).append("] ").append(i.text)
                    if (i.due.isNotBlank()) sb.append("（").append(i.due).append("）")
                    if (i.threadKey.isNotBlank()) sb.append(" · 已挂事项 ").append(i.threadKey)
                    if (i.actionKey.isNotBlank()) sb.append(" · 已挂行动 ").append(i.actionKey)
                    sb.append('\n')
                }
                if (open.size > maxItems) sb.append("- 还有 ").append(open.size - maxItems).append(" 条没列出来\n")
            }
        }
        return sb.toString()
    }

    /** 给“今天该盯什么”的一句话统计。 */
    fun tally(list: List<Directive>): String {
        val active = list.filter { it.status == Directive.STATUS_ACTIVE }
        if (active.isEmpty()) return ""
        val open = active.sumOf { it.open.size }
        val done = active.sumOf { it.doneCount }
        return "${active.size} 份外接谋划单，${open} 条没落实（已落 ${done} 条）"
    }

    fun find(list: List<Directive>, keyword: String): Directive? {
        val k = keyword.trim()
        if (k.isBlank()) return null
        return list.firstOrNull { it.id == k }
            ?: list.firstOrNull { it.title.contains(k, ignoreCase = true) }
    }
}
