package com.zongkong.core.work

import com.zongkong.core.ApiResult
import com.zongkong.core.NotionApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.random.Random

/** 存放 Work 的地方。同步过程中界面可能还在改，所以每一步都用 update 原子地合并，而不是整份覆盖。 */
interface WorkRef {
    fun get(): Work
    fun update(f: (Work) -> Work): Work
}

class MemoryRef(initial: Work = Work()) : WorkRef {
    private var w = initial
    override fun get() = w

    @Synchronized
    override fun update(f: (Work) -> Work): Work {
        w = f(w)
        return w
    }
}

data class Dbs(val thread: String, val action: String, val note: String) {
    val ready: Boolean get() = thread.isNotBlank() && action.isNotBlank() && note.isNotBlank()
    fun of(kind: Kind) = when (kind) {
        Kind.THREAD -> thread
        Kind.ACTION -> action
        Kind.NOTE -> note
    }
}

data class SyncReport(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val changed: Int = 0,
    val conflicts: Int = 0,
    val failed: Int = 0,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null && failed == 0
    val message: String
        get() = when {
            error != null -> "同步失败：$error"
            failed > 0 -> "部分失败：$failed 条没写进 Notion"
            else -> buildList {
                if (pushed > 0) add("写入 $pushed 条")
                if (changed > 0) add("读到 $changed 处更新")
                if (conflicts > 0) add("$conflicts 条有冲突")
            }.ifEmpty { listOf("已是最新") }.joinToString("，")
        }
}

/**
 * 本机 ↔ Notion 的双向同步。
 *
 * 规则：
 *  - 共享内容（事项、行动、记录的各字段）以 Notion 为准；本机改动先进待同步队列，联网后按字段写回。
 *  - 两边都改了同一字段：比较修改时间，后改的为准；另一份存进 conflicts，界面提示你看一眼。
 *  - 新建前先打标记；如果请求发出后没收到回复（断网、超时），下次先按“总控ID”查有没有建成，避免重复。
 *  - 拉取按 last_edited_time 增量查询，往前多查 2 分钟（Notion 的修改时间只精确到分钟）。
 */
class SyncEngine(private val api: NotionApi, private val rnd: Random = Random.Default) {

    suspend fun sync(ref: WorkRef, dbs: Dbs, now: Long): SyncReport {
        if (!dbs.ready) return SyncReport(error = "还没设置 Notion 的事项、行动、记录三个库")
        // 先拿库结构：写入时只写库里有的列
        for (kind in Kind.entries) {
            when (val r = api.schema(dbs.of(kind))) {
                is ApiResult.Err -> return finish(ref, now, SyncReport(error = r.message))
                is ApiResult.Ok -> ref.update { it.copy(schemas = it.schemas + (dbs.of(kind) to r.value.props)) }
            }
        }
        var report = SyncReport()
        // 先拉后推：先知道对面改了什么，再把本机改动合并后写回
        for (kind in listOf(Kind.THREAD, Kind.ACTION, Kind.NOTE)) {
            val r = pull(ref, dbs, kind, now)
            report = report.copy(pulled = report.pulled + r.pulled, changed = report.changed + r.changed, conflicts = report.conflicts + r.conflicts)
            if (r.error != null) return finish(ref, now, report.copy(error = r.error))
        }
        val p = push(ref, dbs, now)
        report = report.copy(pushed = p.pushed, failed = p.failed, conflicts = report.conflicts + p.conflicts, error = p.error)
        return finish(ref, now, report)
    }

    private fun finish(ref: WorkRef, now: Long, r: SyncReport): SyncReport {
        ref.update { it.copy(lastSyncAt = now, lastSyncOk = r.ok, lastSyncMessage = r.message) }
        return r
    }

    // ---------- 拉取 ----------

