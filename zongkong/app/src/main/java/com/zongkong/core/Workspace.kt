package com.zongkong.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 在 Notion 里搭建四部门的库、写随手记、写验收日志。 */
object Workspace {
    const val INBOX_TITLE = "信息收集库"
    const val THINK_TITLE = "谋划库"
    const val LOG_TITLE = "总控日志"

    val CAPTURE_TAGS = listOf("信息", "问题", "灵感", "待办")

    data class Created(val inboxDb: String, val thinkDb: String, val logDb: String)

    private fun select(vararg options: Pair<String, String>) = buildJsonObject {
        putJsonObject("select") {
            putJsonArray("options") {
                options.forEach { (name, color) -> add(buildJsonObject { put("name", name); put("color", color) }) }
            }
        }
    }

    private val empty = JsonObject(emptyMap())
    private fun typed(type: String) = buildJsonObject { put(type, empty) }

    val inboxSchema = buildJsonObject {
        put("标题", typed("title"))
        put("类型", select("信息" to "blue", "问题" to "red", "灵感" to "yellow", "待办" to "green"))
        put("状态", select("新" to "gray", "转谋划" to "purple", "转统筹" to "orange", "已处理" to "green", "归档" to "default"))
        put("为什么值得留", typed("rich_text"))
        put("来源", typed("url"))
        put("日期", typed("date"))
    }

    val thinkSchema = buildJsonObject {
        put("问题", typed("title"))
        put(
            "方法卡",
            buildJsonObject {
                putJsonObject("select") {
                    putJsonArray("options") { Defaults.methods.forEach { add(buildJsonObject { put("name", it.name) }) } }
                }
            },
        )
        put("结论", typed("rich_text"))
        put("下一步", typed("rich_text"))
        put("置信度", buildJsonObject { putJsonObject("number") { put("format", "percent") } })
        put("状态", select("进行中" to "yellow", "已结论" to "blue", "已应验" to "green", "被推翻" to "red"))
        put("日期", typed("date"))
    }

    val logSchema = buildJsonObject {
        put("标题", typed("title"))
        put(
            "部门",
            buildJsonObject {
                putJsonObject("select") {
                    putJsonArray("options") { Dept.entries.forEach { add(buildJsonObject { put("name", it.label) }) } }
                }
            },
        )
        put("结果", select("通过" to "green", "迟交通过" to "yellow", "降级通过" to "orange", "未通过" to "red", "报到" to "gray", "紧急放行" to "pink"))
        put("得分", buildJsonObject { putJsonObject("number") { put("format", "number") } })
        put("日期", typed("date"))
    }

    /** 在父页面下建好三个库。父页面必须先在 Notion 里连接到你的集成。 */
    suspend fun setup(notion: NotionApi, parentPage: String): ApiResult<Created> {
        val inbox = when (val r = notion.createDatabase(parentPage, INBOX_TITLE, inboxSchema)) {
            is ApiResult.Err -> return r
            is ApiResult.Ok -> r.value
        }
        val think = when (val r = notion.createDatabase(parentPage, THINK_TITLE, thinkSchema)) {
            is ApiResult.Err -> return r
            is ApiResult.Ok -> r.value
        }
        val log = when (val r = notion.createDatabase(parentPage, LOG_TITLE, logSchema)) {
            is ApiResult.Err -> return r
            is ApiResult.Ok -> r.value
        }
        return ApiResult.Ok(Created(inbox, think, log))
    }

