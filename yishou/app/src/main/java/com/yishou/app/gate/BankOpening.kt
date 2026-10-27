package com.yishou.app.gate

import com.yishou.app.judge.LockedQ
import com.yishou.app.reflex.*

/** 破冰必须挑别的题，不能提前把本题缩成两个选项。 */
object BankOpening {
    fun ice(currentId: String, others: List<LockedQ>, seed: Long): Ice? =
        others.filter { it.id != currentId }.shuffled(kotlin.random.Random(seed)).firstNotNullOfOrNull { Icebreaker.from(it, seed) }
    fun first(q: LockedQ, state: FadeState): LockedQ = if (state.level == FadeLevel.ASK_FIRST) Fade.askFirstQ(q) else q
    fun hints(q: LockedQ, state: FadeState): List<String> = if (state.level == FadeLevel.FEED) Fade.feedHints(q) else emptyList()
}
