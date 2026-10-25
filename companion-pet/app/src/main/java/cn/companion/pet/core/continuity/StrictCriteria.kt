package cn.companion.pet.core.continuity

/**
 * 严格回合的验收标准：按当前问题生成，不写死某类题（原实现固定成“作者转折”）。
 * 素材不在手边（重启恢复后不保存屏幕原文）时，不要求引用原文。
 */
object StrictCriteria {
    fun forQuestion(question: String, hasMaterial: Boolean): List<String> {
        val q = question.trim().take(60)
        val first = if (q.isEmpty()) "直接回应这个问题" else "直接回应这个问题：「" + q + "」"
        val second = if (hasMaterial) "给出素材里的一处具体依据（原句、数据或细节）" else "说出你的依据或理由（素材原文没保存，不要求引用）"
        return listOf(first, second)
    }
}
