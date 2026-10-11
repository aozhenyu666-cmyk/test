package com.yishou.app.coach

import com.yishou.app.data.ChainRecall
import com.yishou.app.data.MoveKind
import com.yishou.app.data.MoveVerdict
import com.yishou.app.data.SetMove
import com.yishou.app.data.SetStatus
import com.yishou.app.data.TrainSet

/** 组怎么往下走、帮助怎么撤：都是纯规则，方便测试。 */
object CoachPlan {
    const val END = "end"
    /** 一手最多尝试几次：首次加两次重做，第三次还不过就给出正确的这一步 */
    const val MAX_ATTEMPTS = 3
    const val MAX_LEVEL = 3
    /** 能当主练的面（第 6 面回响由回响页练） */
    val FACES = 1..5

    /** 这一手过了以后，下一手是什么。 */
    fun nextKind(move: SetMove, setMoves: Int): String = when {
        move.kind == MoveKind.VERIFY -> END
        move.idx + 1 >= setMoves -> MoveKind.VERIFY
        else -> MoveKind.RELAY
    }

    fun giveAnswer(move: SetMove): Boolean = move.attempt >= MAX_ATTEMPTS

    /** 结论链：过了的、或者教练给出了正确这一步的，按顺序 */
    fun chain(moves: List<SetMove>): List<String> = moves
        .filter { (it.verdict == MoveVerdict.PASS || it.verdict == MoveVerdict.SHOWN) && it.conclusion.isNotBlank() }
        .map { it.conclusion }

    /** 独立：第一次就过，一点帮助没用 */
    fun independent(m: SetMove): Boolean = m.verdict == MoveVerdict.PASS && m.attempt == 1 && m.help == 0

    /**
     * 帮助怎么撤：独立验证那一手独立做对，下一组少给一级帮助；
     * 验证没做对（教练给了答案）就多给一级；有帮助或改过才对，保持。
     */
    fun nextLevel(level: Int, verify: SetMove?): Int = when {
        verify == null -> level
        independent(verify) -> (level + 1).coerceAtMost(MAX_LEVEL)
        verify.verdict == MoveVerdict.SHOWN -> (level - 1).coerceAtLeast(0)
        else -> level
    }

    /**
     * 下一组主练哪一面：上一组没走完或验证没独立做对，就接着练同一面；
     * 否则挑等级最低的面，同级里挑最近练得少的。
     */
    fun suggestFace(levels: Map<Int, Int>, recent: List<TrainSet>): Int {
        val last = recent.maxByOrNull { it.startedAt }
        if (last != null && last.face in FACES && (last.status == SetStatus.DROPPED || last.verified == 0)) return last.face
        val counts = recent.groupingBy { it.face }.eachCount()
        return FACES.minWith(compareBy<Int> { levels[it] ?: 0 }.thenBy { counts[it] ?: 0 }.thenBy { it })
    }
}

/** 进步页的三个主数，加参与记录。比例为 null 表示还没有数据。 */
data class CoachStats(
    val sets: Int,
    /** 独立完成：验证手独立做对 / 走到验证的组 */
    val independent: Int,
    val verified: Int,
    /** 连续接力：接力手第一次就对 / 接力手 */
    val relayFirst: Int,
    val relays: Int,
    /** 回来复述结论链：说对的节数 / 总节数 */
    val recalled: Int,
    val recallTotal: Int,
    /** 改过以后对了 / 被要求改的手 */
    val fixed: Int,
    val redone: Int,
    /** 用了帮助的手 */
    val helped: Int,
    val moves: Int,
) {
    companion object {
        fun of(sets: List<TrainSet>, moves: List<SetMove>, recalls: List<ChainRecall>): CoachStats {
            // 每一手（同一组同一 idx）可能有好几次尝试，按手分组
            val byMove = moves.groupBy { it.setId to it.idx }.values.map { tries -> tries.sortedBy { it.attempt } }
            val judged = byMove.filter { tries -> tries.any { it.verdict != MoveVerdict.PENDING && it.verdict != MoveVerdict.UNCLEAR } }
            val verifies = judged.filter { it.first().kind == MoveKind.VERIFY }
            val relays = judged.filter { it.first().kind == MoveKind.RELAY }
            val redone = judged.filter { tries -> tries.any { it.verdict == MoveVerdict.REDO } }
            return CoachStats(
                sets = sets.count { it.status == SetStatus.DONE },
                independent = verifies.count { tries -> tries.any { CoachPlan.independent(it) } },
                verified = verifies.size,
                relayFirst = relays.count { tries -> tries.first().let { it.verdict == MoveVerdict.PASS && it.attempt == 1 } },
                relays = relays.size,
                recalled = recalls.sumOf { it.recalled },
                recallTotal = recalls.sumOf { it.total },
                fixed = redone.count { tries -> tries.any { it.verdict == MoveVerdict.PASS } },
                redone = redone.size,
                helped = judged.count { tries -> tries.any { it.help > 0 } },
                moves = judged.size,
            )
        }

        fun ratio(a: Int, b: Int): String = if (b == 0) "-" else "$a/$b"
    }
}
