package com.zongkong.core

/**
 * 话头：去 ChatGPT / Gemini 时「不知道说什么」，总控按你此刻的状况替你起好几句开场白。
 *
 * 不调 AI、不联网：用总控手里已有的东西（今天的主线、当前一步、一手没接住的题、严管没有）拼出来，
 * 一点就带着前情过去。每一句都要求对方「先反驳我、再给建议、最后只问我一个问题」——
 * 去聊不是为了被安慰，是为了有人跟你争。纯逻辑，单元测试直接跑。
 */
object Openers {

    data class Ctx(
        val headline: String = "",
        val strict: Boolean = false,
        val mainTitle: String = "",
        val stepTitle: String = "",
        val stepNext: String = "",
        /** 一手今天没接住 / 差一点的几手（题目｜我说的｜判语），新的在后 */
        val misses: List<String> = emptyList(),
        val yishouLine: String = "",
        /** 最近一条结论 / 判断 */
        val lastJudge: String = "",
        val hour: Int = 12,
    )

    data class Opener(val key: String, val title: String, val text: String)

    private const val RULES =
        "规则：用中文；先指出我想法里最弱的一处（直接反驳，不要客气），再给建议；最后只问我一个问题，等我回答再继续。不要列长清单。"

    fun build(c: Ctx): List<Opener> {
        val out = mutableListOf<Opener>()
        val state = buildString {
            if (c.headline.isNotBlank()) append("我现在的状态：").append(c.headline).append(if (c.strict) "（被总控严管中）" else "").append('\n')
            if (c.mainTitle.isNotBlank()) append("今天的主线：").append(c.mainTitle).append('\n')
            if (c.stepTitle.isNotBlank()) append("当前这一步：").append(c.stepTitle).append('\n')
            if (c.stepNext.isNotBlank()) append("停在：").append(c.stepNext).append('\n')
        }

        if (c.misses.isNotEmpty()) {
            out += Opener(
                "misses", "复盘一手没接住的题",
                buildString {
                    append("我在做思考训练（刷题），今天这几题没接住：\n")
                    c.misses.takeLast(3).forEachIndexed { i, m -> append(i + 1).append(". ").append(m).append('\n') }
                    if (c.yishouLine.isNotBlank()) append("今天的账：").append(c.yishouLine).append('\n')
                    append("帮我找出这几题背后我反复漏掉的同一层，用一句话概括成「下次遇到这类先想什么」。\n")
                    append(RULES)
                },
            )
        }

        if (c.mainTitle.isNotBlank() || c.stepTitle.isNotBlank()) {
            out += Opener(
                "step", "把手上这一步想透",
                state + "我想把这一步推进下去，但没想清楚怎么开始。先问我三个能暴露问题的问题（一次一个），再帮我拆出 5 分钟内能动手的第一步。\n" + RULES,
            )
            out += Opener(
                "argue", "跟我吵：今天的安排对不对",
                state + (if (c.lastJudge.isNotBlank()) "我最近的判断：${c.lastJudge}\n" else "") +
                    "你扮演反方：论证我今天的安排是错的、或者主线根本不该是这个。我会反驳你，吵到其中一方说服另一方为止。\n" + RULES,
            )
        }

        if (c.strict || c.mainTitle.isBlank()) {
            out += Opener(
                "stuck", "我在逃避，帮我看清在逃什么",
                state + "我现在手里有事却一直在刷手机。别劝我，先问我：我此刻在回避的那件事，最让我不舒服的是哪一部分？然后帮我把它缩到能马上做的一小步。\n" + RULES,
            )
        }

        out += Opener(
            "think", "随便聊一个我在想的问题",
            "我想就一个问题跟你认真讨论，我先说我的观点，你负责找漏洞。\n" + RULES + "\n我的问题是：",
        )

        if (c.hour >= 21 || c.hour < 4) {
            out += Opener(
                "night", "睡前复盘今天",
                state + (if (c.yishouLine.isNotBlank()) "一手今天：${c.yishouLine}\n" else "") +
                    "陪我复盘今天：先问我今天最该做却没做的是什么，再帮我定明天醒来后的第一步（具体到动作）。\n" + RULES,
            )
        }
        return out
    }
}
