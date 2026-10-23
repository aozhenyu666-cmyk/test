package com.yishou.app.companion.core

/**
 * 一条带出处的说法。**没有出处的说法不算数**：模型很容易把「一般常识」写成「这段材料说的」，
 * 一旦进了记忆和回响，下次就会拿假依据去问人。
 */
data class Claim(val text: String, val evidenceIds: List<Long>) {
    val cited: Boolean get() = evidenceIds.isNotEmpty()
}

/** 一段材料的摘要：主张、条件、依据不足的地方 */
data class SegmentSummary(
    val segmentId: Long,
    val claims: List<Claim>,
    val conditions: List<Claim>,
    val openQuestions: List<Claim>,
) {
    /** 一条引用都没有的摘要不用，宁可让上层重新做一次或者直接跳过 */
    val usable: Boolean get() = claims.isNotEmpty() && claims.all { it.cited }

    fun allClaims(): List<Claim> = claims + conditions + openQuestions
}

/**
 * 解析分段摘要。约定模型输出三行一组，冒号后是说法，竖线后是引用到的证据编号：
 *
 * ```
 * 主张：他从样本 A 推到全部人群｜[11,12]
 * 条件：样本必须是随机抽的｜[13]
 * 未解：抽样方法始终没说｜[11]
 * ```
 *
 * 引用不到的（编号不在允许集合里）直接丢掉那一条；一条都不剩就返回 null——
 * 解析失败时上层按「没有摘要」处理，不拿半截当完整。
 */
object SummaryParse {

    private val LINE = Regex("""^\s*(主张|条件|未解|未解问题)\s*[：:]\s*(.+?)\s*(?:[｜|]\s*\[([0-9,\s]*)\])?\s*$""")

    fun parse(raw: String, allowedIds: Set<Long>, segmentId: Long): SegmentSummary? {
        val claims = ArrayList<Claim>()
        val conditions = ArrayList<Claim>()
        val open = ArrayList<Claim>()
        raw.lineSequence().forEach { line ->
            val m = LINE.find(line) ?: return@forEach
            val kind = m.groupValues[1]
            val text = m.groupValues[2].trim()
            if (text.isEmpty()) return@forEach
            val ids = m.groupValues.getOrNull(3).orEmpty()
                .split(',')
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { it in allowedIds }
            val claim = Claim(text, ids)
            if (!claim.cited) return@forEach
            when (kind) {
                "主张" -> claims += claim
                "条件" -> conditions += claim
                else -> open += claim
            }
        }
        if (claims.isEmpty() && conditions.isEmpty() && open.isEmpty()) return null
        return SegmentSummary(segmentId, claims, conditions, open)
    }
}

/** 裁过的上下文：能用哪几条证据、丢了多少 */
data class FittedContext(
    val text: String,
    val usedIds: List<Long>,
    val segments: List<Segment>,
    val droppedSegments: Int,
    val truncated: Boolean,
)

/**
 * 滚动上下文的预算。
 *
 * 一次请求不能把 180 秒全塞进去：又贵又慢，还容易让模型抓不住重点。
 * 从最新往回取到预算用完，被丢掉的部分明确标出来（界面上也要说「更早的没带上」），
 * 不能悄悄裁掉再让模型以为看到的是全部。
 */
class ContextBudget(private val maxChars: Int = 1_800) {

    fun fit(slice: ContextSlice): FittedContext {
        if (slice.segments.isEmpty()) {
            return FittedContext("", emptyList(), emptyList(), 0, false)
        }
        val picked = ArrayList<Segment>()
        var used = 0
        var truncated = false
        for (seg in slice.segments.sortedByDescending { it.toMs }) {
            val cost = seg.text.length + 12
            if (used + cost > maxChars) {
                truncated = true
                break
            }
            picked += seg
            used += cost
        }
        picked.reverse()
        return FittedContext(
            text = picked.joinToString("\n") { seg(it) },
            usedIds = picked.flatMap { it.evidenceIds },
            segments = picked,
            droppedSegments = slice.segments.size - picked.size,
            truncated = truncated,
        )
    }

    private fun seg(s: Segment): String = when (s.channel) {
        Channel.VIDEO_AUDIO -> s.text
        Channel.USER_VOICE -> "（我说的）" + s.text
        Channel.SCREEN -> "（画面）" + s.text
        Channel.MEMO -> "（目标）" + s.text
    }
}
