package com.yishou.app.summary

import com.yishou.app.card.Card
import com.yishou.app.data.AppDatabase

/** 从各张表里把一天的账读出来（只读，不另存一份） */
class DayLedger(private val db: AppDatabase) {

    suspend fun between(from: Long, to: Long): List<LedgerEntry> {
        val cardDao = db.cards()
        val questions = HashMap<Long, String>()
        suspend fun question(cardId: Long): String = questions.getOrPut(cardId) {
            cardDao.getCard(cardId)?.let { row -> runCatching { Card.parse(org.json.JSONObject(row.json)).question.take(40) }.getOrNull() }.orEmpty()
        }
        val titles = HashMap<Long, String>()
        val out = mutableListOf<LedgerEntry>()
        cardDao.attemptsBetween(from, to).forEach { a -> LedgerFrom.card(a, question(a.cardId))?.let(out::add) }
        db.think().drillsSince(from).filter { it.createdAt < to }.forEach { d ->
            val title = d.materialId?.let { id -> titles.getOrPut(id) { db.think().getMaterial(id)?.title.orEmpty() } }.orEmpty()
            LedgerFrom.drill(d, title)?.let(out::add)
        }
        db.soups().between(from, to).forEach { out += LedgerFrom.soup(it, question(it.cardId)) }
        db.endgames().playedBetween(from, to).forEach { e -> LedgerFrom.endgame(e)?.let(out::add) }
        db.scrollQuizzes().touched(from, to).forEach { q ->
            q.answeredAt?.takeIf { it in from until to }?.let { LedgerFrom.scroll(q)?.let(out::add) }
            q.redoAt?.takeIf { it in from until to }?.let { LedgerFrom.scrollRedo(q)?.let(out::add) }
        }
        db.partners().startedBetween(from, to).forEach { out += LedgerFrom.partner(it) }
        db.lines().movesBetween(from, to).forEach { m -> LedgerFrom.move(m)?.let(out::add) }
        return out.sortedBy { it.at }
    }
}
