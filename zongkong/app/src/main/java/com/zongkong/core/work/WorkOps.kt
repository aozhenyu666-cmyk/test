package com.zongkong.core.work

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/** 对本机记录的修改。纯函数：输入旧的 Work，返回新的 Work。所有改动都标记为待同步。 */
object WorkOps {
    private const val CODE_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val MAX_EVENTS = 400
    const val MAX_CHANGES = 80

    fun newKey(now: Long, rnd: Random): String = "k" + now.toString(36) + (1..4).map { CODE_CHARS[rnd.nextInt(CODE_CHARS.length)] }.joinToString("").lowercase()

    /** 事项编号：ZK- 加 4 位，不和已有的重复。给人看、给 GPT 引用。 */
    fun newCode(work: Work, rnd: Random): String {
        val used = work.threads().map { it[F.CODE].uppercase() }.toSet()
        while (true) {
            val c = "ZK-" + (1..4).map { CODE_CHARS[rnd.nextInt(CODE_CHARS.length)] }.joinToString("")
            if (c !in used) return c
        }
    }

    fun create(work: Work, kind: Kind, fields: Map<String, String>, now: Long, rnd: Random): Pair<Work, Rec> {
        val key = newKey(now, rnd)
        var f = fields.filterValues { it.isNotBlank() } + (F.ZKID to key) + (F.SOURCE to "总控")
        if (kind == Kind.THREAD && f[F.CODE].isNullOrBlank()) f = f + (F.CODE to newCode(work, rnd))
        if (kind == Kind.THREAD && f[F.STATUS].isNullOrBlank()) f = f + (F.STATUS to ThreadStatus.COLLECTING.label)
        if (kind == Kind.ACTION && f[F.STATUS].isNullOrBlank()) f = f + (F.STATUS to ActionStatus.TODO.label)
        if (kind != Kind.THREAD) {
            val t = f[F.THREAD]?.let { work.rec(it) }
            if (t != null) f = f + (F.THREAD_CODE to t[F.CODE])
        }
        val rec = Rec(
            kind = kind, key = key, f = f, dirty = f.keys, sync = SyncState.PENDING,
            createdAt = now, updatedAt = now,
        )
        return work.copy(recs = work.recs + rec) to rec
    }

    /** 改几个字段。值没变的不算改动。 */
    fun update(work: Work, key: String, changes: Map<String, String>, now: Long): Work {
        val rec = work.rec(key) ?: return work
        val real = changes.filter { (k, v) -> rec[k] != v }
        if (real.isEmpty()) return work
        var extra = emptyMap<String, String>()
        if (F.THREAD in real && rec.kind != Kind.THREAD) {
            extra = mapOf(F.THREAD_CODE to (work.rec(real.getValue(F.THREAD))?.get(F.CODE) ?: ""))
        }
        val all = real + extra + (F.SOURCE to "总控")
        val next = rec.copy(
            f = rec.f + all,
            dirty = rec.dirty + all.keys,
            sync = if (rec.sync == SyncState.CONFLICT) SyncState.CONFLICT else SyncState.PENDING,
            updatedAt = now,
        )
        return work.copy(recs = work.recs.map { if (it.key == key) next else it })
    }

    fun event(work: Work, e: Event): Work = work.copy(events = (work.events + e).takeLast(MAX_EVENTS))

    /** 冲突看过了：保留当前值，丢掉另一份。 */
    fun resolveConflicts(work: Work, key: String): Work = work.copy(
        recs = work.recs.map {
            if (it.key != key) it else it.copy(conflicts = emptyMap(), sync = if (it.dirty.isEmpty()) SyncState.SYNCED else SyncState.PENDING)
        },
    )

    // ---------- 一步行动的生命周期 ----------

    fun start(work: Work, actionKey: String, now: Long, lockMinutes: Int): Work {
        val a = work.rec(actionKey) ?: return work
        var w = update(work, actionKey, mapOf(F.STATUS to ActionStatus.DOING.label), now)
        a.threadKey.takeIf { it.isNotEmpty() }?.let { tk ->
            val t = w.rec(tk)
            if (t != null && t.threadStatus != ThreadStatus.ACTIVE) w = update(w, tk, mapOf(F.STATUS to ThreadStatus.ACTIVE.label), now)
        }
        val lock = if (lockMinutes > 0) now + lockMinutes * 60_000L else 0
        w = w.copy(session = Session(actionKey, now, lockUntil = lock))
        return event(w, Event(now, "start", actionKey))
    }

    fun progress(work: Work, text: String): Work = work.session?.let { work.copy(session = it.copy(progress = text)) } ?: work

    fun left(work: Work, now: Long): Work = work.session?.let { work.copy(session = it.copy(leftAt = now)) } ?: work

    /**
     * 中途离开：记下做到哪、卡在哪、下一步。写到行动、事项和一条“断点”记录上。
     * 回来时从这里接着做。
     */
    fun pause(work: Work, actionKey: String, done: String, blocker: String, next: String, now: Long, zone: ZoneId, rnd: Random): Work {
        val a = work.rec(actionKey) ?: return work
        val text = breakpointText(a.title, done, blocker, now, zone)
        var w = update(work, actionKey, mapOf(F.BREAK to text, F.STATUS to ActionStatus.DOING.label), now)
        if (a.threadKey.isNotEmpty()) {
            w = update(w, a.threadKey, buildMap {
                put(F.BREAK, text)
                if (next.isNotBlank()) put(F.NEXT, next.trim())
            }, now)
        }
        w = create(w, Kind.NOTE, mapOf(
            F.TITLE to "断点：${a.title}".take(80), F.TYPE to NoteType.BREAKPOINT.label, F.THREAD to a.threadKey,
            F.RAW to listOf("做到：$done", "卡在：$blocker", "下一步：$next").filter { !it.endsWith("：") }.joinToString("\n"),
            F.DATE to Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString(),
        ), now, rnd).first
        w = w.copy(session = if (w.session?.actionKey == actionKey) null else w.session)
        return event(w, Event(now, "pause", actionKey))
    }

