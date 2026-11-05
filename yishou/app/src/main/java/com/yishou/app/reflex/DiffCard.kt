package com.yishou.app.reflex

import com.yishou.app.judge.LockedQ
import com.yishou.app.judge.ProbeTrace
import com.yishou.app.judge.Verdict

/**
 * 思维对比卡：判完不夸也不训，只并排摆两条路——
 * 左边是你刚才的脑子怎么走的（开口前愣了多久、在哪停住、删改了几次、哪些要点说到了），
 * 右边是最短路径（锁题里的要点，按顺序）。看完填一句触发器才算这一手入库。
 */
data class DiffCard(val yours: List<String>, val shortest: List<String>, val reference: String)

object DiffCards {

    fun build(q: LockedQ, answer: String, trace: ProbeTrace, v: Verdict): DiffCard {
        val yours = mutableListOf<String>()
        yours += "看到题后 ${secs(trace.ttftMs)} 才开口" + if (trace.ttftMs < 3_000) "（太快，多半没想）" else ""
        // 按停顿把回答切开，每段后面写停了多久
        var from = 0
        trace.pauses.sortedBy { it.atChar }.forEach { p ->
            val to = p.atChar.coerceIn(from, answer.length)
            if (to > from) yours += "“${answer.substring(from, to).trim().take(40)}”"
            yours += "（在这里停了 ${secs(p.millis)}）"
            from = to
        }
        if (from < answer.length) yours += "“${answer.substring(from).trim().take(60)}”"
        if (trace.deletes > 0) yours += "删改了 ${trace.deletes} 次"
        val shortest = q.points.map { p ->
            val hit = v.hits[p.id]
            if (hit != null) "✓ ${p.criterion}（你说的：“$hit”）" else "✗ ${p.criterion}"
        }
        return DiffCard(yours, shortest, q.reference)
    }

    private fun secs(ms: Long) = if (ms < 60_000) "${(ms + 500) / 1000} 秒" else "${ms / 60_000} 分 ${(ms % 60_000 + 500) / 1000} 秒"
}
