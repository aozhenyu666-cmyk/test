package com.zongkong.core.work

import com.zongkong.core.NotionClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** 从 Notion 拿到的一页。关联字段的值是 Notion 页面 ID。 */
data class RemotePage(
    val id: String,
    val url: String,
    val lastEdited: String,
    val archived: Boolean,
    val fields: Map<String, String>,
)

/** 本机字段 ↔ Notion 属性。读的时候宽容（类型对不上也尽量读出来），写的时候按库的实际结构来。 */
object NotionMap {

    // ---------- 建库 ----------

    private fun select(vararg names: String) = buildJsonObject {
        putJsonObject("select") { putJsonArray("options") { names.forEach { n -> add(buildJsonObject { put("name", n) }) } } }
    }

    private fun typed(type: String) = buildJsonObject { put(type, JsonObject(emptyMap())) }

    /** 建库用的属性定义。关联列指向事项库。 */
    fun dbProperties(kind: Kind, threadsDb: String): JsonObject = buildJsonObject {
        F.SCHEMA.getValue(kind).forEach { (name, type) ->
            when {
                name == F.STATUS && kind == Kind.THREAD -> put(name, select(*ThreadStatus.entries.map { it.label }.toTypedArray()))
                name == F.STATUS && kind == Kind.ACTION -> put(name, select(*ActionStatus.entries.map { it.label }.toTypedArray()))
                name == F.TYPE -> put(name, select(*NoteType.entries.map { it.label }.toTypedArray()))
                name == F.STUCK -> put(name, select(*StuckReason.entries.map { it.label }.toTypedArray()))
                name == F.PRIORITY -> put(name, select("高", "中", "低"))
                name == F.SOURCE -> put(name, select("总控", "GPT", "手动"))
                type == "relation" -> put(name, buildJsonObject {
                    putJsonObject("relation") {
                        put("database_id", threadsDb)
                        putJsonObject("single_property") {}
                    }
                })
                else -> put(name, typed(type))
            }
        }
    }

    fun dbTitle(kind: Kind) = when (kind) {
        Kind.THREAD -> "总控·事项"
        Kind.ACTION -> "总控·行动"
        Kind.NOTE -> "总控·记录"
    }

    // ---------- 读 ----------

