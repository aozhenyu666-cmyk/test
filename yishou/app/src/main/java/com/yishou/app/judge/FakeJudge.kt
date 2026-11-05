package com.yishou.app.judge

/** 只给测试用的假判卷：连续字匹配，不证明语义正确。正式入口用 LlmJudge（YishouApp.judge）。 */
class FakeJudge : Judge {
    override suspend fun judge(q: LockedQ, answer: String, trace: ProbeTrace, seen: List<String>): Verdict {
        val excluded = trace.pastedRanges.flatMap { it.toList() }.toSet()
        val hits = q.points.associate { p ->
            val quote = p.criterion.windowed(4).firstOrNull { fragment ->
                fragment.all { it.isLetterOrDigit() } && answer.windowed(4).withIndex().any { (index, value) ->
                    value == fragment && (index until index + 4).none { it in excluded }
                }
            }
            p.id to quote
        }
        return Verdict(hits, false, q.need in 1..q.points.size && hits.values.count { it != null } >= q.need, "测试判卷")
    }
}
