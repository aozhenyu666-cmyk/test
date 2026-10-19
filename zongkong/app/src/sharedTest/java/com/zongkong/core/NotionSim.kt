package com.zongkong.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Notion API（2022-06-28）的本地模拟，用于测试双向同步：
 *  - 建库、查库（时间戳过滤、按文本/选项过滤、分页）、建页、改页、读页、读写子块
 *  - 和真 Notion 一样校验：列名必须存在、值的类型必须和列类型一致，否则 400 validation_error
 *  - last_edited_time 只精确到分钟（真 Notion 也是这样）
 *  - 可以注入故障：下一次请求超时 / 服务器处理了但返回 502（用来测重复提交）
 */
class NotionSim {
    val server = MockWebServer()

    /** 模拟时钟（毫秒）。默认跟随真实时间，测试里可以手动拨。 */
    var clock: () -> Long = { System.currentTimeMillis() }

    /** 下一次写请求处理完以后返回 502（模拟“写进去了但手机没收到回复”）。 */
    var failAfterWriteNext = 0

    /** 接下来的若干个请求直接返回 503。 */
    var failNext = 0

    val requests = mutableListOf<String>()

    class Db(val id: String, val title: String, val props: MutableMap<String, JsonObject>)
    class Page(
        val id: String,
        val dbId: String,
        val props: MutableMap<String, JsonElement>,
        val created: Long,
        var edited: Long,
        var archived: Boolean = false,
        val children: MutableList<JsonObject> = mutableListOf(),
    )

    val dbs = linkedMapOf<String, Db>()
    val pages = linkedMapOf<String, Page>()
    val rootPage: String = UUID.randomUUID().toString()