    suspend fun pull(ref: WorkRef, dbs: Dbs, kind: Kind, now: Long): SyncReport {
        val cursor = ref.get().cursors[kind.name]
        val filter = cursor?.let { c ->
            val since = runCatching { OffsetDateTime.parse(c).minusMinutes(2).toString() }.getOrDefault(c)
            buildJsonObject {
                put("timestamp", "last_edited_time")
                putJsonObject("last_edited_time") { put("on_or_after", since) }
            }
        }
        var next: String? = null
        var pulled = 0
        var changed = 0
        var conflicts = 0
        var maxEdited = cursor.orEmpty()
        do {
            val page = when (val r = api.query(dbs.of(kind), filter, next)) {
                is ApiResult.Err -> return SyncReport(pulled, 0, changed, conflicts, error = r.message)
                is ApiResult.Ok -> r.value
            }
            for (obj in page.first) {
                val remote = NotionMap.fromPage(obj)
                if (remote.id.isBlank() || remote.archived) continue
                pulled++
                if (remote.lastEdited > maxEdited) maxEdited = remote.lastEdited
                val (c, k) = apply(ref, kind, remote, now)
                changed += c
                conflicts += k
            }
            next = page.second
        } while (next != null)
        if (maxEdited.isNotEmpty()) ref.update { it.copy(cursors = it.cursors + (kind.name to maxEdited)) }
        return SyncReport(pulled = pulled, changed = changed, conflicts = conflicts)
    }

    /** 把一页远端内容合并进本机。返回（变化数，冲突数）。 */
    private fun apply(ref: WorkRef, kind: Kind, remote: RemotePage, now: Long): Pair<Int, Int> {
        var result = 0 to 0
        ref.update { w ->
            val fields = remoteFields(w, kind, remote)
            val zk = fields[F.ZKID].orEmpty()
            val local = w.byNotion(remote.id) ?: w.rec(zk)?.takeIf { it.kind == kind && it.notionId.isEmpty() }
            if (local == null) {
                // Notion 里新出现的（GPT 写的、你在 Notion 里建的）
                val key = if (zk.isNotBlank() && w.rec(zk) == null) zk else "n" + remote.id.replace("-", "").take(12)
                var rec = Rec(
                    kind = kind, key = key, notionId = remote.id, url = remote.url, f = fields, base = fields,
                    remoteEdited = remote.lastEdited, sync = SyncState.SYNCED, createdAt = now, updatedAt = now,
                )
                var w2 = w.copy(recs = w.recs + rec)
                // 没有编号的事项补一个编号，写回 Notion
                if (kind == Kind.THREAD && fields[F.CODE].isNullOrBlank()) {
                    w2 = WorkOps.update(w2, key, mapOf(F.CODE to WorkOps.newCode(w2, rnd)), now)
                    rec = w2.rec(key)!!
                }
                result = 1 to 0
                w2.addChange(Change(now, key, kind, rec.title, "新出现（来自${by(fields)}）", by(fields)))
            } else {
                val m = merge(local.copy(notionId = remote.id, url = remote.url.ifBlank { local.url }), remote.copy(fields = fields), now)
                result = m.changes.size to m.conflicts
                var w2 = w.copy(recs = w.recs.map { if (it.key == local.key) m.rec else it })
                m.changes.forEach { w2 = w2.addChange(it) }
                w2
            }
        }
        // 只写了“事项编号”、没填关联列的（GPT 常这样）：按编号找到事项后，把关联补写回 Notion
        val rawRel = remote.fields[F.THREAD].orEmpty()
        if (kind != Kind.THREAD && rawRel.isBlank()) {
            ref.update { w ->
                val rec = w.byNotion(remote.id) ?: return@update w
                val k = rec.threadKey
                if (k.isBlank() || k.startsWith("@") || F.THREAD in rec.dirty) return@update w
                val fixed = rec.copy(dirty = rec.dirty + F.THREAD, base = rec.base + (F.THREAD to ""), sync = if (rec.sync == SyncState.CONFLICT) rec.sync else SyncState.PENDING)
                w.copy(recs = w.recs.map { if (it.key == rec.key) fixed else it })
            }
        }
        return result
    }

