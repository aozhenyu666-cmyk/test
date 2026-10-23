package com.yishou.app.companion.core

/** 同一问的幂等键。重试、晚到的旧回调都靠它挡成一行。 */
object CompanionKeys {
    fun turn(sessionId: Long, turnId: Long): String = "$sessionId-$turnId"
}

/**
 * 一条记录够不够格变成「下次再练」。
 *
 * 三条硬规矩：
 * 1. **没答、没判成、跳过，一律不进候选**——跳过不等于错，别硬凑成错题；
 * 2. 有差值才有复习价值，差值空的不进；
 * 3. 残局只接受有唯一来源和明确破绽的内容，视频陪练的一问**不进残局**，只进回响候选。
 */
data class RecallCandidate(
    val question: String,
    val delta: String,
    val evidenceIds: List<Long>,
    /** 用户开启了「保留训练卡图片」才会有值 */
    val imagePath: String?,
)

object RecallPlan {

    /** verdict 用存库的字符串，和 data/CompanionTurnRow 一致 */
    fun candidate(
        verdict: String,
        question: String,
        delta: String,
        evidenceIds: String,
        imagePath: String? = null,
    ): RecallCandidate? {
        if (verdict !in JUDGED) return null
        if (question.isBlank() || delta.isBlank()) return null
        val ids = evidenceIds.split(',')
            .mapNotNull { it.trim().toLongOrNull() }
        return RecallCandidate(question.trim(), delta.trim(), ids, imagePath)
    }

    /**
     * 视频陪练的一问**不进残局**：残局要有唯一 sourceKey 和能一句话说清的破绽，
     * 视频里的追问往往依赖上下文，硬塞进残局会变成看不懂的怪题。
     * 所以这里没有「变成残局」的方法——想进残局得另外满足残局自己的条件。
     */

    /** 跳过的、没判成的、没答的，都不是答错 */
    fun countedAsWrong(verdict: String): Boolean = verdict == "MISS"

    private val JUDGED = setOf("HIT", "NEAR", "MISS")
}

/** 删记录时要一起清掉的附件路径 */
object AttachmentCleanup {

    fun of(imagePaths: List<String?>): List<String> =
        imagePaths.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
