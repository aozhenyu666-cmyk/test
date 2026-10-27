package com.yishou.app.judge

/** 仅用于开发接线，会被 C1 替换；连续字匹配不证明语义正确。 */
class FakeJudge : Judge {
    override suspend fun judge(q: LockedQ, answer: String, trace: ProbeTrace): Verdict {
        val excluded = trace.pastedRanges.flatMap { it.toList() }.toSet()
        val hits = q.points.associate { p ->
            val quote = p.criterion.windowed(4).firstOrNull { fragment ->
                fragment.all { it.isLetterOrDigit() } && answer.windowed(4).withIndex().any { (index, value) ->
                    value == fragment && (index until index + 4).none { it in excluded }
                }
            }
            p.id to quote
        }
        return Verdict(hits, false, q.need in 1..q.points.size && hits.values.count { it != null } >= q.need, "开发判卷：只检查连续字匹配，等待真实判卷器替换。")
    }
}
