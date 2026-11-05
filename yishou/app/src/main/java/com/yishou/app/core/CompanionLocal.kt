package com.yishou.app.core

/**
 * 没有配置模型时的本地主动交流。
 *
 * 目的不是替代模型，而是让第一阶段的最小闭环在“没填 API”时也能真的跑：
 * 知道你在哪个应用待了多久、有没有未完成的任务、卡在哪，然后用一句话把选择摆出来。
 *
 * 纯逻辑，不碰 Android，可单测。
 */
object CompanionLocal {

    /** 这些词说明用户已经做了选择或给了下一步。 */
    private val choices = listOf(
        "休息", "回去", "现在", "定", "不做", "继续", "停", "关", "睡",
        "走", "开始", "做完", "时间", "离开", "放下", "关掉", "收",
    )

    /** 本地提醒：优先贴上次回答，其次卡点，其次任务，最后才泛泛问。 */
    fun nudge(ctx: CompanionContext, appLabel: String, minutes: Int): String {
        val m = minutes.coerceAtLeast(1)
        val app = appLabel.ifBlank { "这个应用" }

        // 上一次真的回答过什么：下一轮先把它接回来，而不是每次从零问。
        val last = ctx.recentConclusions.firstOrNull()?.trim().orEmpty()
        if (last.isNotBlank()) {
            return "上次你说「${last.take(24)}」。现在又在$app 待了 $m 分钟——这次还是休息，还是回去？"
        }

        val stuck = ctx.stuck?.trim().orEmpty()
        if (stuck.length >= 4) {
            return "你说卡在「${stuck.take(24)}」。已经在$app 待了 $m 分钟——休息，还是回去做 15 分钟？"
        }

        val task = ctx.taskTitle?.trim().orEmpty()
        if (task.isNotBlank()) {
            return "「${task.take(20)}」还没推进。你在$app 待了 $m 分钟——现在回去，还是定个时间？"
        }

        return "你在$app 待了 $m 分钟。这一趟是休息，还是该回去了？"
    }

    /**
     * 本地回答的判定：比模型模式宽，但仍然是“做一个选择”。
     * 只回“嗯/随便”不算；给出选择或一句解释就算。
     */
    fun checkAnswer(text: String, attempt: Int = 0): JudgementCheck {
        val sub = JudgementRules.substance(text)
        if (sub < 2) {
            return JudgementCheck(
                pass = false,
                problem = JudgementProblem.EMPTY,
                hint = "回一句就行：休息 / 现在回去 / 定个时间。",
                reask = attempt <= 0,
            )
        }
        if (choices.any { it in text } || sub >= 6) {
            return JudgementCheck(pass = true, hint = "记下了。")
        }
        return JudgementCheck(
            pass = false,
            problem = JudgementProblem.NO_STANCE,
            hint = "回一句就行：休息 / 现在回去 / 定个时间。",
            reask = attempt <= 0,
        )
    }
}