    /** 远端字段整理成本机格式：关联换成事项 key；没填关联但写了事项编号的，按编号找。 */
    private fun remoteFields(w: Work, kind: Kind, remote: RemotePage): Map<String, String> {
        val f = remote.fields.toMutableMap()
        if (kind != Kind.THREAD) {
            val rel = f[F.THREAD].orEmpty()
            val byRel = w.byNotion(rel)?.key
            val byCode = w.threadByCode(f[F.THREAD_CODE].orEmpty())?.key
            f[F.THREAD] = byRel ?: byCode ?: if (rel.isNotBlank()) "@$rel" else ""
        }
        return f
    }

    data class Merged(val rec: Rec, val changes: List<Change>, val conflicts: Int)

    /**
     * 字段级三方合并。base 是上次对齐时的值。
     *  - 只有远端改了：采用远端
     *  - 只有本机改了：保留本机，等着推上去
     *  - 都改了且不同：后改的为准，另一份进 conflicts
     */
    fun merge(local: Rec, remote: RemotePage, now: Long): Merged {
        val f = local.f.toMutableMap()
        val base = local.base.toMutableMap()
        val dirty = local.dirty.toMutableSet()
        val conflicts = local.conflicts.toMutableMap()
        val changes = mutableListOf<Change>()
        var newConflicts = 0
        val remoteMillis = runCatching { OffsetDateTime.parse(remote.lastEdited).toInstant().toEpochMilli() }.getOrDefault(0L)
        val who = by(remote.fields)
        for ((k, rv) in remote.fields) {
            val lv = f[k].orEmpty()
            val bv = base[k]
            if (k == F.SOURCE) {
                // “谁最后改的”只是标记：本机有改动就写本机的，否则跟着远端
                if (k !in dirty) f[k] = rv
                base[k] = rv
                continue
            }
            if (k !in dirty) {
                if (!NotionMap.same(k, lv, rv)) {
                    f[k] = rv
                    if (k !in QUIET) changes += Change(now, local.key, local.kind, f[F.TITLE] ?: local.title, describe(k, lv, rv), who)
                }
            } else {
                when {
                    NotionMap.same(k, lv, rv) -> dirty -= k
                    bv != null && NotionMap.same(k, bv, rv) -> {} // 远端没动，保留本机改动
                    remoteMillis > local.updatedAt -> {
                        conflicts[k] = lv
                        f[k] = rv
                        dirty -= k
                        newConflicts++
                        changes += Change(now, local.key, local.kind, f[F.TITLE] ?: local.title, "冲突：${describe(k, lv, rv)}（采用 Notion 的，手机上的已保留）", who)
                    }
                    else -> {
                        conflicts[k] = rv
                        newConflicts++
                        changes += Change(now, local.key, local.kind, f[F.TITLE] ?: local.title, "冲突：$k 两边都改了（采用手机上的，Notion 的已保留）", who)
                    }
                }
            }
            base[k] = rv
        }
        val sync = when {
            conflicts.isNotEmpty() -> SyncState.CONFLICT
            dirty.isNotEmpty() -> SyncState.PENDING
            else -> SyncState.SYNCED
        }
        return Merged(
            local.copy(f = f, base = base, dirty = dirty, conflicts = conflicts, remoteEdited = remote.lastEdited, sync = sync, syncError = if (sync == SyncState.SYNCED) "" else local.syncError),
            changes, newConflicts,
        )
    }

    // ---------- 推送 ----------

    private data class PushResult(val pushed: Int, val failed: Int, val conflicts: Int, val error: String?)

    private suspend fun push(ref: WorkRef, dbs: Dbs, now: Long): PushResult {
        var pushed = 0
        var failed = 0
        var conflicts = 0
        // 事项先推，行动和记录的关联才有 Notion ID 可指
        val order = listOf(Kind.THREAD, Kind.ACTION, Kind.NOTE)
        for (kind in order) {
            val todo = ref.get().recs.filter { it.kind == kind && (it.notionId.isEmpty() || it.dirty.isNotEmpty()) }
            for (snap in todo) {
                val r = pushOne(ref, dbs, snap, now)
                when (r) {
                    is One.Ok -> pushed++
                    is One.Conflict -> { pushed++; conflicts++ }
                    is One.Failed -> failed++
                    is One.Offline -> return PushResult(pushed, failed, conflicts, r.message)
                    is One.Deferred -> {}
                }
            }
        }
        return PushResult(pushed, failed, conflicts, null)
    }

