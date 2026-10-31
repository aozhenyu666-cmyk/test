package com.yishou.app.reflex

import com.yishou.app.judge.LockedQ

/**
 * 破冰：开局第一屏两颗大按钮，“直觉看一眼，哪个更像鬼扯？”，5 秒内点。
 * 不算分、不影响放行，只为把注意力钩进来——按下去的阻力比刷视频还低。
 * 有选项的题拿正确项和一个错项；没选项的题不破冰。
 * 注意：传进来的必须是“另一道题”，不能是这次入口要答的那道——不然等于提前把答案缩成二选一。
 */
data class Ice(val left: String, val right: String, val nonsense: Int)

object Icebreaker {
    const val LIMIT_MS = 5_000L

    fun from(q: LockedQ, seed: Long): Ice? {
        if (q.answer !in q.options.indices || q.options.size < 2) return null
        val wrong = q.options.indices.filter { it != q.answer }
        val w = wrong[(seed.mod(wrong.size.toLong())).toInt()]
        val swap = seed.mod(2L) == 1L
        val right = q.options[q.answer]
        val bad = q.options[w]
        return if (swap) Ice(bad, right, 0) else Ice(right, bad, 1)
    }
}
