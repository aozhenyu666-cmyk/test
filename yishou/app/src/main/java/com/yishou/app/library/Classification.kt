package com.yishou.app.library

data class Topic(val id: Long, val name: String)

/** 分类只管收纳，不冒充题目的标准答案或难度。 */
object Classification {
    val defaults = listOf(Topic(1, "行测"), Topic(2, "申论"), Topic(3, "海龟汤"), Topic(4, "文章"), Topic(5, "其他"))
    fun choose(text: String, kind: String, tabs: List<Topic>, selected: Long?): Long = selected ?: category(text, kind, tabs)
    fun category(text: String, kind: String, tabs: List<Topic>): Long {
        tabs.filter { it.id > 5 && it.name.length >= 2 }.sortedByDescending { it.name.length }
            .firstOrNull { text.contains(it.name, ignoreCase = true) }?.let { return it.id }
        if (listOf("海龟汤", "汤面", "汤底").any(text::contains)) return 3
        if (listOf("申论", "给定资料", "概括主要", "提出对策").any(text::contains)) return 2
        if (kind == "card" || listOf("行测", "片段阅读", "逻辑判断", "资料分析", "数量关系").any(text::contains)) return 1
        return if (kind == "material") 4 else 5
    }
}
