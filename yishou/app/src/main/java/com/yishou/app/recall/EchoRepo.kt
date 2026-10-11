package com.yishou.app.recall

import com.yishou.app.YishouApp
import com.yishou.app.data.Recall
import com.yishou.app.window.WindowClock
import java.time.ZoneId

/** 回响的读写：今天该想起哪几手，想起之后记一笔。 */
class EchoRepo(private val app: YishouApp) {
    private val dao = app.database.dao()
    private val practice = app.database.practice()
    private val zone get() = ZoneId.systemDefault()

    suspend fun due(): List<EchoItem> {
        val now = System.currentTimeMillis()
        val today = WindowClock.today(now, zone)
        val from = WindowClock.startOfDay(today.minusDays(14), zone)
        val rounds = dao.roundsBetween(from, now + 1)
        val recalls = practice.recallsSince(from)
        val todayStart = WindowClock.startOfDay(today, zone)
        return Echo.due(
            rounds,
            recalls.map { it.roundId to it.stage }.toSet(),
            recalls.filter { it.doneAt >= todayStart }.map { it.stage }.toSet(),
            today,
            zone,
        )
    }

    suspend fun record(item: EchoItem, said: String, result: Int) {
        practice.upsertRecall(Recall(item.round.id, item.stage, System.currentTimeMillis(), result, said.trim()))
    }
}