    enum class Met(val label: String) { YES("达到"), PARTIAL("部分达到"), NO("没达到") }

    /**
     * 做完（或做不下去）：结果回到行动上，另记一条“结果”记录，事项的断点和下一步跟着更新。
     * 达到完成依据 → 完成；部分达到 → 待确认（交给 GPT 或你自己判断）；没达到 → 卡住。
     */
    fun complete(
        work: Work, actionKey: String, result: String, met: Met, next: String,
        now: Long, zone: ZoneId, rnd: Random,
    ): Work {
        val a = work.rec(actionKey) ?: return work
        val status = when (met) {
            Met.YES -> ActionStatus.DONE
            Met.PARTIAL -> ActionStatus.CONFIRM
            Met.NO -> ActionStatus.STUCK
        }
        val resultText = "${met.label}。${result.trim()}".trim()
        var w = update(work, actionKey, mapOf(F.STATUS to status.label, F.RESULT to resultText), now)
        if (a.threadKey.isNotEmpty()) {
            val stamp = Instant.ofEpochMilli(now).atZone(zone).let { "%02d-%02d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }
            w = update(w, a.threadKey, buildMap {
                put(F.BREAK, "【$stamp】「${a.title}」${met.label}：${result.trim()}".take(1800))
                if (next.isNotBlank()) put(F.NEXT, next.trim())
                // 还有没做完的行动就继续推进，否则等下一步
                val stillOpen = w.actionsOf(a.threadKey).any { it.key != actionKey && it.actionStatus.open }
                put(F.STATUS, if (stillOpen) ThreadStatus.ACTIVE.label else if (next.isNotBlank()) ThreadStatus.READY.label else ThreadStatus.THINKING.label)
            }, now)
        }
        w = create(w, Kind.NOTE, mapOf(
            F.TITLE to "结果：${a.title}".take(80), F.TYPE to NoteType.RESULT.label, F.THREAD to a.threadKey,
            F.RAW to buildString {
                append("完成依据：${a[F.CRITERIA].ifBlank { "（没写）" }}\n")
                append("对照：${met.label}\n")
                append("结果：${result.trim()}")
                if (next.isNotBlank()) append("\n下一步：${next.trim()}")
            },
            F.DATE to Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString(),
        ), now, rnd).first
        w = w.copy(session = if (w.session?.actionKey == actionKey) null else w.session)
        return event(w, Event(now, "done:${met.name}", actionKey))
    }

    fun stuck(work: Work, actionKey: String, reason: StuckReason, detail: String, now: Long, zone: ZoneId, rnd: Random): Work {
        val a = work.rec(actionKey) ?: return work
        var w = update(work, actionKey, mapOf(F.STATUS to ActionStatus.STUCK.label, F.STUCK to reason.label), now)
        if (a.threadKey.isNotEmpty() && reason in setOf(StuckReason.CANT, StuckReason.UNCLEAR)) {
            w = update(w, a.threadKey, mapOf(F.STATUS to ThreadStatus.STUCK.label), now)
        }
        w = create(w, Kind.NOTE, mapOf(
            F.TITLE to "卡点：${a.title} · ${reason.label}".take(80), F.TYPE to NoteType.STUCK.label, F.THREAD to a.threadKey,
            F.RAW to "原因：${reason.label}${if (detail.isNotBlank()) "\n说明：${detail.trim()}" else ""}",
            F.DATE to Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString(),
        ), now, rnd).first
        w = w.copy(session = if (w.session?.actionKey == actionKey) null else w.session)
        return event(w, Event(now, "stuck:${reason.name}", actionKey, detail.take(80)))
    }

    /** 改期：换计划时间，状态记为“改期”（仍然算没做完）。 */
    fun reschedule(work: Work, actionKey: String, plan: Plan, now: Long): Work {
        val w = update(work, actionKey, mapOf(F.PLAN to plan.encode(), F.STATUS to ActionStatus.RESCHEDULED.label), now)
        return event(w, Event(now, "reschedule", actionKey, plan.start))
    }

    fun cancel(work: Work, actionKey: String, reason: String, now: Long): Work {
        val a = work.rec(actionKey) ?: return work
        val w = update(work, actionKey, buildMap {
            put(F.STATUS, ActionStatus.CANCELLED.label)
            if (reason.isNotBlank()) put(F.RESULT, "取消：${reason.trim()}")
        }, now)
        return event(w, Event(now, "cancel", a.key))
    }

    fun breakpointText(title: String, done: String, blocker: String, now: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        return buildString {
            append("【%02d-%02d %02d:%02d · %s】".format(t.monthValue, t.dayOfMonth, t.hour, t.minute, title.take(30)))
            if (done.isNotBlank()) append("做到：${done.trim()}")
            if (blocker.isNotBlank()) append("；卡在：${blocker.trim()}")
        }
    }
}