    private sealed interface One {
        data object Ok : One
        data object Conflict : One
        data object Deferred : One
        data class Failed(val message: String) : One
        data class Offline(val message: String) : One
    }

    private fun isNetwork(msg: String) = msg.contains("网络") || msg.contains("超时") || msg.contains("429") || msg.contains("Notion 服务出错")

    private fun markError(ref: WorkRef, key: String, msg: String, failed: Boolean) {
        ref.update { w ->
            w.copy(recs = w.recs.map { if (it.key == key) it.copy(syncError = msg, sync = if (failed) SyncState.FAILED else it.sync) else it })
        }
    }

    private suspend fun pushOne(ref: WorkRef, dbs: Dbs, snap: Rec, now: Long): One {
        val db = dbs.of(snap.kind)
        val schema = ref.get().schemas[db].orEmpty()
        fun threadId(key: String): String? {
            if (key.startsWith("@")) return key.removePrefix("@")
            return ref.get().rec(key)?.notionId?.takeIf { it.isNotEmpty() }
        }

        if (snap.notionId.isEmpty()) {
            // 之前发过创建请求但没收到结果：先查一下是不是已经建好了
            if (snap.createTried) {
                val found = when (val r = api.query(db, zkFilter(snap.key), null)) {
                    is ApiResult.Err -> return if (isNetwork(r.message)) One.Offline(r.message) else One.Failed(r.message).also { markError(ref, snap.key, r.message, true) }
                    is ApiResult.Ok -> r.value.first.firstOrNull()
                }
                if (found != null) {
                    val remote = NotionMap.fromPage(found)
                    ref.update { w ->
                        val cur = w.rec(snap.key) ?: return@update w
                        val adopted = cur.copy(notionId = remote.id, url = remote.url, createTried = false)
                        // 已建好的那份当作基准，本机字段仍待写
                        w.copy(recs = w.recs.map { if (it.key == snap.key) adopted.copy(base = remoteFields(w, snap.kind, remote)) else it })
                    }
                    return pushOne(ref, dbs, ref.get().rec(snap.key)!!, now)
                }
            }
            val fields = snap.f
            val props = NotionMap.toProps(fields, fields.keys, schema, ::threadId)
            if (props.deferred.isNotEmpty() && snap.kind != Kind.THREAD) {
                // 所属事项还没进 Notion：先等事项建好
                val t = snap.threadKey
                if (t.isNotEmpty() && ref.get().rec(t)?.notionId.isNullOrEmpty()) return One.Deferred
            }
            ref.update { w -> w.copy(recs = w.recs.map { if (it.key == snap.key) it.copy(createTried = true) else it }) }
            val body = if (snap.kind == Kind.NOTE && snap[F.RAW].length > 300) com.zongkong.core.NotionClient.paragraphs(snap[F.RAW]) else JsonArray(emptyList())
            return when (val r = api.createPageObject(db, props.json, body)) {
                is ApiResult.Err -> if (isNetwork(r.message)) {
                    markError(ref, snap.key, r.message, false); One.Offline(r.message)
                } else {
                    markError(ref, snap.key, r.message, true); One.Failed(r.message)
                }
                is ApiResult.Ok -> {
                    val remote = NotionMap.fromPage(r.value)
                    settle(ref, snap.key, remote, fields, props, now)
                    One.Ok
                }
            }
        }

        // 已在 Notion 里：先看对面有没有改过，合并后只写本机改了的字段
        val remoteObj = when (val r = api.getPage(snap.notionId)) {
            is ApiResult.Err -> return if (isNetwork(r.message)) {
                markError(ref, snap.key, r.message, false); One.Offline(r.message)
            } else {
                val msg = if (r.message.contains("404")) "Notion 里找不到这条了（可能被删除，或没有分享给集成）" else r.message
                markError(ref, snap.key, msg, true); One.Failed(msg)
            }
            is ApiResult.Ok -> r.value
        }
        val remote = NotionMap.fromPage(remoteObj)
        var hadConflict = false
        if (remote.lastEdited != snap.remoteEdited) {
            ref.update { w ->
                val cur = w.rec(snap.key) ?: return@update w
                val m = merge(cur, remote.copy(fields = remoteFields(w, snap.kind, remote)), now)
                hadConflict = m.conflicts > 0
                var w2 = w.copy(recs = w.recs.map { if (it.key == snap.key) m.rec else it })
                m.changes.forEach { w2 = w2.addChange(it) }
                w2
            }
        }
        val cur = ref.get().rec(snap.key) ?: return One.Ok
        if (cur.dirty.isEmpty()) return if (hadConflict) One.Conflict else One.Ok
        val props = NotionMap.toProps(cur.f, cur.dirty, schema, ::threadId)
        if (props.written.isEmpty()) {
            settle(ref, snap.key, null, cur.f, props, now)
            return if (hadConflict) One.Conflict else One.Ok
        }
        return when (val r = api.updatePage(cur.notionId, props.json)) {
            is ApiResult.Err -> if (isNetwork(r.message)) {
                markError(ref, snap.key, r.message, false); One.Offline(r.message)
            } else {
                markError(ref, snap.key, r.message, true); One.Failed(r.message)
            }
            is ApiResult.Ok -> {
                settle(ref, snap.key, NotionMap.fromPage(r.value), cur.f, props, now)
                if (hadConflict) One.Conflict else One.Ok
            }
        }
    }