    fun start(): NotionSim {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@NotionSim) { handle(request) }
        }
        server.start()
        return this
    }

    fun shutdown() = server.shutdown()

    val baseUrl: String get() = server.url("/v1").toString().trimEnd('/')

    // ---------- 测试辅助 ----------

    fun page(id: String): Page? = pages[norm(id)]

    /** 某页某列的纯文本值（便于断言）。 */
    fun text(pageId: String, prop: String): String {
        val p = page(pageId) ?: return ""
        val name = if (prop == "标题") titleName(p.dbId) else prop
        return plainOf(p.props[name])
    }

    fun pagesIn(dbId: String): List<Page> = pages.values.filter { it.dbId == norm(dbId) && !it.archived }

    private fun titleName(dbId: String) = dbs[dbId]?.props?.entries?.firstOrNull { it.value["type"]?.str() == "title" }?.key ?: "Name"

    private fun plainOf(v: JsonElement?): String = when (v) {
        null, JsonNull -> ""
        is JsonArray -> {
            if (v.firstOrNull()?.jsonObject?.containsKey("plain_text") == true) {
                v.joinToString("") { it.jsonObject["plain_text"]?.str().orEmpty() }
            } else {
                v.joinToString(",") { it.jsonObject["id"]?.str().orEmpty() }
            }
        }
        is JsonObject -> v["name"]?.str() ?: v["start"]?.str()?.let { s -> v["end"]?.str()?.let { "$s|$it" } ?: s } ?: v.toString()
        is JsonPrimitive -> v.contentOrNull.orEmpty()
    }

    // ---------- 处理请求 ----------

    private fun handle(req: RecordedRequest): MockResponse {
        val path = req.path.orEmpty().substringBefore('?').removePrefix("/v1")
        requests += "${req.method} $path"
        if (failNext > 0) {
            failNext--
            return error(503, "service_unavailable", "Notion is unavailable")
        }
        if (req.getHeader("Notion-Version") != "2022-06-28") return error(400, "missing_version", "Notion-Version header failed validation")
        if (req.getHeader("Authorization").isNullOrBlank()) return error(401, "unauthorized", "API token is invalid.")
        val body = req.body.readUtf8().takeIf { it.isNotBlank() }?.let { ZkJson.parseToJsonElement(it).jsonObject }
        val seg = path.trim('/').split('/')
        val resp = try {
            when {
                req.method == "GET" && path == "/users/me" -> ok(buildJsonObject { put("object", "user"); put("type", "bot"); put("name", "总控测试集成") })
                req.method == "POST" && path == "/databases" -> createDb(body!!)
                req.method == "GET" && seg.size == 2 && seg[0] == "databases" -> dbs[norm(seg[1])]?.let { ok(dbJson(it)) } ?: notFound(seg[1])
                req.method == "POST" && seg.size == 3 && seg[0] == "databases" && seg[2] == "query" -> query(norm(seg[1]), body ?: JsonObject(emptyMap()))
                req.method == "POST" && path == "/pages" -> createPage(body!!)
                req.method == "GET" && seg.size == 2 && seg[0] == "pages" -> pages[norm(seg[1])]?.let { ok(pageJson(it)) } ?: notFound(seg[1])
                req.method == "PATCH" && seg.size == 2 && seg[0] == "pages" -> updatePage(norm(seg[1]), body!!)
                req.method == "GET" && seg.size == 3 && seg[0] == "blocks" -> pages[norm(seg[1])]?.let { p ->
                    ok(buildJsonObject { put("object", "list"); put("results", JsonArray(p.children)); put("has_more", false) })
                } ?: notFound(seg[1])
                req.method == "PATCH" && seg.size == 3 && seg[0] == "blocks" -> pages[norm(seg[1])]?.let { p ->
                    body!!["children"]?.jsonArray?.forEach { c -> p.children += withPlain(c.jsonObject) }
                    p.edited = clock()
                    ok(buildJsonObject { put("object", "list"); put("results", JsonArray(p.children)) })
                } ?: notFound(seg[1])
                else -> error(400, "invalid_request_url", "Invalid request URL.")
            }
        } catch (e: Validation) {
            error(400, "validation_error", e.message ?: "validation")
        }
        if (failAfterWriteNext > 0 && req.method != "GET" && !path.endsWith("/query") && resp.status.contains("200")) {
            failAfterWriteNext--
            return error(502, "bad_gateway", "upstream timed out")
        }
        return resp
    }

    private class Validation(msg: String) : Exception(msg)

    private fun createDb(body: JsonObject): MockResponse {
        val parent = body["parent"]?.jsonObject?.get("page_id")?.str() ?: throw Validation("body.parent.page_id should be defined")
        if (norm(parent) != norm(rootPage)) return notFound(parent)
        val props = linkedMapOf<String, JsonObject>()
        body["properties"]!!.jsonObject.forEach { (name, def) ->
            val d = def.jsonObject
            val type = d.keys.first { it in TYPES }
            if (type == "relation") {
                val target = d["relation"]!!.jsonObject["database_id"]?.str() ?: throw Validation("relation.database_id should be defined")
                if (norm(target) !in dbs) throw Validation("Could not find database with ID: $target.")
            }
            props[name] = buildJsonObject {
                put("id", name.hashCode().toString(36)); put("name", name); put("type", type)
                put(type, d[type] ?: JsonObject(emptyMap()))
            }
        }
        if (props.values.count { it["type"]?.str() == "title" } != 1) throw Validation("Title is not provided")
        val title = body["title"]?.jsonArray?.joinToString("") { it.jsonObject["text"]?.jsonObject?.get("content")?.str().orEmpty() }.orEmpty()
        val db = Db(UUID.randomUUID().toString(), title, props)
        dbs[db.id] = db
        return ok(dbJson(db))
    }

    private fun dbJson(db: Db) = buildJsonObject {
        put("object", "database"); put("id", db.id)
        putJsonArray("title") { add(buildJsonObject { put("type", "text"); put("plain_text", db.title) }) }
        put("properties", JsonObject(db.props))
    }

    private fun validateProps(db: Db, props: JsonObject): Map<String, JsonElement> {
        val out = linkedMapOf<String, JsonElement>()
        props.forEach { (name, v) ->
            val def = db.props[name] ?: throw Validation("$name is not a property that exists.")
            val type = def["type"]!!.str()!!
            val obj = v.jsonObject
            val value = obj[type] ?: throw Validation("$name is expected to be $type.")
            out[name] = when (type) {
                "title", "rich_text" -> {
                    val arr = value.jsonArray
                    arr.forEach { t ->
                        val c = t.jsonObject["text"]?.jsonObject?.get("content")?.str().orEmpty()
                        if (c.length > 2000) throw Validation("body.properties.$name.$type[0].text.content.length should be ≤ `2000`")
                    }
                    JsonArray(arr.map { withPlainText(it.jsonObject) })
                }
                "select" -> if (value is JsonNull) JsonNull else {
                    val n = value.jsonObject["name"]?.str() ?: throw Validation("select.name should be defined")
                    if (',' in n) throw Validation("Select option names cannot contain commas")
                    buildJsonObject { put("id", n.hashCode().toString(36)); put("name", n); put("color", "default") }
                }
                "date" -> if (value is JsonNull) JsonNull else buildJsonObject {
                    put("start", millis(value.jsonObject["start"]!!.str()!!))
                    put("end", value.jsonObject["end"]?.str()?.let { JsonPrimitive(millis(it)) } ?: JsonNull)
                    put("time_zone", JsonNull)
                }
                "relation" -> {
                    val target = def["relation"]!!.jsonObject["database_id"]!!.str()!!
                    JsonArray(value.jsonArray.map { r ->
                        val id = norm(r.jsonObject["id"]!!.str()!!)
                        val p = pages[id] ?: throw Validation("Could not find page with ID: $id.")
                        if (p.dbId != norm(target)) throw Validation("Relation page $id is not in database $target.")
                        buildJsonObject { put("id", id) }
                    })
                }
                "url" -> value
                "number" -> value
                else -> value
            }
        }
        return out
    }

    private fun createPage(body: JsonObject): MockResponse {
        val dbId = body["parent"]?.jsonObject?.get("database_id")?.str()?.let(::norm) ?: throw Validation("body.parent.database_id should be defined")
        val db = dbs[dbId] ?: return notFound(dbId)
        val props = validateProps(db, body["properties"]?.jsonObject ?: JsonObject(emptyMap()))
        val now = clock()
        val p = Page(UUID.randomUUID().toString(), dbId, props.toMutableMap(), now, now)
        body["children"]?.jsonArray?.forEach { p.children += withPlain(it.jsonObject) }
        pages[p.id] = p
        return ok(pageJson(p))
    }

    private fun updatePage(id: String, body: JsonObject): MockResponse {
        val p = pages[id] ?: return notFound(id)
        val db = dbs[p.dbId]!!
        body["properties"]?.jsonObject?.let { p.props.putAll(validateProps(db, it)) }
        body["archived"]?.str()?.let { p.archived = it == "true" }
        body["in_trash"]?.str()?.let { p.archived = it == "true" }
        p.edited = clock()
        return ok(pageJson(p))
    }

    private fun query(dbId: String, body: JsonObject): MockResponse {
        val db = dbs[dbId] ?: return notFound(dbId)
        val filter = body["filter"]?.jsonObject
        val size = body["page_size"]?.str()?.toIntOrNull() ?: 100
        val start = body["start_cursor"]?.str()?.toIntOrNull() ?: 0
        val all = pages.values.filter { it.dbId == db.id && !it.archived && (filter == null || matches(it, filter)) }
            .sortedBy { it.edited }
        val slice = all.drop(start).take(size)
        val more = start + size < all.size
        return ok(buildJsonObject {
            put("object", "list")
            put("results", JsonArray(slice.map { pageJson(it) }))
            put("has_more", more)
            put("next_cursor", if (more) JsonPrimitive((start + size).toString()) else JsonNull)
        })
    }

    private fun matches(p: Page, f: JsonObject): Boolean {
        f["and"]?.jsonArray?.let { arr -> return arr.all { matches(p, it.jsonObject) } }
        f["or"]?.jsonArray?.let { arr -> return arr.any { matches(p, it.jsonObject) } }
        f["timestamp"]?.str()?.let { ts ->
            val cond = f[ts]!!.jsonObject
            val t = if (ts == "created_time") round(p.created) else round(p.edited)
            cond["on_or_after"]?.str()?.let { return t >= Instant.parse(toUtc(it)).toEpochMilli() }
            cond["after"]?.str()?.let { return t > Instant.parse(toUtc(it)).toEpochMilli() }
            return true
        }
        val prop = f["property"]?.str() ?: return true
        val v = plainOf(p.props[prop])
        f["rich_text"]?.jsonObject?.get("equals")?.str()?.let { return v == it }
        f["title"]?.jsonObject?.get("equals")?.str()?.let { return plainOf(p.props[titleName(p.dbId)]) == it }
        f["select"]?.jsonObject?.get("equals")?.str()?.let { return v == it }
        return true
    }

    private fun pageJson(p: Page) = buildJsonObject {
        put("object", "page"); put("id", p.id)
        put("created_time", iso(p.created)); put("last_edited_time", iso(p.edited))
        put("archived", p.archived); put("in_trash", p.archived)
        put("url", "https://www.notion.so/" + p.id.replace("-", ""))
        putJsonObject("parent") { put("type", "database_id"); put("database_id", p.dbId) }
        putJsonObject("properties") {
            dbs[p.dbId]!!.props.forEach { (name, def) ->
                val type = def["type"]!!.str()!!
                put(name, buildJsonObject {
                    put("id", def["id"]!!); put("type", type)
                    put(type, p.props[name] ?: empty(type))
                })
            }
        }
    }

    private fun empty(type: String): JsonElement = when (type) {
        "title", "rich_text", "relation", "multi_select" -> JsonArray(emptyList())
        else -> JsonNull
    }

    private fun withPlainText(t: JsonObject): JsonObject {
        val c = t["text"]?.jsonObject?.get("content")?.str().orEmpty()
        return JsonObject(t + ("plain_text" to JsonPrimitive(c)))
    }

    private fun withPlain(block: JsonObject): JsonObject {
        val type = block["type"]?.str() ?: return block
        val inner = block[type]?.jsonObject ?: return block
        val rt = inner["rich_text"]?.jsonArray ?: return block
        val fixed = JsonObject(inner + ("rich_text" to JsonArray(rt.map { withPlainText(it.jsonObject) })))
        return JsonObject(block + (type to fixed) + ("id" to JsonPrimitive(UUID.randomUUID().toString())))
    }

    /** 真 Notion 返回日期时带毫秒：2026-10-10T09:00:00.000+08:00 */
    private fun millis(s: String): String {
        if (!s.contains('T') || s.contains('.')) return s
        val i = s.indexOfAny(charArrayOf('+', 'Z'), 11).let { if (it < 0) s.lastIndexOf('-').takeIf { j -> j > 10 } ?: -1 else it }
        return if (i < 0) "$s.000" else s.substring(0, i) + ".000" + s.substring(i)
    }

    private fun toUtc(s: String): String = runCatching { java.time.OffsetDateTime.parse(s).toInstant().toString() }.getOrDefault(s)

    private fun round(t: Long) = Instant.ofEpochMilli(t).truncatedTo(ChronoUnit.MINUTES).toEpochMilli()
    private fun iso(t: Long) = Instant.ofEpochMilli(round(t)).toString().let { if (it.endsWith(":00Z")) it.dropLast(1) + ".000Z" else it }

    private fun ok(o: JsonObject) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(o.toString())
    private fun error(code: Int, c: String, msg: String) = MockResponse().setResponseCode(code)
        .setBody(buildJsonObject { put("object", "error"); put("status", code); put("code", c); put("message", msg) }.toString())
    private fun notFound(id: String) = error(404, "object_not_found", "Could not find object with ID: $id. Make sure the relevant pages and databases are shared with your integration.")

    companion object {
        val TYPES = setOf("title", "rich_text", "select", "multi_select", "status", "date", "relation", "url", "number", "checkbox", "people", "files", "email", "phone_number")
        fun norm(id: String): String {
            val h = id.replace("-", "").lowercase()
            if (h.length != 32) return id
            return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
        }

        private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    }
}
