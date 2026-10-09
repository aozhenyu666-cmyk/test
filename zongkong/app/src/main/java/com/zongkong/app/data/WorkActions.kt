package com.zongkong.app.data

import android.content.Context
import android.util.Log
import com.zongkong.app.system.Reminders
import com.zongkong.core.ApiResult
import com.zongkong.core.DayOps
import com.zongkong.core.Net
import com.zongkong.core.NotionClient
import com.zongkong.core.work.Capture
import com.zongkong.core.work.Dbs
import com.zongkong.core.work.F
import com.zongkong.core.work.Handoff
import com.zongkong.core.work.Kind
import com.zongkong.core.work.NoteType
import com.zongkong.core.work.Plan
import com.zongkong.core.work.SelfTest
import com.zongkong.core.work.StuckReason
import com.zongkong.core.work.SyncEngine
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.WorkOps
import com.zongkong.core.work.WorkSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.time.Instant
import kotlin.random.Random

/**
 * 事项、行动、记录：本机先改（立即生效、断网也能用），再在后台同步到 Notion。
 * 同步结果如实记在 Work 里（已同步 / 待同步 / 同步失败 / 有冲突），界面照着显示。
 */
class WorkActions(private val context: Context, private val store: Store, private val notion: NotionClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pendingKick: Job? = null
    private val rnd = Random.Default
    private val zone get() = store.zone
    private fun now() = System.currentTimeMillis()

    private fun dbs(): Dbs = store.config.value.notion.let { Dbs(it.threadsDb, it.actionsDb, it.notesDb) }

    val ready: Boolean get() = store.config.value.notion.workReady

    // ---------- 同步 ----------

    suspend fun sync(): String = lock.withLock {
        if (!ready) return@withLock "还没设置 Notion 的事项、行动、记录三个库（设置 → Notion）"
        val r = try {
            SyncEngine(notion, rnd).sync(store.workRef, dbs(), now())
        } catch (e: Exception) {
            Log.e(TAG, "同步出错", e)
            val msg = "同步出错：${e.message ?: e.javaClass.simpleName}"
            store.updateWork { it.copy(lastSyncAt = now(), lastSyncOk = false, lastSyncMessage = msg) }
            return@withLock msg
        }
        Reminders.reschedule(context)
        r.message
    }

    /** 改完以后 2 秒内没有新改动，就在后台同步一次。 */
    fun kick() {
        Reminders.reschedule(context)
        if (!ready) return
        pendingKick?.cancel()
        pendingKick = scope.launch {
            delay(2_000)
            sync()
        }
    }

    /** 旧的“验收日志、报到”待写队列，换成记录库里的记录。 */
    fun absorbOutbox() {
        for (d in store.history(3)) {
            for (e in d.outbox) {
                val type = if (e.result == "报到") NoteType.CHECKIN else NoteType.REVIEW
                store.updateWork {
                    WorkOps.create(it, Kind.NOTE, mapOf(
                        F.TITLE to "${e.dept.label} · ${e.title} · ${e.result}".take(80),
                        F.TYPE to type.label, F.RAW to e.text,
                        F.DATE to Instant.ofEpochMilli(e.at).atZone(zone).toLocalDate().toString(),
                    ), e.at, rnd).first
                }
                store.updateDay(d.date) { day -> day.copy(outbox = day.outbox - e) }
            }
        }
    }

    // ---------- 收集 ----------

    /**
     * 收进一条记录。原文完整保留；类型、标题、链接已经自动猜好，你可以改。
     * [asAction] 为真时同时在事项下建一个同名行动（没排时间）。
     */
    fun capture(raw: String, type: NoteType, title: String, url: String, threadKey: String, asAction: Boolean, image: String = ""): String {
        val t = now()
        var key = ""
        store.updateWork { w0 ->
            val (w1, n) = WorkOps.create(w0, Kind.NOTE, mapOf(
                F.TITLE to title.ifBlank { Capture.guess(raw).title },
                F.TYPE to type.label, F.URL to url, F.THREAD to threadKey,
                F.RAW to buildString {
                    append(raw.trim())
                    if (image.isNotBlank()) append("\n[截图只存在手机里：$image]")
                },
                F.DATE to Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toString(),
            ), t, rnd)
            key = n.key
            if (asAction && threadKey.isNotBlank()) {
                WorkOps.create(w1, Kind.ACTION, mapOf(F.TITLE to title.ifBlank { raw.take(40) }, F.THREAD to threadKey), t, rnd).first
            } else {
                w1
            }
        }
        kick()
        return key
    }

    /** 给链接补标题：抓网页的 og:title / title。失败就算了。 */
    suspend fun fetchTitle(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) Zongkong").build()
            Net.client(8).newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else Capture.htmlTitle(r.peekBody(200_000).string())
            }
        } catch (e: Exception) {
            null
        }
    }

    fun addConclusion(text: String, threadKey: String = "") {
        val t = now()
        store.updateWork {
            WorkOps.create(it, Kind.NOTE, mapOf(
                F.TITLE to "判断：" + text.lines().firstOrNull { l -> l.isNotBlank() }.orEmpty().take(60),
                F.TYPE to NoteType.CONCLUSION.label, F.RAW to text.trim(), F.THREAD to threadKey,
                F.DATE to Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toString(),
            ), t, rnd).first
        }
        kick()
    }

    // ---------- 事项、行动 ----------

    fun createThread(title: String, why: String = "", next: String = "", criteria: String = ""): String {
        var key = ""
        store.updateWork {
            val (w, r) = WorkOps.create(it, Kind.THREAD, mapOf(F.TITLE to title.trim(), F.WHY to why, F.NEXT to next, F.CRITERIA to criteria), now(), rnd)
            key = r.key
            w
        }
        kick()
        return key
    }

    fun update(key: String, changes: Map<String, String>) {
        store.updateWork { WorkOps.update(it, key, changes, now()) }
        kick()
    }

    /** 新增行动。时间写不出来时返回提示，不建。 */
    fun addAction(threadKey: String, title: String, time: String, criteria: String): Pair<String?, String?> {
        val plan = if (time.isBlank()) null else TimeParse.parse(time, now(), zone) ?: return null to "时间“$time”没认出来，换个写法：明天 20:00、周六 10:00-11:00"
        var key = ""
        store.updateWork {
            val (w, r) = WorkOps.create(it, Kind.ACTION, buildMap {
                put(F.TITLE, title.trim()); put(F.THREAD, threadKey); put(F.CRITERIA, criteria.trim())
                plan?.let { p -> put(F.PLAN, p.encode()) }
            }, now(), rnd)
            key = r.key
            w
        }
        kick()
        return key to null
    }

    /** 开始一步。开专注锁时，锁到计划结束时间（没有就用默认时长）。 */
    fun start(actionKey: String, lock: Boolean, minutes: Int? = null) {
        val t = now()
        val a = store.work.value.rec(actionKey)
        val planned = a?.plan?.let { TimeParse.endMillis(it, zone) }?.takeIf { it > t + 5 * 60_000 }?.let { ((it - t) / 60_000).toInt() }
        val m = if (!lock) 0 else minutes ?: planned ?: store.config.value.focusMinutes
        store.updateWork { WorkOps.start(it, actionKey, t, m.coerceIn(0, 240)) }
        kick()
    }

    fun extendLock(minutes: Int) = store.updateWork { w ->
        w.session?.let { s -> w.copy(session = s.copy(lockUntil = maxOf(s.lockUntil, now()) + minutes * 60_000L)) } ?: w
    }

    fun unlock() = store.updateWork { w -> w.session?.let { w.copy(session = it.copy(lockUntil = 0)) } ?: w }

    fun progress(text: String) = store.updateWork { WorkOps.progress(it, text) }

    fun left() = store.updateWork { WorkOps.left(it, now()) }

    fun back() = store.updateWork { w -> w.session?.let { w.copy(session = it.copy(leftAt = 0)) } ?: w }

    fun pause(actionKey: String, done: String, blocker: String, next: String) {
        store.updateWork { WorkOps.pause(it, actionKey, done, blocker, next, now(), zone, rnd) }
        kick()
    }

    fun complete(actionKey: String, result: String, met: WorkOps.Met, next: String) {
        store.updateWork { WorkOps.complete(it, actionKey, result, met, next, now(), zone, rnd) }
        kick()
    }

    fun stuck(actionKey: String, reason: StuckReason, detail: String) {
        store.updateWork { WorkOps.stuck(it, actionKey, reason, detail, now(), zone, rnd) }
        kick()
    }

    /** 记一次没推进的原因，但不改状态（马上就要开始做了）。 */
    fun noteReason(actionKey: String, reason: StuckReason) {
        store.updateWork { WorkOps.event(it, com.zongkong.core.work.Event(now(), "stuck:${reason.name}", actionKey)) }
    }

    fun reschedule(actionKey: String, time: String): String? {
        val plan = TimeParse.parse(time, now(), zone) ?: return "时间“$time”没认出来，换个写法：今晚 20:00、明天 9:00"
        rescheduleTo(actionKey, plan)
        return null
    }

    fun rescheduleTo(actionKey: String, plan: Plan) {
        store.updateWork { WorkOps.reschedule(it, actionKey, plan, now()) }
        kick()
    }

    /** 推迟 N 分钟（通知里的“推迟”）。保留原来的时长。 */
    fun snooze(actionKey: String, minutes: Int) {
        val a = store.work.value.rec(actionKey) ?: return
        val p = a.plan
        val dur = p?.takeIf { it.end.isNotBlank() }?.let { pp ->
            val s = TimeParse.startMillis(pp, zone)
            val e = TimeParse.endMillis(pp, zone)
            if (s != null && e != null) e - s else null
        }
        val fmt = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val start = Instant.ofEpochMilli(now() + minutes * 60_000L).atZone(zone).withSecond(0).withNano(0)
        val plan = Plan(start.format(fmt), dur?.let { start.plusSeconds(it / 1000).format(fmt) }.orEmpty())
        rescheduleTo(actionKey, plan)
    }

    fun cancel(actionKey: String, reason: String) {
        store.updateWork { WorkOps.cancel(it, actionKey, reason, now()) }
        kick()
    }

    /** 冲突：用另一份（useOther）或保留当前。 */
    fun pickConflict(key: String, field: String, useOther: Boolean) {
        store.updateWork { w ->
            val r = w.rec(key) ?: return@updateWork w
            var w2 = w
            if (useOther) w2 = WorkOps.update(w2, key, mapOf(field to r.conflicts[field].orEmpty()), now())
            val cur = w2.rec(key)!!
            val left = cur.conflicts - field
            w2.copy(recs = w2.recs.map {
                if (it.key != key) it else it.copy(
                    conflicts = left,
                    sync = when {
                        left.isNotEmpty() -> com.zongkong.core.work.SyncState.CONFLICT
                        it.dirty.isNotEmpty() || it.notionId.isEmpty() -> com.zongkong.core.work.SyncState.PENDING
                        else -> com.zongkong.core.work.SyncState.SYNCED
                    },
                )
            })
        }
        kick()
    }

    // ---------- GPT 交接 ----------

    fun planImport(text: String): Handoff.Plan? {
        val b = Handoff.parse(text) ?: return null
        if (b.empty && !b.alreadyWritten) return null
        return Handoff.plan(store.work.value, b, now(), zone)
    }

    fun applyImport(plan: Handoff.Plan): String {
        var key = ""
        store.updateWork {
            val (w, k) = Handoff.apply(it, plan, now(), rnd)
            key = k
            w
        }
        store.awaitingGpt = ""
        kick()
        return key
    }

    fun gptPrompt(threadKey: String, ask: String = ""): String = Handoff.contextPrompt(store.work.value, threadKey, now(), zone, ask)

    fun stuckPrompt(actionKey: String, reason: StuckReason, detail: String): String =
        Handoff.stuckPrompt(store.work.value, actionKey, reason, detail, now(), zone)

    // ---------- Notion 设置 ----------

    suspend fun setup(parentPage: String): String = when (val r = WorkSetup.create(notion, parentPage)) {
        is ApiResult.Err -> r.message
        is ApiResult.Ok -> {
            store.updateConfig { c ->
                c.copy(notion = c.notion.copy(parentPage = parentPage, threadsDb = r.value.thread, actionsDb = r.value.action, notesDb = r.value.note))
            }
            "已在 Notion 建好：总控·事项、总控·行动、总控·记录。\n" + sync()
        }
    }

    suspend fun selfTest(): List<SelfTest.Step> = SelfTest.run(notion, dbs(), now())

    fun newChanges(): Int = store.work.value.changes.count { it.at > store.seenChanges }

    companion object {
        private const val TAG = "WorkActions"
    }
}
