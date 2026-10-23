package com.yishou.app.companion.android

import android.content.Context
import com.yishou.app.companion.core.AttemptMemory
import com.yishou.app.companion.core.CompanionKeys
import com.yishou.app.companion.core.ThinkMove
import com.yishou.app.companion.core.Verdict
import com.yishou.app.data.AppDatabase
import com.yishou.app.data.CompanionSessionRow
import com.yishou.app.data.CompanionTurnRow
import com.yishou.app.log.RunLog
import java.io.File

/**
 * 视频陪练的记录口。
 *
 * 三件事在这里守住：
 * 1. **幂等**：同一问用 dedupeKey 挡成一行；答案只写第一次，重试和晚到的旧回调都不会多记一笔；
 * 2. **不冒充错题**：跳过、没判成写 NONE / SKIP，账上算「记了一笔」，不算「没接住」；
 * 3. **删得干净**：删一场时把关联的卡片图片一起删掉，不留孤儿文件。
 */
class CompanionStore(private val context: Context, private val db: AppDatabase) {

    private val dao get() = db.companion()

    suspend fun openSession(goal: String, minutes: Int, gear: String, pkg: String): Long =
        dao.openSession(
            CompanionSessionRow(
                startedAt = System.currentTimeMillis(),
                goal = goal.trim(),
                minutes = minutes,
                gear = gear,
                pkg = pkg,
            ),
        )

    suspend fun closeSession(id: Long, materialSec: Int, asked: Int, missed: Int) =
        dao.closeSession(id, System.currentTimeMillis(), materialSec, asked, missed)

    /** 问出去就落一行（还没答）。返回 false 表示这一问之前已经记过了。 */
    suspend fun asked(
        sessionId: Long,
        turnId: Long,
        move: ThinkMove,
        question: String,
        criteria: String,
        evidenceIds: List<Long>,
        sourceText: String,
        epoch: Int,
        imagePath: String? = null,
    ): Boolean {
        val row = CompanionTurnRow(
            sessionId = sessionId,
            turnId = turnId,
            askedAt = System.currentTimeMillis(),
            move = move.label,
            question = question.trim(),
            criteria = criteria.trim(),
            evidenceIds = evidenceIds.joinToString(","),
            sourceText = sourceText.take(2_000),
            dedupeKey = CompanionKeys.turn(sessionId, turnId),
            epoch = epoch,
            imagePath = imagePath,
        )
        return dao.insertTurn(row) > 0
    }

    /** 答了：只写第一次；返回 true 表示这次真的写进去了 */
    suspend fun answered(sessionId: Long, turnId: Long, answer: String, verdict: Verdict, delta: String): Boolean =
        dao.answer(
            CompanionKeys.turn(sessionId, turnId),
            answer.trim(),
            verdict.name,
            delta.trim(),
            System.currentTimeMillis(),
        ) > 0

    /** 给下一问用的旧差值：最近几条没到／差一点的 */
    suspend fun weakMemory(n: Int = 5): List<AttemptMemory> =
        dao.recentWeak(n).mapNotNull { row ->
            val move = ThinkMove.entries.firstOrNull { it.label == row.move } ?: return@mapNotNull null
            val verdict = runCatching { Verdict.valueOf(row.verdict) }.getOrDefault(Verdict.NONE)
            AttemptMemory(move, row.answeredAt ?: row.askedAt, verdict, row.delta)
        }

    /** 删一场，连附件一起删。返回删掉的文件数。 */
    suspend fun deleteSession(sessionId: Long): Int {
        val paths = dao.imagePaths(sessionId)
        dao.clearTurns(sessionId)
        dao.clearSession(sessionId)
        var removed = 0
        paths.forEach { p ->
            val f = File(p)
            if (f.exists() && f.delete()) removed++ else if (f.exists()) RunLog.e("陪练记录", "删不掉附件：$p")
        }
        return removed
    }
}
