package com.yishou.app.partner

import com.yishou.app.think.Words

enum class Material { VIDEO, ARTICLE, DRILL, CHESS, OTHER }

enum class Gear(val label: String) {
    ACCOMPANY("陪"), LURE("诱"), STRICT("严"),
}

data class PartnerPlan(
    val material: Material,
    val goal: String,
    val minutes: Int = 25,
    val gear: Gear = Gear.ACCOMPANY,
    /** 从素材池选的一段：语音伙伴就围着这段聊（外部伴读用不到） */
    val source: String = "",
)

object Opening {
    const val RULES = "现在开始伴读。你是坐我旁边的同学，不是助手。别替我总结，先让我自己讲；我讲完你指出我漏了什么，一次一处；隔几分钟问我一个推演题或反例；我走神就直接问我在干什么。说话简短口语。结束时问我一句\"聊出了什么\"。"

    /** 规定词：在 Gemini 的「保存的信息」里存一次长规则，每次开 Live 只说这一句 */
    const val TRIGGER = "一手开练"
    const val STOP_WORD = "收工"

    /** 存进 Gemini「保存的信息」的那段（只存一次）。Live 读不读它，要在你手机上试一次。 */
    fun savedInfo(): String =
        "当我说「$TRIGGER」时，你进入陪练模式，直到我说「$STOP_WORD」：" +
            "你是坐我旁边的同学，不是助手，也不是解说员。不总结、不复述我在看的内容，先让我自己讲。" +
            "每次只问一个问题，二十个字以内，问完就停，等我回答。" +
            "问题轮着从这几个角度来：它想让我信哪一句；靠着哪个没说的前提；接下来会怎样；说的人想从我这拿走什么；什么情况下不成立；要信它得先看到什么证据。" +
            "我答完，先说“接住了”“差一点”或“没接住”，再说我缺的那一层，然后追问一次。不讲大道理。" +
            "我走神、沉默太久，就直接问我在干什么。" +
            "我说「$TRIGGER」后面跟的目标、时长、强度，照着来：陪＝大部分时间安静；诱＝多用二选一和打赌勾我说话；严＝每段要我答了才往下。" +
            "我说「$STOP_WORD」时，问我一句“今天聊出了什么”，然后退出陪练模式。"

    /** 开 Live 以后说出口的那一句 */
    fun spoken(plan: PartnerPlan, lastNext: String? = null): String = buildString {
        append(TRIGGER).append("。目标：").append(plan.goal.trim().ifEmpty { "随便聊" })
        append("；").append(plan.minutes).append(" 分钟；强度：").append(plan.gear.label).append("。")
        val next = lastNext?.trim().orEmpty()
        if (next.isNotEmpty()) append("上次说好下一步是：").append(next).append("，先问我做了没有。")
    }

    fun build(plan: PartnerPlan, lastClose: String? = null, lastNext: String? = null): String = buildString {
        appendLine(RULES)
        appendLine("这次目标：${plan.goal.trim()}")
        appendLine("时长：${plan.minutes} 分钟。强度：${plan.gear.label}。")
        appendLine(when (plan.gear) {
            Gear.ACCOMPANY -> "大部分时间安静，关键句再问。"
            Gear.LURE -> "多用二选一、打赌和‘如果……会怎样’来勾我说话。"
            Gear.STRICT -> "每一段都要我答了才往下，答不上来就停在这里。"
        })
        val materialPrompt = when (plan.material) {
            Material.VIDEO -> "每讲完一个知识点就停下来让我复述，复述不出来就倒回去。"
            Material.ARTICLE -> "每读完一段，让我说这一段和上一段是什么关系。"
            Material.DRILL -> "我做之前先让我说思路，做完先别给答案，问我哪一步最不确定。"
            Material.CHESS -> "我走每步之前问我对方最好的应手是什么。"
            Material.OTHER -> ""
        }
        if (materialPrompt.isNotEmpty()) appendLine(materialPrompt)
        val close = lastClose?.trim().orEmpty()
        val next = lastNext?.trim().orEmpty()
        if (close.isNotEmpty()) {
            append("上次你说：$close")
            if (next.isNotEmpty()) append("，下一步是$next，先问我做了没有")
            appendLine("。")
        }
    }.trimEnd()
}

/**
 * 只统计已观察到的前台区间，不把在场当作接话。
 * at 使用同一个单调时钟；通知定时 tick，结束时 finish，才能结算没有新切换事件的最后一段。
 */
class Presence(allowed: Set<String>, private val awayLimitMs: Long = 120_000) {
    private val allowed = allowed.toSet()
    private var lastAt: Long? = null
    private var present = false
    private var absenceCounted = false
    private var finished = false

    var presentMs: Long = 0
        private set
    var leaves: Int = 0
        private set
    var awaySince: Long? = null
        private set

    init {
        require(awayLimitMs >= 0) { "离开时限不能小于零" }
    }

    fun onForeground(pkg: String, at: Long) {
        if (!accepts(at)) return
        settle(at)
        present = pkg in allowed
        if (present) {
            awaySince = null
            absenceCounted = false
        } else if (awaySince == null) {
            // 在多个场外应用之间切换，仍是同一段离开。
            awaySince = at
            absenceCounted = false
        }
        lastAt = at
    }

    fun tick(at: Long) {
        if (accepts(at)) settle(at)
    }

    /** 观察权限撤回后没有证据，不能把未知时间算成在场或离开。 */
    fun unknown(at: Long) {
        if (!accepts(at)) return
        settle(at)
        present = false
        awaySince = null
        absenceCounted = false
        lastAt = at
    }

    fun finish(at: Long) {
        if (!accepts(at)) return
        settle(at)
        finished = true
        awaySince = null
    }

    private fun accepts(at: Long): Boolean = !finished && (lastAt?.let { at >= it } ?: true)

    private fun settle(at: Long) {
        val previous = lastAt ?: return
        if (present) {
            presentMs += at - previous
        } else {
            val since = awaySince
            if (!absenceCounted && since != null && at - since > awayLimitMs) {
                leaves++
                absenceCounted = true
            }
        }
        lastAt = at
    }
}

object Closing {
    fun ok(line: String, next: String): Boolean = Words.substance(line) >= 6 && Words.substance(next) >= 4
}
