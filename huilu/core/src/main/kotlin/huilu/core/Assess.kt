package huilu.core

enum class AppCat { TARGET, DISTRACTOR, NEUTRAL, OTHER }

/** 把"预期"和"观察"放在一起，算出偏差。纯函数，不做任何决定。 */
class Assessor(private val platform: Platform, private val settings: () -> Settings) {

    fun category(pkg: String, action: Action?): AppCat = when {
        action != null && pkg in action.expect.targetApps -> AppCat.TARGET
        pkg in settings().distractors -> AppCat.DISTRACTOR
        platform.isNeutral(pkg) -> AppCat.NEUTRAL
        else -> AppCat.OTHER
    }

    fun deviation(action: Action, obs: Observation): Deviation {
        if (obs.state != SensorState.OK) {
            return Deviation(DevKind.UNKNOWN, reality = "计划：${action.text}｜实际：无法观察（${stateText(obs.state)}）")
        }
        var target = 0L; var distract = 0L; var other = 0L; var neutral = 0L
        val dByApp = HashMap<String, Long>(); val oByApp = HashMap<String, Long>()
        for ((pkg, ms) in obs.apps) when (category(pkg, action)) {
            AppCat.TARGET -> target += ms
            AppCat.DISTRACTOR -> { distract += ms; dByApp[pkg] = ms }
            AppCat.NEUTRAL -> neutral += ms
            AppCat.OTHER -> { other += ms; oByApp[pkg] = ms }
        }
        val idle = obs.idleMs + neutral
        val w = obs.windowMs.coerceAtLeast(1)
        val topD = dByApp.maxByOrNull { it.value }?.key
        val topO = oByApp.maxByOrNull { it.value }?.key
        val onset = topD?.let { p -> obs.firstSeen[p]?.let { ((it - action.startedAt) / 60_000).toInt().coerceAtLeast(0) } }

        val kind = when {
            distract >= 60_000 && distract * 4 >= w -> DevKind.DRIFT
            else -> when (action.expect.effectiveMode) {
                EnvMode.IN_APPS -> when {
                    target * 2 >= (target + other + distract).coerceAtLeast(1) && target > 0 -> DevKind.ON_TRACK
                    other >= idle -> DevKind.OFF_TARGET
                    else -> DevKind.AWAY
                }
                EnvMode.OFF_PHONE -> if ((other + distract) * 10 <= w * 3) DevKind.ON_TRACK else DevKind.OFF_TARGET
                EnvMode.ANY -> DevKind.ON_TRACK
            }
        }
        val parts = mutableListOf<String>()
        if (target > 0) parts += "目标 App ${dur(target)}"
        dByApp.entries.sortedByDescending { it.value }.take(2).forEach { parts += "${platform.label(it.key)} ${dur(it.value)}" }
        oByApp.entries.sortedByDescending { it.value }.take(2).forEach { parts += "${platform.label(it.key)} ${dur(it.value)}" }
        if (idle >= 30_000) parts += "没在用手机 ${dur(idle)}"
        val reality = "计划：${action.text}｜实际：" + (if (parts.isEmpty()) "几乎没有记录" else parts.joinToString("，"))
        return Deviation(kind, target, distract, other, idle, topD, topO, onset, reality)
    }

    fun question(action: Action, trigger: Trigger, dev: Deviation, now: Long, fg: Foreground?): String {
        val a = "「${action.text}」"
        val minute = minuteOf(action, now)
        return when (trigger) {
            Trigger.DRIFT -> {
                val p = fg?.pkg?.let(platform::label) ?: dev.topDistractor?.let(platform::label) ?: "娱乐 App"
                val mins = fg?.let { ((now - it.since) / 60_000).coerceAtLeast(1) } ?: 1
                "原计划$a，但「$p」已经在前台连续 $mins 分钟。发生了什么？"
            }
            Trigger.END -> "$a 计划的 ${action.plannedMin} 分钟到了。${realityTail(dev)}结果如何？"
            Trigger.REST_OVER -> "休息时间到了。回到$a 吗？"
            Trigger.USER -> "$a 进行到第 $minute 分钟。${realityTail(dev)}现在怎么样？"
            Trigger.SCHEDULED -> when (dev.kind) {
                DevKind.ON_TRACK -> if (action.expect.effectiveMode == EnvMode.IN_APPS)
                    "$a 进行到第 $minute 分钟，看起来一直在目标 App 里。实际进展到哪了？"
                    else "$a 进行到第 $minute 分钟。实际做到哪了？"
                DevKind.DRIFT -> "原计划$a，过去这段时间「${dev.topDistractor?.let(platform::label)}」用了 ${dur(dev.distractorMs)}。现在在做什么？"
                DevKind.OFF_TARGET -> "原计划$a，过去这段时间主要在「${dev.topOther?.let(platform::label) ?: "别的 App"}」。这是计划的一部分吗？"
                DevKind.AWAY -> "$a 进行到第 $minute 分钟，手机大部分时间没在用。是在纸上或电脑上做吗？"
                DevKind.UNKNOWN -> "$a 进行到第 $minute 分钟了。现在实际在做什么？"
            }
        }
    }

    private fun realityTail(dev: Deviation): String =
        if (dev.kind == DevKind.UNKNOWN) "" else "（" + dev.reality.substringAfter("实际：") + "）"

    private fun stateText(s: SensorState) = when (s) {
        SensorState.NO_PERMISSION -> "没有使用情况访问权限"
        SensorState.UNAVAILABLE -> "设备不支持"
        SensorState.OK -> ""
    }

    companion object {
        fun minuteOf(action: Action, now: Long): Int = ((now - action.startedAt) / 60_000).toInt().coerceAtLeast(0)

        fun dur(ms: Long): String {
            val s = ms / 1000
            return when {
                s < 60 -> "${s}秒"
                s < 3600 -> "${s / 60}分" + (if (s % 60 >= 10 && s < 600) "${s % 60}秒" else "")
                else -> "${s / 3600}小时${(s % 3600) / 60}分"
            }
        }
    }
}
