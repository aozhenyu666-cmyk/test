package com.yishou.app.gate

import com.yishou.app.judge.LockedQ
import com.yishou.app.reflex.*

/**
 * 入口开题：先给提示还是先自己说（退坡决定），提示只给没记住这类题的人。
 *
 * 这里原来还有一屏“直觉看一眼，哪个更像鬼扯”——两个选项来自别的题，脱离文段没得判，
 * 而且点哪个都不影响后面，等于白占一次注意力。已经去掉。
 */
object BankOpening {
    fun first(q: LockedQ, state: FadeState): LockedQ = if (state.level == FadeLevel.ASK_FIRST) Fade.askFirstQ(q) else q
    fun hints(q: LockedQ, state: FadeState): List<String> = if (state.level == FadeLevel.FEED) Fade.feedHints(q) else emptyList()
}
