package com.yishou.app.summary

import com.yishou.app.data.CardAttempt
import com.yishou.app.data.CompanionTurnRow
import com.yishou.app.data.DrillRow
import com.yishou.app.data.Endgame
import com.yishou.app.data.LineVerdict
import com.yishou.app.data.Move
import com.yishou.app.data.PartnerSession
import com.yishou.app.data.ScrollQuizRow
import com.yishou.app.data.Soup
import com.yishou.app.think.DrillStats

/** 各张表里的一行 → 账上的一手。纯函数，不碰数据库。 */
object LedgerFrom {

    fun card(a: CardAttempt, question: String): LedgerEntry? {
        if (!a.verified) return null
        val mark = when {
            a.firstCorrect && a.help == 0 -> LedgerMark.HIT
            a.finalCorrect -> LedgerMark.NEAR
            else -> LedgerMark.MISS
        }
        return LedgerEntry(a.createdAt, LedgerKind.CARD, mark, question.ifBlank { "一道${a.skill}题" }, note = a.cause)
    }

    fun drill(d: DrillRow, title: String): LedgerEntry? {
        if (d.disputed) return null
        val mark = when {
            d.score < 0 -> LedgerMark.NONE
            DrillStats.good(d) -> LedgerMark.HIT
            d.score >= 35 -> LedgerMark.NEAR
            else -> LedgerMark.MISS
        }
        val lens = com.yishou.app.think.Lens.entries.firstOrNull { it.name == d.lens }?.label ?: d.lens
        return LedgerEntry(d.createdAt, LedgerKind.DRILL, mark, "$lens · ${title.ifBlank { "一段素材" }}", d.answer, d.takeaway)
    }

    fun soup(s: Soup, question: String): LedgerEntry {
        val mark = when {
            s.asks < 0 -> LedgerMark.NONE
            s.solved -> LedgerMark.HIT
            s.correct == true -> LedgerMark.NEAR
            else -> LedgerMark.MISS
        }
        val note = if (s.asks >= 0) "问了 ${s.asks} 问" else "去 ChatGPT 玩的"
        return LedgerEntry(s.createdAt, LedgerKind.SOUP, mark, question.ifBlank { "一锅汤" }, note = note)
    }

    /** 残局只记最后一次走的那一下（表里只留了这一下） */
    fun endgame(e: Endgame): LedgerEntry? {
        val at = e.lastAt ?: return null
        return LedgerEntry(at, LedgerKind.ENDGAME, if (e.lastHit) LedgerMark.HIT else LedgerMark.MISS, e.ask.ifBlank { e.text.take(30) }, note = "破绽：${e.crux.take(30)}")
    }

    /** 没回的、跳过的不记 */
    fun scroll(q: ScrollQuizRow): LedgerEntry? {
        val at = q.answeredAt ?: return null
        val mark = when (q.verdict) {
            "HIT" -> LedgerMark.HIT
            "NEAR" -> LedgerMark.NEAR
            "MISS" -> LedgerMark.MISS
            "" -> if (q.answer.isBlank()) return null else LedgerMark.NONE
            else -> return null
        }
        return LedgerEntry(at, LedgerKind.SCROLL, mark, "刷到「${q.seen.ifBlank { "一条" }}」问：${q.question}", q.answer, q.line)
    }

    /** 静下来重想的那一次，单独算一手 */
    fun scrollRedo(q: ScrollQuizRow): LedgerEntry? {
        val at = q.redoAt ?: return null
        val mark = when (q.redoVerdict) {
            "HIT" -> LedgerMark.HIT
            "NEAR" -> LedgerMark.NEAR
            "MISS" -> LedgerMark.MISS
            else -> LedgerMark.NONE
        }
        return LedgerEntry(at, LedgerKind.SCROLL, mark, "重想「${q.seen.ifBlank { "一条" }}」：${q.question}")
    }

    fun partner(p: PartnerSession): LedgerEntry {
        val note = buildString {
            append("在场 ${p.presentMs / 60_000} 分钟")
            if (p.leaves > 0) append("，离开 ${p.leaves} 次")
            if (p.nextStep.isNotBlank()) append("；下一步：").append(p.nextStep)
        }
        val route = when (p.route) {
            "live" -> "Gemini Live"
            "gemini" -> "Gemini"
            else -> "一手语音"
        }
        return LedgerEntry(p.startedAt, LedgerKind.PARTNER, LedgerMark.NONE, "$route 伴读：${p.goal}", p.closeLine, note)
    }

    fun move(m: Move): LedgerEntry? {
        if (m.verdict == LineVerdict.PARKED) return null
        return LedgerEntry(m.createdAt, LedgerKind.LINE, if (m.verdict == LineVerdict.OK) LedgerMark.HIT else LedgerMark.MISS, m.question, m.answer, m.note)
    }

    /**
     * 视频陪练的一问。
     *
     * 没答的不进账；**跳过和没判成都记 NONE，不记 MISS**——识别坏了、网络断了、
     * 用户不想答，这些都不该在账上变成「没接住」。
     */
    fun companionTurn(t: CompanionTurnRow): LedgerEntry? {
        val at = t.answeredAt ?: return null
        val mark = when (t.verdict) {
            "HIT" -> LedgerMark.HIT
            "NEAR" -> LedgerMark.NEAR
            "MISS" -> LedgerMark.MISS
            else -> LedgerMark.NONE
        }
        val what = "视频陪练·${t.move}：${t.question.ifBlank { "一问" }}"
        return LedgerEntry(at, LedgerKind.VIDEO, mark, what, t.answer, t.delta)
    }
}
