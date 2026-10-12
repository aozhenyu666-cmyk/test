package com.yishou.app.card

import com.yishou.app.data.CardAttempt
import com.yishou.app.data.CardDao
import com.yishou.app.data.CardRow
import com.yishou.app.data.CardSource
import com.yishou.app.llm.CardGen
import com.yishou.app.llm.LlmResult
import com.yishou.app.log.RunLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 拿到手的一张题卡：存档行 + 题卡内容 + 这次是不是错题重做 */
data class Dealt(val row: CardRow, val card: Card, val redo: Boolean)

/**
 * 题卡的来源和去处：
 * - 平时在后台备几张核对过答案的题卡，入口思考页一打开就有题，不用等；
 * - 发牌时错题重做优先，其次按掌握度挑小技能；
 * - 做完记一条结果。
 * 出题后让模型不看答案再做一遍，两次答案对得上才算“核对过”；对不上的题不用。
 */
class CardEngine(
    private val dao: CardDao,
    private val gen: CardGen,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val genLock = Mutex()
    private val prefetching = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 最近的做题记录（30 天），算掌握度、重做都用它 */
    suspend fun history(): List<CardAttempt> = dao.attemptsSince(clock() - 30 * DAY)

    /** 发一张。skill 为 null 时按掌握度挑；allowRedo 时错题重做优先。 */
    suspend fun deal(skill: Skill?, allowRedo: Boolean = true): LlmResult<Dealt> {
        val hist = history()
        if (allowRedo) {
            for (id in Progress.dueRedo(hist, clock())) {
                val row = dao.getCard(id) ?: continue
                if (skill != null && row.skill != skill.name) continue
                val card = runCatching { Card.fromJson(row.json) }.getOrNull() ?: continue
                return LlmResult.Ok(Dealt(row, card, redo = true))
            }
        }
        val target = skill ?: Progress.nextSkill(hist)
        val unused = dao.unused()
        val row = unused.firstOrNull { it.skill == target.name } ?: if (skill == null) unused.firstOrNull() else null
        if (row != null) {
            val card = runCatching { Card.fromJson(row.json) }.getOrNull()
            if (card != null) return LlmResult.Ok(Dealt(row, card, redo = false))
        }
        return when (val r = make(target, Progress.difficulty(hist, target))) {
            is LlmResult.Err -> r
            is LlmResult.Ok -> LlmResult.Ok(Dealt(r.value, Card.fromJson(r.value.json), redo = false))
        }
    }

    /**
     * 后台备题：没做过的核对过的题卡少于 min 张就补，先补掌握度最低的几个小技能。
     * 返回补了几张；出错就停下（下次再补）。
     */
    suspend fun prefetch(min: Int = 4): Int {
        // 同一时间只跑一个备题循环；每出一张都单独拿锁，入口思考页要现出题时最多等一张
        if (!prefetching.compareAndSet(false, true)) return 0
        try {
            var added = 0
            repeat(min) {
                val unused = dao.unused()
                if (unused.size >= min) return added
                val hist = history()
                val have = unused.groupingBy { it.skill }.eachCount()
                val skill = Skill.entries.sortedWith(
                    compareBy<Skill> { have[it.name] ?: 0 }.thenBy { Progress.mastery(hist, it) ?: 0.0 },
                ).first()
                when (make(skill, Progress.difficulty(hist, skill))) {
                    is LlmResult.Ok -> added++
                    is LlmResult.Err -> return added
                }
            }
            return added
        } finally {
            prefetching.set(false)
        }
    }

    private suspend fun make(skill: Skill, difficulty: Int): LlmResult<CardRow> = genLock.withLock { makeLocked(skill, difficulty) }

    /** 出一张并核对；对不上就再出一张，还对不上就算了 */
    private suspend fun makeLocked(skill: Skill, difficulty: Int): LlmResult<CardRow> {
        val avoid = dao.recent(8).mapNotNull { runCatching { Card.fromJson(it.json).let { c -> c.sentences.first().take(30) } }.getOrNull() }
        var lastErr: LlmResult.Err? = null
        repeat(2) {
            val card = when (val g = gen.generate(skill, difficulty, avoid)) {
                is LlmResult.Err -> return g
                is LlmResult.Ok -> g.value
            }
            when (val s = gen.solve(card)) {
                is LlmResult.Err -> lastErr = s
                is LlmResult.Ok -> if (s.value == card.answer) {
                    val row = CardRow(skill = skill.name, difficulty = card.difficulty, json = card.toJson(), verified = true, source = CardSource.AI, createdAt = clock())
                    return LlmResult.Ok(row.copy(id = dao.insertCard(row)))
                } else {
                    RunLog.e("出题", "核对没对上（出题 ${Card.letter(card.answer)}，另做 ${Card.letter(s.value)}），重出一张")
                }
            }
        }
        return lastErr ?: LlmResult.Err(com.yishou.app.llm.LlmError.BadFormat("两次出题答案都没核对上"))
    }

    /** 拍下的真题：整理成题卡，先标“未核对”，用户在揭晓时点了官方答案才算数 */
    suspend fun import(text: String): LlmResult<Dealt> = when (val r = gen.structure(text)) {
        is LlmResult.Err -> r
        is LlmResult.Ok -> {
            val row = CardRow(skill = r.value.skill.name, difficulty = r.value.difficulty, json = r.value.toJson(), verified = false, source = CardSource.PHOTO, createdAt = clock())
            LlmResult.Ok(Dealt(row.copy(id = dao.insertCard(row)), r.value, redo = false))
        }
    }

    /**
     * 用户点了官方答案：和整理时的答案一样就直接算核对过；不一样就以官方答案为准
     * （错因表那部分就不可靠了，清掉，多查只看“有没有毛病”）。
     */
    suspend fun confirmKey(d: Dealt, official: Int): Dealt {
        val card = if (official == d.card.answer) d.card else d.card.copy(answer = official, flaws = emptyMap())
        val row = d.row.copy(json = card.toJson(), verified = true)
        dao.updateCard(row)
        return Dealt(row, card, d.redo)
    }

    suspend fun updateAttempt(a: CardAttempt) = dao.updateAttempt(a)

    /** 记一张做完的题卡 */
    suspend fun record(d: Dealt, r: Round, mode: String, level: Int, durationMs: Long): CardAttempt {
        val now = clock()
        if (d.row.usedAt == null) dao.updateCard(d.row.copy(usedAt = now))
        val a = CardAttempt(
            cardId = d.row.id,
            skill = d.card.skill.name,
            difficulty = d.card.difficulty,
            mode = mode,
            level = level,
            bet = r.bet ?: -1,
            conf = r.conf?.label.orEmpty(),
            evidenceOk = r.evidenceOk,
            checks = r.checks.size,
            checksOk = r.checksOk,
            final = r.finalAnswer ?: -1,
            firstCorrect = r.firstCorrect,
            finalCorrect = r.finalCorrect,
            help = r.help,
            cause = r.cause,
            durationMs = durationMs,
            verified = d.row.verified,
            createdAt = now,
        )
        return a.copy(id = dao.insertAttempt(a))
    }

    companion object {
        private const val DAY = 86_400_000L
    }
}
