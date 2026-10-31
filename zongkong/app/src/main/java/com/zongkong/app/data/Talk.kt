package com.zongkong.app.data

import com.zongkong.app.ZkApp
import com.zongkong.core.DayClock
import com.zongkong.core.Openers
import com.zongkong.core.YishouLedger
import com.zongkong.core.plan.Plans
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus

/** 话头：从总控此刻的状况（主线、当前一步、一手没接住的题、严管）拼出去 GPT / Gemini 的开场白。 */
object Talk {
    fun openers(app: ZkApp, now: Long = System.currentTimeMillis()): List<Openers.Opener> {
        val store = app.store
        val zone = store.zone
        val status = store.status(now)
        val work = store.work.value
        val main = Plans.mainAction(store.plans.value, work, now, zone)
        val cur = Focus.current(work, now, zone, store.mainKey(now))
        val from = DayClock.startOf(DayClock.date(now, zone), zone)
        val today = YishouLedger.between(store.yishouBook.value, from, now + 1)
        val misses = today.filter { it.miss || it.mark == com.zongkong.core.YishouEntry.NEAR }.map { e ->
            buildString {
                append("（").append(e.name).append("）").append(e.what.take(80))
                if (e.said.isNotBlank()) append("｜我答：").append(e.said.take(60))
                if (e.note.isNotBlank()) append("｜判：").append(e.note.take(60))
            }
        }
        return Openers.build(
            Openers.Ctx(
                headline = status.reasons.firstOrNull()?.text ?: status.headline,
                strict = status.strict,
                mainTitle = main?.title.orEmpty(),
                stepTitle = cur.action?.title.orEmpty(),
                stepNext = cur.action?.get(F.BREAK).orEmpty(),
                misses = misses,
                yishouLine = if (today.isEmpty()) "" else YishouLedger.line(today),
                lastJudge = cur.thread?.get(F.JUDGE).orEmpty(),
                hour = java.time.Instant.ofEpochMilli(now).atZone(zone).hour,
            ),
        )
    }
}
