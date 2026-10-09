package com.zongkong.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

data class NotionPage(val id: String, val title: String, val url: String, val createdTime: String)

/** 数据库结构：标题属性叫什么，其余属性名 → 类型。 */
data class NotionSchema(val titleProp: String, val props: Map<String, String>, val selectOptions: Map<String, List<String>>)

/** Notion 接口。总控只用到下面几个动作。 */
interface NotionApi {
    suspend fun me(): ApiResult<String>
    suspend fun createdSince(dbId: String, sinceIso: String): ApiResult<List<NotionPage>>
    suspend fun pageText(pageId: String, maxChars: Int): ApiResult<String>
    suspend fun schema(dbId: String): ApiResult<NotionSchema>
    suspend fun createDatabase(parentPage: String, title: String, properties: JsonObject): ApiResult<String>
    suspend fun createPage(dbId: String, properties: JsonObject, children: JsonArray): ApiResult<String>
}

/**
 * Notion 官方 API。用 2022-06-28 版本：数据库的 query/create 接口在这个版本下最稳定，
 * 新版本把数据库拆成了 data source，老版本仍然兼容单数据源的数据库。
 */
class NotionClient(
    private val token: () -> String,
    private val http: OkHttpClient = Net.client(30),
    private val base: String = "https://api.notion.com/v1",
) : NotionApi {

    private fun headers() = mapOf(
        "Authorization" to "Bearer ${token().trim()}",
        "Notion-Version" to VERSION,
    )

    private suspend fun call(request: okhttp3.Request): ApiResult<JsonObject> {
        if (token().isBlank()) return ApiResult.Err("还没有填 Notion 密钥（设置 → Notion）")
        val raw = when (val r = Net.send(http, request)) {
            is ApiResult.Err -> return ApiResult.Err("Notion：${r.message}")
            is ApiResult.Ok -> r.value
        }
        if (raw.code !in 200..299) return ApiResult.Err(httpMessage(raw.code, Net.errorMessage(raw.body)))
        return Net.parseObject(raw.body)?.let { ApiResult.Ok(it) } ?: ApiResult.Err("Notion 返回的内容看不懂：${raw.body.take(120)}")
    }

    override suspend fun me(): ApiResult<String> =
        call(Net.get("$base/users/me", headers())).map { it.str("name") ?: "Notion 集成" }

    override suspend fun createdSince(dbId: String, sinceIso: String): ApiResult<List<NotionPage>> {
        val id = NotionIds.parse(dbId) ?: return ApiResult.Err("Notion 数据库 ID 不对：$dbId")
        val body = buildJsonObject {
            putJsonObject("filter") {
                put("timestamp", "created_time")
                putJsonObject("created_time") { put("on_or_after", sinceIso) }
            }
            putJsonArray("sorts") { add(buildJsonObject { put("timestamp", "created_time"); put("direction", "ascending") }) }
            put("page_size", 100)
        }
        return call(Net.post("$base/databases/$id/query", body, headers())).map { obj ->
            obj["results"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.let(::toPage) }
        }
    }

    override suspend fun pageText(pageId: String, maxChars: Int): ApiResult<String> {
        val id = NotionIds.parse(pageId) ?: return ApiResult.Err("Notion 页面 ID 不对：$pageId")
        return call(Net.get("$base/blocks/$id/children?page_size=100", headers())).map { obj ->
            blocksText(obj["results"]?.jsonArray.orEmpty()).take(maxChars)
        }
    }

    override suspend fun schema(dbId: String): ApiResult<NotionSchema> {
        val id = NotionIds.parse(dbId) ?: return ApiResult.Err("Notion 数据库 ID 不对：$dbId")
        return call(Net.get("$base/databases/$id", headers())).map(::toSchema)
    }

    override suspend fun createDatabase(parentPage: String, title: String, properties: JsonObject): ApiResult<String> {
        val id = NotionIds.parse(parentPage) ?: return ApiResult.Err("父页面链接不对：$parentPage")
        val body = buildJsonObject {
            putJsonObject("parent") { put("type", "page_id"); put("page_id", id) }
            put("title", richText(title))
            put("properties", properties)
        }
        return call(Net.post("$base/databases", body, headers())).map { it.str("id").orEmpty() }
    }

    override suspend fun createPage(dbId: String, properties: JsonObject, children: JsonArray): ApiResult<String> {
        val id = NotionIds.parse(dbId) ?: return ApiResult.Err("Notion 数据库 ID 不对：$dbId")
        val body = buildJsonObject {
            putJsonObject("parent") { put("database_id", id) }
            put("properties", properties)
            if (children.isNotEmpty()) put("children", children)
        }
        return call(Net.post("$base/pages", body, headers())).map { it.str("id").orEmpty() }
    }

    companion object {
        const val VERSION = "2022-06-28"

        fun httpMessage(code: Int, detail: String): String {
            val hint = when (code) {
                400 -> "请求格式不对"
                401 -> "密钥不对或已失效"
                403 -> "这个集成没有权限"
                404 -> "找不到。多半是没有把页面或数据库分享给集成：在 Notion 里打开它 → 右上角 ⋯ → 连接 → 选你的集成"
                409 -> "冲突，稍后再试"
                429 -> "请求太频繁"
                in 500..599 -> "Notion 服务出错"
                else -> "请求失败"
            }
            return if (detail.isBlank()) "Notion 返回 $code：$hint" else "Notion 返回 $code：$hint（$detail）"
        }

        fun toPage(obj: JsonObject): NotionPage {
            val props = obj["properties"] as? JsonObject
            val title = props?.values?.firstOrNull { (it as? JsonObject)?.str("type") == "title" }
                ?.jsonObject?.get("title")?.jsonArray?.let(::plain).orEmpty()
            return NotionPage(
                id = obj.str("id").orEmpty(),
                title = title.ifBlank { "（无标题）" },
                url = obj.str("url").orEmpty(),
                createdTime = obj.str("created_time").orEmpty(),
            )
        }

        fun toSchema(obj: JsonObject): NotionSchema {
            val props = obj["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val types = props.mapValues { (_, v) -> (v as? JsonObject)?.str("type").orEmpty() }
            val options = props.mapNotNull { (k, v) ->
                val o = v as? JsonObject ?: return@mapNotNull null
                if (o.str("type") != "select") return@mapNotNull null
                val names = (o["select"] as? JsonObject)?.get("options")?.jsonArray.orEmpty()
                    .mapNotNull { (it as? JsonObject)?.str("name") }
                k to names
            }.toMap()
            return NotionSchema(types.entries.firstOrNull { it.value == "title" }?.key ?: "Name", types, options)
        }

        /** 把块列表里的文字拼起来，每块一行。只取常见的文字块。 */
        fun blocksText(blocks: List<JsonElement>): String = blocks.mapNotNull { el ->
            val b = el as? JsonObject ?: return@mapNotNull null
            val type = b.str("type") ?: return@mapNotNull null
            val content = b[type] as? JsonObject ?: return@mapNotNull null
            val text = (content["rich_text"] as? JsonArray)?.let(::plain) ?: return@mapNotNull null
            if (text.isBlank()) return@mapNotNull null
            when (type) {
                "heading_1", "heading_2", "heading_3" -> "【$text】"
                "bulleted_list_item", "numbered_list_item" -> "· $text"
                "to_do" -> (if ((content["checked"]?.jsonPrimitive?.contentOrNull) == "true") "☑ " else "☐ ") + text
                else -> text
            }
        }.joinToString("\n")

        fun plain(arr: JsonArray): String = arr.joinToString("") { (it as? JsonObject)?.str("plain_text").orEmpty() }

        /** Notion 单个文字对象最多 2000 字，长文切开。 */
        fun richText(text: String): JsonArray = buildJsonArray {
            text.chunked(1900).ifEmpty { listOf("") }.forEach { part ->
                add(buildJsonObject { put("type", "text"); putJsonObject("text") { put("content", part) } })
            }
        }

        /** 长文变成若干段落块（每块一段，最多 90 块）。 */
        fun paragraphs(text: String): JsonArray = buildJsonArray {
            text.split("\n").filter { it.isNotBlank() }.take(90).forEach { line ->
                add(
                    buildJsonObject {
                        put("object", "block")
                        put("type", "paragraph")
                        putJsonObject("paragraph") { put("rich_text", richText(line)) }
                    },
                )
            }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

/** 从 Notion 链接或 ID 里取出标准格式的 ID（带横线的 UUID）。 */
object NotionIds {
    private val HEX32 = Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{32}(?![0-9a-fA-F])")
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun parse(input: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        // 链接里 ?v= 后面是视图 ID，# 后面是块 ID，都不是我们要的
        val path = t.substringBefore('?').substringBefore('#')
        UUID.findAll(path).lastOrNull()?.let { return it.value.lowercase() }
        val hex = HEX32.findAll(path).lastOrNull()?.value ?: return null
        val h = hex.lowercase()
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}