    fun fromPage(page: JsonObject): RemotePage {
        val props = page["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val fields = mutableMapOf<String, String>()
        props.forEach { (name, v) ->
            val o = v as? JsonObject ?: return@forEach
            val type = o.str("type") ?: return@forEach
            // 标题列不管叫什么，都当作“标题”
            val key = if (type == "title") F.TITLE else name
            readValue(type, o[type])?.let { fields[key] = it }
        }
        return RemotePage(
            id = page.str("id").orEmpty(),
            url = page.str("url").orEmpty(),
            lastEdited = page.str("last_edited_time").orEmpty(),
            archived = page.str("archived") == "true" || page.str("in_trash") == "true",
            fields = fields,
        )
    }

    private fun readValue(type: String, v: JsonElement?): String? {
        if (v == null || v is JsonNull) return ""
        return when (type) {
            "title", "rich_text" -> (v as? JsonArray)?.let(NotionClient::plain)?.trim()
            "select", "status" -> (v as? JsonObject)?.str("name").orEmpty()
            "multi_select" -> (v as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("name") }?.joinToString("、")
            "date" -> (v as? JsonObject)?.let { d ->
                val s = normDate(d.str("start").orEmpty())
                val e = normDate(d.str("end").orEmpty())
                if (e.isBlank()) s else "$s|$e"
            }
            "url", "email", "phone_number" -> (v as? JsonPrimitive)?.contentOrNull.orEmpty()
            "number" -> (v as? JsonPrimitive)?.contentOrNull.orEmpty()
            "checkbox" -> (v as? JsonPrimitive)?.contentOrNull.orEmpty()
            "relation" -> (v as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("id") }.orEmpty()
            else -> null
        }
    }

    // ---------- 写 ----------

    /**
     * 把字段写成 Notion 属性。只写库里实际存在、类型能对上的列；对不上的列返回在 skipped 里。
     * 关联列：本机存的是事项 key，用 [threadNotionId] 换成 Notion ID；事项还没同步时返回在 deferred 里，下一轮再写。
     */
    data class Props(val json: JsonObject, val written: Set<String>, val skipped: Set<String>, val deferred: Set<String>)

    fun toProps(
        fields: Map<String, String>,
        only: Set<String>,
        dbSchema: Map<String, String>,
        threadNotionId: (String) -> String?,
    ): Props {
        val written = mutableSetOf<String>()
        val skipped = mutableSetOf<String>()
        val deferred = mutableSetOf<String>()
        val titleName = dbSchema.entries.firstOrNull { it.value == "title" }?.key
        val json = buildJsonObject {
            for (name in only) {
                val value = fields[name].orEmpty()
                val prop = if (name == F.TITLE) titleName else name
                val type = prop?.let { dbSchema[it] }
                if (prop == null || type == null) {
                    skipped += name
                    continue
                }
                val el: JsonElement? = when (type) {
                    "title" -> buildJsonObject { put("title", NotionClient.richText(value.take(1900))) }
                    "rich_text" -> buildJsonObject { put("rich_text", if (value.isEmpty()) JsonArray(emptyList()) else NotionClient.richText(value)) }
                    "select" -> buildJsonObject { put("select", if (value.isBlank()) JsonNull else buildJsonObject { put("name", cleanOption(value)) }) }
                    "status" -> if (value.isBlank()) null else buildJsonObject { putJsonObject("status") { put("name", value) } }
                    "multi_select" -> buildJsonObject {
                        putJsonArray("multi_select") {
                            value.split('、', ',', '，').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(buildJsonObject { put("name", cleanOption(it)) }) }
                        }
                    }
                    "date" -> buildJsonObject {
                        val p = Plan.parse(value)
                        put("date", if (p == null) JsonNull else buildJsonObject {
                            put("start", p.start)
                            if (p.end.isNotBlank()) put("end", p.end)
                        })
                    }
                    "url" -> buildJsonObject { put("url", if (value.isBlank()) JsonNull else JsonPrimitive(value)) }
                    "number" -> buildJsonObject { put("number", value.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonNull) }
                    "relation" -> {
                        if (value.isBlank()) {
                            buildJsonObject { put("relation", JsonArray(emptyList())) }
                        } else {
                            val nid = threadNotionId(value)
                            if (nid == null) {
                                deferred += name
                                null
                            } else {
                                buildJsonObject { putJsonArray("relation") { add(buildJsonObject { put("id", nid) }) } }
                            }
                        }
                    }
                    else -> null
                }
                if (el == null) {
                    if (name !in deferred) skipped += name
                } else {
                    put(prop, el)
                    written += name
                }
            }
        }
        return Props(json, written, skipped, deferred)
    }

    /** Notion 的选项名不能有英文逗号。 */
    private fun cleanOption(s: String) = s.replace(',', '，').trim().take(100)

    /** 统一日期格式，避免 “09:00:00.000+08:00” 和 “09:00:00+08:00” 被当成两个值。 */
    fun normDate(s: String): String {
        val t = s.trim()
        if (t.isEmpty() || !t.contains('T')) return t
        return try {
            OffsetDateTime.parse(t).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (e: Exception) {
            t
        }
    }

    /** 两个字段值是不是同一个意思（日期比时刻、关联比 ID、文本去掉首尾空白）。 */
    fun same(field: String, a: String, b: String): Boolean {
        if (a.trim() == b.trim()) return true
        if (field == F.PLAN || field == F.DATE) {
            val pa = Plan.parse(a)
            val pb = Plan.parse(b)
            if (pa == null || pb == null) return pa == pb
            return instantEq(pa.start, pb.start) && instantEq(pa.end, pb.end)
        }
        if (field == F.THREAD) return a.replace("-", "") == b.replace("-", "")
        return false
    }

    private fun instantEq(a: String, b: String): Boolean {
        if (a == b) return true
        if (!a.contains('T') || !b.contains('T')) return false
        return try {
            OffsetDateTime.parse(a).toInstant() == OffsetDateTime.parse(b).toInstant()
        } catch (e: Exception) {
            false
        }
    }

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun schemaOf(db: JsonObject): Map<String, String> {
        val props = db["properties"] as? JsonObject ?: return emptyMap()
        return props.mapNotNull { (k, v) -> (v as? JsonObject)?.str("type")?.let { k to it } }.toMap()
    }
}