    /**
     * 按数据库的实际结构填属性：库里有这个名字、类型对得上才写，其余跳过。
     * 这样你自己建的库也能用，只要标题之外的列名对得上就会填进去。
     */
    fun properties(schema: NotionSchema, title: String, fields: Map<String, Any?>): JsonObject = buildJsonObject {
        put(schema.titleProp, buildJsonObject { put("title", NotionClient.richText(title.take(200))) })
        fields.forEach { (name, value) ->
            if (value == null || name == schema.titleProp) return@forEach
            when (schema.props[name]) {
                "select" -> put(name, buildJsonObject { putJsonObject("select") { put("name", value.toString()) } })
                "rich_text" -> put(name, buildJsonObject { put("rich_text", NotionClient.richText(value.toString())) })
                "date" -> put(name, buildJsonObject { putJsonObject("date") { put("start", value.toString()) } })
                "number" -> (value as? Number)?.let { n -> put(name, buildJsonObject { put("number", n.toDouble()) }) }
                "url" -> put(name, buildJsonObject { put("url", value.toString()) })
                else -> {}
            }
        }
    }

    /** 随手记：写进信息收集库。库里有“类型”列就填类型，没有就把类型放进标题。 */
    suspend fun capture(notion: NotionApi, inboxDb: String, text: String, tag: String, date: String): ApiResult<String> {
        val schema = when (val r = notion.schema(inboxDb)) {
            is ApiResult.Err -> return r
            is ApiResult.Ok -> r.value
        }
        val firstLine = text.trim().lineSequence().first().trim()
        val hasType = schema.props["类型"] == "select"
        val title = if (hasType || tag.isBlank()) firstLine else "【$tag】$firstLine"
        val rest = text.trim().lines().drop(1).joinToString("\n").trim()
        val props = properties(
            schema, title,
            mapOf("类型" to tag.ifBlank { null }, "状态" to "新", "日期" to date),
        )
        return notion.createPage(inboxDb, props, if (rest.isBlank()) buildJsonArray { } else NotionClient.paragraphs(rest))
    }

    /** 一条验收结果写进总控日志。 */
    suspend fun log(notion: NotionApi, logDb: String, entry: LogEntry, date: String): ApiResult<String> {
        val schema = when (val r = notion.schema(logDb)) {
            is ApiResult.Err -> return r
            is ApiResult.Ok -> r.value
        }
        val props = properties(
            schema, entry.title,
            mapOf("部门" to entry.dept.label, "结果" to entry.result, "得分" to entry.score, "日期" to date),
        )
        return notion.createPage(logDb, props, NotionClient.paragraphs(entry.text))
    }
}

/** 谋划单写进谋划库：标题取“问题”那一行。 */
object ThinkSheet {
    fun question(text: String): String {
        val line = text.lines().map { it.trim() }.firstOrNull { it.startsWith("问题") }
            ?.substringAfter('：', "")?.substringAfter(':')?.trim()
        return line?.takeIf { it.isNotBlank() } ?: text.trim().lineSequence().first().take(60)
    }

    fun field(text: String, label: String): String? = text.lines().map { it.trim() }
        .firstOrNull { it.startsWith(label) }
        ?.let { l -> l.substringAfter('：', l.substringAfter(':', "")).trim() }
        ?.takeIf { it.isNotBlank() }

    /** “置信度（0–100%）：70%” → 0.7 */
    fun confidence(text: String): Double? {
        val v = field(text, "置信度") ?: return null
        val n = Regex("(\\d{1,3})").find(v)?.value?.toIntOrNull() ?: return null
        return (n.coerceIn(0, 100)) / 100.0
    }

    suspend fun write(notion: NotionApi, thinkDb: String, entry: LogEntry, date: String, method: String): ApiResult<String> {
        val schema = when (val r = notion.schema(thinkDb)) {
            is ApiResult.Err -> return r
            is ApiResult.Ok -> r.value
        }
        val props = Workspace.properties(
            schema, question(entry.text),
            mapOf(
                "方法卡" to method,
                "结论" to field(entry.text, "修正后的结论"),
                "下一步" to field(entry.text, "24 小时内的下一步"),
                "置信度" to confidence(entry.text),
                "状态" to "已结论",
                "日期" to date,
            ),
        )
        return notion.createPage(thinkDb, props, NotionClient.paragraphs(entry.text))
    }
}
