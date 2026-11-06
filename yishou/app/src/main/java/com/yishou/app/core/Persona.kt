package com.yishou.app.core

/**
 * 人格与交流风格：同一件事，用不同语气说。
 *
 * 约束：
 *  - 不奉承、不无意义赞同、不总结用户的话；
 *  - 一次只问一个动作；
 *  - 语气可配置，但不改变事实和边界。
 *
 * 纯逻辑，不碰 Android，可单测。
 */
data class PersonaConfig(
    val tone: Tone = Tone.BALANCED,
    /** 同一话题最多追问几次；超过就交给退避。 */
    val maxFollowUps: Int = 2,
) {
    enum class Tone { BALANCED, DIRECT, GENTLE, BRIEF }

    fun sane(): PersonaConfig = copy(maxFollowUps = maxFollowUps.coerceIn(0, 3))
}

object Persona {
    fun nudge(
        ctx: CompanionContext,
        appLabel: String,
        minutes: Int,
        persona: PersonaConfig = PersonaConfig(),
    ): String {
        val m = minutes.coerceAtLeast(1)
        val app = appLabel.ifBlank { "这个应用" }
        val last = ctx.recentConclusions.firstOrNull()?.trim().orEmpty()
        if (last.isNotBlank()) return recall(last, app, m, persona.tone)
        val stuck = ctx.stuck?.trim().orEmpty()
        if (stuck.length >= 4) return stuckLine(stuck, app, m, persona.tone)
        val task = ctx.taskTitle?.trim().orEmpty()
        if (task.isNotBlank()) return taskLine(task, app, m, persona.tone)
        return restLine(app, m, persona.tone)
    }

    fun topic(title: String, persona: PersonaConfig = PersonaConfig()): String {
        val t = title.ifBlank { "上次那件事" }.take(30)
        return when (persona.tone) {
            PersonaConfig.Tone.BALANCED -> "上次你说「$t」——现在到哪一步了？"
            PersonaConfig.Tone.DIRECT -> "「$t」到哪一步了？"
            PersonaConfig.Tone.GENTLE -> "上次你说的「$t」，现在进展到哪了？"
            PersonaConfig.Tone.BRIEF -> "「$t」？"
        }
    }

    private fun recall(last: String, app: String, m: Int, tone: PersonaConfig.Tone): String {
        val t = last.take(24)
        return when (tone) {
            PersonaConfig.Tone.BALANCED ->
                "上次你说「$t」。现在又在$app 待了 $m 分钟——这次还是休息，还是回去？"
            PersonaConfig.Tone.DIRECT ->
                "上次你说「$t」。又在$app $m 分钟。回去，还是休息？"
            PersonaConfig.Tone.GENTLE ->
                "上次你说「$t」。现在又在$app 待了 $m 分钟，要不要先休息，还是回去做 15 分钟？"
            PersonaConfig.Tone.BRIEF ->
                "$app｜$m 分钟｜「${last.take(12)}」→ 回去还是休息？"
        }
    }

    private fun stuckLine(stuck: String, app: String, m: Int, tone: PersonaConfig.Tone): String {
        val s = stuck.take(24)
        return when (tone) {
            PersonaConfig.Tone.BALANCED ->
                "你说卡在「$s」。已经在$app 待了 $m 分钟——休息，还是回去做 15 分钟？"
            PersonaConfig.Tone.DIRECT ->
                "卡在「$s」。$app 已 $m 分钟。回去做 15 分钟，还是休息？"
            PersonaConfig.Tone.GENTLE ->
                "你说卡在「$s」。已经在$app $m 分钟了，要不要先回去试 15 分钟，或者休息？"
            PersonaConfig.Tone.BRIEF ->
                "「$s」｜$app $m 分钟｜回去/休息？"
        }
    }

    private fun taskLine(task: String, app: String, m: Int, tone: PersonaConfig.Tone): String {
        val t = task.take(20)
        return when (tone) {
            PersonaConfig.Tone.BALANCED ->
                "「$t」还没推进。你在$app 待了 $m 分钟——现在回去，还是定个时间？"
            PersonaConfig.Tone.DIRECT ->
                "「$t」没推进。$app $m 分钟。回去，还是定个时间？"
            PersonaConfig.Tone.GENTLE ->
                "「$t」还没推进。你在$app 待了 $m 分钟，要不要现在回去，还是定个时间？"
            PersonaConfig.Tone.BRIEF ->
                "「$t」｜$app $m 分钟｜回去/定时？"
        }
    }

    private fun restLine(app: String, m: Int, tone: PersonaConfig.Tone): String = when (tone) {
        PersonaConfig.Tone.BALANCED -> "你在$app 待了 $m 分钟。这一趟是休息，还是该回去了？"
        PersonaConfig.Tone.DIRECT -> "$app 已 $m 分钟。休息，还是回去？"
        PersonaConfig.Tone.GENTLE -> "你在$app 待了 $m 分钟了。要不要休息一下，还是回去？"
        PersonaConfig.Tone.BRIEF -> "$app $m 分钟｜休息/回去？"
    }
}
