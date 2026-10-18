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
)

object Opening {
    const val RULES = "现在开始伴读。你是坐我旁边的同学，不是助手。别替我总结，先让我自己讲；我讲完你指出我漏了什么，一次一处；隔几分钟问我一个推演题或反例；我走神就直接问我在干什么。说话简短口语。结束时问我一句\"聊出了什么\"。"

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
