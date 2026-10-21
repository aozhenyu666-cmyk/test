package com.moling.app.brief

/** 晨报只拼已有事实；空字段整句省略，最多 300 字，便于一分钟内读完。 */
object Brief {
    fun text(yesterday: String = "", intent: String = "", nextAsk: String = "", prediction: String = ""): String {
        val first = yesterday.trim().split(Regex("[。！？\\n]")).firstOrNull().orEmpty()
        return listOfNotNull(
            first.takeIf { it.isNotBlank() }?.let { "昨天：${it.take(65)}。" },
            if (intent.isBlank()) "今天还没排水道，我先排一个草稿？" else "今天：${intent.trim().take(80)}。",
            nextAsk.trim().takeIf { it.isNotEmpty() }?.let { "第一问：${it.take(65)}。" },
            prediction.trim().takeIf { it.isNotEmpty() }?.let { "预言：${it.take(65)}。" },
        ).joinToString(" ").take(300)
    }
}
