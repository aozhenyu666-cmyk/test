package com.zongkong.core.work

import com.zongkong.core.ApiResult
import com.zongkong.core.NotionApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/** 在 Notion 父页面下建“事项、行动、记录”三个库。事项库先建，另外两个库的关联列指向它。 */
object WorkSetup {
    suspend fun create(api: NotionApi, parentPage: String): ApiResult<Dbs> {
        val thread = when (val r = api.createDatabase(parentPage, NotionMap.dbTitle(Kind.THREAD), NotionMap.dbProperties(Kind.THREAD, ""))) {
            is ApiResult.Err -> return r
            is ApiResult.Ok -> r.value
        }
        val action = when (val r = api.createDatabase(parentPage, NotionMap.dbTitle(Kind.ACTION), NotionMap.dbProperties(Kind.ACTION, thread))) {
            is ApiResult.Err -> return r
            is ApiResult.Ok -> r.value
        }
        val note = when (val r = api.createDatabase(parentPage, NotionMap.dbTitle(Kind.NOTE), NotionMap.dbProperties(Kind.NOTE, thread))) {
            is ApiResult.Err -> return r
            is ApiResult.Ok -> r.value
        }
        return ApiResult.Ok(Dbs(thread, action, note))
    }

    /** 检查一个库缺哪些列（你自己建的库或改过列名时用）。 */
    fun missingColumns(kind: Kind, schema: Map<String, String>): List<String> {
        val need = F.SCHEMA.getValue(kind)
        val hasTitle = schema.values.any { it == "title" }
        return need.filter { (name, type) ->
            if (type == "title") !hasTitle else {
                val t = schema[name]
                t == null || !(t == type || (type == "select" && t == "status"))
            }
        }.keys.toList()
    }
}

/**
 * 连接自检：用你真实的 Notion 走一遍“写 → 读 → 改 → 增量查到 → 关联 → 清理”。
 * 每一步如实报告结果。测试页最后归档，不留垃圾。
 */
object SelfTest {
    data class Step(val name: String, val ok: Boolean, val detail: String)

    suspend fun run(api: NotionApi, dbs: Dbs, now: Long): List<Step> {
        val steps = mutableListOf<Step>()
        fun add(name: String, ok: Boolean, detail: String): Boolean {
            steps += Step(name, ok, detail); return ok
        }
        if (!dbs.ready) {
            add("库设置", false, "事项、行动、记录三个库还没设置")
            return steps
        }
        when (val r = api.me()) {
            is ApiResult.Err -> { add("连接 Notion", false, r.message); return steps }
            is ApiResult.Ok -> add("连接 Notion", true, "集成：${r.value}")
        }
        val schemas = mutableMapOf<Kind, Map<String, String>>()
        for (k in Kind.entries) {
            when (val r = api.schema(dbs.of(k))) {
                is ApiResult.Err -> { add("读取${k.label}库", false, r.message); return steps }
                is ApiResult.Ok -> {
                    schemas[k] = r.value.props
                    val miss = WorkSetup.missingColumns(k, r.value.props)
                    add("读取${k.label}库", miss.isEmpty(), if (miss.isEmpty()) "列齐全" else "缺少列：${miss.joinToString("、")}（缺的字段不会同步）")
                }
            }
        }
        val tag = "总控自检 " + Instant.ofEpochMilli(now).toString().take(16)
        val tProps = NotionMap.toProps(
            mapOf(F.TITLE to tag, F.STATUS to ThreadStatus.THINKING.label, F.BREAK to "自检写入", F.ZKID to "selftest-$now"),
            setOf(F.TITLE, F.STATUS, F.BREAK, F.ZKID), schemas.getValue(Kind.THREAD),
        ) { null }
        val thread = when (val r = api.createPageObject(dbs.thread, tProps.json, JsonArray(emptyList()))) {
            is ApiResult.Err -> { add("写入事项", false, r.message); return steps }
            is ApiResult.Ok -> NotionMap.fromPage(r.value).also { add("写入事项", true, "已新建「$tag」") }
        }
        val back = when (val r = api.getPage(thread.id)) {
            is ApiResult.Err -> { add("读回事项", false, r.message); null }
            is ApiResult.Ok -> NotionMap.fromPage(r.value)
        }
        if (back != null) {
            add("读回事项", back.fields[F.BREAK] == "自检写入" && back.fields[F.TITLE] == tag, "断点=${back.fields[F.BREAK]}，状态=${back.fields[F.STATUS]}")
        }
        val upd = NotionMap.toProps(mapOf(F.STATUS to ThreadStatus.READY.label), setOf(F.STATUS), schemas.getValue(Kind.THREAD)) { null }
        val edited = when (val r = api.updatePage(thread.id, upd.json)) {
            is ApiResult.Err -> { add("修改状态", false, r.message); null }
            is ApiResult.Ok -> NotionMap.fromPage(r.value).also { add("修改状态", it.fields[F.STATUS] == ThreadStatus.READY.label, "状态 → ${it.fields[F.STATUS]}") }
        }
        if (edited != null) {
            val since = runCatching { java.time.OffsetDateTime.parse(edited.lastEdited).minusMinutes(2).toString() }.getOrDefault(edited.lastEdited)
            val f = buildJsonObject { put("timestamp", "last_edited_time"); putJsonObject("last_edited_time") { put("on_or_after", since) } }
            when (val r = api.query(dbs.thread, f, null)) {
                is ApiResult.Err -> add("增量查询", false, r.message)
                is ApiResult.Ok -> add(
                    "增量查询", r.value.first.any { NotionMap.fromPage(it).id == thread.id },
                    "按修改时间查到 ${r.value.first.size} 条（应包含自检事项）",
                )
            }
        }
        val aProps = NotionMap.toProps(
            mapOf(F.TITLE to "$tag · 行动", F.THREAD to "x", F.STATUS to ActionStatus.TODO.label, F.ZKID to "selftest-a-$now"),
            setOf(F.TITLE, F.THREAD, F.STATUS, F.ZKID), schemas.getValue(Kind.ACTION),
        ) { thread.id }
        var actionId: String? = null
        when (val r = api.createPageObject(dbs.action, aProps.json, JsonArray(emptyList()))) {
            is ApiResult.Err -> add("写入关联行动", false, r.message)
            is ApiResult.Ok -> {
                val a = NotionMap.fromPage(r.value)
                actionId = a.id
                add("写入关联行动", a.fields[F.THREAD]?.replace("-", "") == thread.id.replace("-", ""), "行动的“事项”列指向自检事项")
            }
        }
        // 清理：归档测试页
        val cleaned = listOfNotNull(thread.id, actionId).all { archive(api, it) }
        add("清理测试数据", cleaned, if (cleaned) "测试页已归档（在 Notion 的回收站里）" else "归档失败，可以手动删除「$tag」")
        return steps
    }

    private suspend fun archive(api: NotionApi, id: String): Boolean = api.archivePage(id) is ApiResult.Ok
}

/** 在 Notion 页面末尾追加一段文字。 */
fun paragraph(text: String) = buildJsonObject {
    put("object", "block"); put("type", "paragraph")
    putJsonObject("paragraph") { putJsonArray("rich_text") { add(buildJsonObject { put("type", "text"); putJsonObject("text") { put("content", text.take(1900)) } }) } }
}