    /** 写成功后的记账：写过去的字段对齐 base；写的过程中你又改了的字段仍然待同步。 */
    private fun settle(ref: WorkRef, key: String, remote: RemotePage?, sent: Map<String, String>, props: NotionMap.Props, now: Long) {
        ref.update { w ->
            val cur = w.rec(key) ?: return@update w
            val base = cur.base.toMutableMap()
            val dirty = cur.dirty.toMutableSet()
            for (k in props.written) {
                base[k] = sent[k].orEmpty()
                if (cur[k] == sent[k].orEmpty()) dirty -= k
            }
            // 库里没有这一列：写不进去，不再重试
            dirty -= props.skipped
            val warn = if (props.skipped.isNotEmpty()) "Notion 库里缺少列：${props.skipped.joinToString("、")}" else ""
            val next = cur.copy(
                notionId = remote?.id?.ifBlank { null } ?: cur.notionId,
                url = remote?.url?.ifBlank { null } ?: cur.url,
                remoteEdited = remote?.lastEdited?.ifBlank { null } ?: cur.remoteEdited,
                base = base,
                dirty = dirty,
                createTried = false,
                sync = when {
                    cur.conflicts.isNotEmpty() -> SyncState.CONFLICT
                    dirty.isNotEmpty() -> SyncState.PENDING
                    else -> SyncState.SYNCED
                },
                syncError = warn,
            )
            w.copy(recs = w.recs.map { if (it.key == key) next else it })
        }
    }

    private fun zkFilter(key: String) = buildJsonObject {
        put("property", F.ZKID)
        putJsonObject("rich_text") { put("equals", key) }
    }

    companion object {
        /** 这些字段变了不值得提示。 */
        val QUIET = setOf(F.ZKID, F.SOURCE, F.THREAD_CODE, F.CODE)

        fun by(fields: Map<String, String>): String = when (fields[F.SOURCE]) {
            "总控" -> "总控"
            "GPT" -> "GPT"
            "手动" -> "Notion（手动）"
            else -> "Notion / GPT"
        }

        fun describe(k: String, old: String, new: String): String = when {
            old.isBlank() -> "$k：${new.take(40)}"
            new.isBlank() -> "$k 被清空"
            k == F.STATUS || k == F.TYPE || k == F.STUCK || k == F.PRIORITY -> "$k：$old → $new"
            else -> "$k 更新：${new.take(40)}"
        }
    }
}

fun Work.addChange(c: Change): Work = copy(changes = (changes + c).takeLast(WorkOps.MAX_CHANGES))

fun isoNow(now: Long): String = Instant.ofEpochMilli(now).toString()
