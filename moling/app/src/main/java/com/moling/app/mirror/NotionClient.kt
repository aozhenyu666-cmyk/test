package com.moling.app.mirror

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Notion 返回的错，[message] 写成中文给人看。[code] = HTTP 状态码。 */
class NotionError(val code: Int, message: String) : Exception(message)

/** 一个块：[text] 是它的纯文字；子页面的 [title] 不空。 */
data class NotionBlock(val id: String, val type: String, val text: String, val title: String, val edited: String)

/**
 * Notion 的公开接口（只用页面和块，不碰数据库）。
 * 密钥是他自己建的“内部集成”的密钥；集成只看得见他手动连上的那个页面和它下面的东西。
 * Notion 限制每秒约 3 次请求，连续调用之间歇一下。
 */
class NotionClient(private val token: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    /** 一个页面（或块）下面的块，最多 [max] 个。 */
    suspend fun children(id: String, max: Int = 300): List<NotionBlock> {
        val out = mutableListOf<NotionBlock>()
        var cursor: String? = null
        do {
            val q = "page_size=100" + (cursor?.let { "&start_cursor=$it" } ?: "")
            val o = call("GET", "blocks/$id/children?$q", null)
            o["results"]?.jsonArray?.forEach { out += block(it.jsonObject) }
            cursor = if ((o["has_more"] as? JsonPrimitive)?.contentOrNull == "true") str(o["next_cursor"]) else null
        } while (cursor != null && out.size < max)
        return out
    }

    /** 在页面末尾加块（每次最多 100 个）。 */
    suspend fun append(id: String, blocks: List<JsonObject>) {
        blocks.chunked(100).forEach { part ->
            call("PATCH", "blocks/$id/children", buildJsonObject { put("children", JsonArray(part)) })
        }
    }

    /** 页面最后一次改动的时间（页面里的内容改了也会变）。 */
    suspend fun edited(pageId: String): String = str(call("GET", "pages/$pageId", null)["last_edited_time"]).orEmpty()

    suspend fun delete(id: String) {
        call("DELETE", "blocks/$id", null)
    }

    /** 在 [parent] 页面下建一个子页面，返回新页面的 id。 */
    suspend fun createPage(parent: String, title: String, blocks: List<JsonObject> = emptyList()): String {
        val o = call(
            "POST", "pages",
            buildJsonObject {
                putJsonObject("parent") { put("page_id", parent) }
                putJsonObject("properties") {
                    putJsonObject("title") {
                        putJsonArray("title") { addJsonObject { putJsonObject("text") { put("content", title) } } }
                    }
                }
                if (blocks.isNotEmpty()) put("children", JsonArray(blocks.take(100)))
            },
        )
        return str(o["id"]) ?: throw NotionError(0, "Notion 没返回新页面的编号")
    }

    private suspend fun call(method: String, path: String, body: JsonObject?): JsonObject = withContext(Dispatchers.IO) {
        delay(GAP_MS)
        val b = Request.Builder()
            .url("$BASE/$path")
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Notion-Version", VERSION)
        val req = when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete()
            else -> b.method(method, (body ?: buildJsonObject {}).toString().toRequestBody(JSON_TYPE))
        }.build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code !in 200..299) throw NotionError(resp.code, human(resp.code, text))
            runCatching { json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        }
    }

    companion object {
        private const val BASE = "https://api.notion.com/v1"
        private const val VERSION = "2022-06-28"
        private const val GAP_MS = 350L
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.contentOrNull

        fun block(o: JsonObject): NotionBlock {
            val type = str(o["type"]).orEmpty()
            val body = o[type] as? JsonObject
            val text = (body?.get("rich_text") as? JsonArray)?.joinToString("") { str(it.jsonObject["plain_text"]).orEmpty() }.orEmpty()
            val title = if (type == "child_page") str(body?.get("title")).orEmpty() else ""
            val prefix = when (type) {
                "heading_1", "heading_2", "heading_3" -> "## "
                "bulleted_list_item", "numbered_list_item" -> "- "
                "to_do" -> if (str(body?.get("checked")) == "true") "[x] " else "[ ] "
                "quote" -> "> "
                else -> ""
            }
            return NotionBlock(str(o["id"]).orEmpty(), type, if (text.isBlank()) "" else prefix + text, title, str(o["last_edited_time"]).orEmpty())
        }

        private fun rich(text: String) = buildJsonArray {
            // Notion 一段文字最多 2000 字
            text.chunked(1900).forEach { part -> addJsonObject { put("type", "text"); putJsonObject("text") { put("content", part) } } }
        }

        private fun simple(type: String, text: String) = buildJsonObject {
            put("object", "block")
            put("type", type)
            putJsonObject(type) { put("rich_text", rich(text)) }
        }

        fun heading(text: String) = simple("heading_2", text)
        fun paragraph(text: String) = simple("paragraph", text)
        fun bullet(text: String) = simple("bulleted_list_item", text)

        private fun human(code: Int, body: String): String {
            val detail = runCatching { str(json.parseToJsonElement(body).jsonObject["message"]) }.getOrNull().orEmpty().take(160)
            val hint = when (code) {
                401 -> "Notion 密钥不对或失效了"
                403 -> "这个集成没有权限（页面右上角 … → 连接 里把它加上）"
                404 -> "找不到页面：链接不对，或页面没连上这个集成"
                409 -> "Notion 那边冲突了，等会儿再试"
                429 -> "太频繁了，Notion 让等一会儿"
                in 500..599 -> "Notion 那边出错了"
                else -> "Notion 返回 $code"
            }
            return if (detail.isBlank()) hint else "$hint（$detail）"
        }
    }
}
