package com.yishou.app.ui

import com.yishou.app.YishouApp
import com.yishou.app.data.StrandRow
import com.yishou.app.gate.GateFlow
import com.yishou.app.thread.*
import android.os.SystemClock
import org.json.JSONObject

/** 页面和入口共用；所有状态变更先落库，再继续下一问。 */
class StrandWork(private val app: YishouApp) {
    private val dao = app.database.strands()
    private val store = StrandStore(dao)
    var strand: Strand? = null; private set
    var flow: GateFlow? = null; private set
    var review: TurnReview? = null; private set
    var error: String? = null; private set

    suspend fun load(s: Strand) {
        strand = s
        review = dao.get(s.id)?.let { JSONObject(it.json).optJSONObject("review") }?.let(TurnReview::parse)
        if (review == null) serve()
    }
    suspend fun open() { store.open()?.let { load(it) } }
    suspend fun serve() {
        val s = strand ?: return
        if (s.done || review != null) return
        val p = StrandEngine.serve(s, app.asker("线程出题"), System.currentTimeMillis())
        if (p == null) { error = "出题的模型暂时不在"; return }
        strand = store.save(s.copy(pending = p))
        flow = GateFlow(p.q) { SystemClock.elapsedRealtime() }
        error = null
    }
    suspend fun submit() {
        val s = strand ?: return
        val f = flow ?: return
        f.submit(app.judge)
        val v = f.verdict ?: return
        if (v.unavailable) { error = "判卷暂时不在，这次不算"; return }
        val t = f.trace ?: return
        val next = StrandEngine.apply(s, f.answer, v, System.currentTimeMillis(), Handoff.parse(f.answer)?.conclusion)
        val rev = TurnReview(f.question, f.answer, t, v, s.pending?.kind == MoveKind.PULL_BACK)
        // 推进与复盘一次写入；最终答案也不会在写盘前显示完成。
        dao.update(StrandRow(next.id, next.toJson().put("review", rev.json()).toString(), next.done, next.updatedAt))
        strand = next; review = rev; error = null
    }
    suspend fun acknowledge() {
        val s = strand ?: return
        dao.update(StrandRow(s.id, s.toJson().toString(), s.done, s.updatedAt))
        review = null; flow = null
    }
    suspend fun next() { acknowledge(); serve() }
    suspend fun returning() {
        val s = strand ?: return
        val next = StrandEngine.startReturn(s, System.currentTimeMillis())
        dao.update(StrandRow(next.id, next.toJson().toString(), next.done, next.updatedAt))
        review = null; strand = next; flow = null; serve()
    }
    suspend fun split(): String {
        val s = strand ?: return ""
        val (old, draft) = StrandEngine.splitOff(s, System.currentTimeMillis())
        dao.update(StrandRow(old.id, old.toJson().toString(), old.done, old.updatedAt))
        strand = null; flow = null; review = null
        return draft
    }
}
