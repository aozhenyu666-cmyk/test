package com.zongkong.core.agent

/**
 * 战役：总控带着你走完一整段深度工作，不是一问一答。
 * 每个战役有固定的阶段；进行中时，阶段说明一直放在系统提示里（多轮对话也不会忘），
 * 总控一段一段推进，每段落到工具上（写里程碑、建行动、提节点、提炼……），最后用 campaign_done 收尾。
 */
enum class Campaign(val title: String, val dept: String, val what: String, val procedure: String) {
    GRAND_PLAN(
        "大统筹", "统筹规划部",
        "从长期目标倒推：每个目标的里程碑 → 这周交出什么 → 排进时间 → 预演最危险的地方。",
        """
        目标：把“远方”（长期目标）一路落到这周的日程上，走完以后他手里有：每个目标 3–5 个带日期的里程碑、一份本周（或下周）计划、排进时间的具体行动、一条约定。
        阶段（每段开头写【阶段 n/6 · 名字】，一段只问一个问题，给【选项】）：
        1 盘点：调 get_plan、list_threads、calibration（必要时 pc 看电脑、get_dossier），用三行说清楚：离每个长期目标还差什么、这周实际时间花在哪、最大的漏洞。没有长期目标就先问他、用 set_aim 挂上。
        2 里程碑：每个目标从截止日往回倒推 3–5 个里程碑（能检查的产出 + 日期），用 set_milestones 写进去；先给他看，他点头再写。
        3 本周：从最近的里程碑里挑出这周必须交出的 1–3 样，用 draft_plan 起草（每天给多少分钟按 calibration 的实际系数打折）。已经有生效的计划就用 revise_plan 对齐。
        4 排进时间：每样交出物拆成 25–90 分钟的步骤，用 backward_plan 或 add_action 排进精力最好的钟头；每一步写完成依据。
        5 预演：找这周最可能崩的一处（看档案、看电脑和手机的娱乐时段），写一条“如果…就…”，用 commit 记一条最关键的约定，用 schedule_check 排一次回访。
        6 收尾：set_day_main 定今天（或明天）的主线；五行以内总结；调 campaign_done。
        """.trimIndent(),
    ),
    GRAND_THINK(
        "大谋划", "谋划思考部",
        "围绕一个真问题想透：定义 → 拆开 → 假设和对立 → 结论 → 最便宜的验证，写进脉络。",
        """
        目标：把一个真正卡着他的问题想透，走完以后：问题被说清楚、拆成几支、每支有假设和最便宜的验证办法、有一个当前结论，节点进了脉络候选，验证排成了行动。
        阶段（每段开头写【阶段 n/6 · 名字】，一段只推进一步，给【选项】）：
        1 选题：他没给题目就从这里挑——vein_due 里拖最久的假设、卡住的行动、离截止最近的长期目标——给 3 个候选让他选。把问题重写成一句话，并写出“想透了的标志”。
        2 拆开：先 vein_search 看已有的节点；再把问题拆成 3–5 支，彼此不重叠、合起来不漏。一支一行。
        3 假设：每支写一个当前最可能的答案和把握（百分比），以及 30 分钟内能做完的验证。
        4 对立：替最强的反方说话——如果我们错了，最可能错在哪？有没有完全不同的解释？把能推翻结论的证据写出来。
        5 结论：一句当前结论 + 它依赖哪几个假设。用 vein_propose 提议节点（推断类 status=假设并写 verify）；用 add_action（没有对应事项就 create_thread）把 1–3 个验证排进时间。
        6 回填：用 schedule_check 排一次回头看（验证结果出来的时候）；三行总结；调 campaign_done。
        """.trimIndent(),
    ),
    INTAKE(
        "整理收集箱", "信息收集部",
        "一次清空积压：每条给一句话收获和去处，没用的直接丢，有用的变成行动或节点。",
        """
        目标：把收集箱的积压清到 3 条以内。
        阶段：
        1 看：调 intake_status，列出待提炼的（最多 12 条），按“马上有用 / 以后有用 / 没用”分三堆，给出你的建议。
        2 定：一次拿 3–4 条给他确认（【选项】全部按建议｜我改几条），他确认后逐条 distill：一句话收获 + 归位到事项。没用的直接说丢，不纠结。
        3 用：马上有用的，每条变成一个 add_action（25 分钟内能做完的一步）或 vein_propose 一个节点。
        4 收尾：说清楚还剩几条、下次什么时候再整理；调 campaign_done。
        """.trimIndent(),
    );

    /** 进行中时放进系统提示的一段。 */
    fun prompt(topic: String, startedAt: Long, now: Long): String = buildString {
        append("## 正在进行的战役：$title（$dept）\n")
        if (topic.isNotBlank()) append("他给的题目：$topic\n")
        append("已经进行 ${(now - startedAt) / 60_000} 分钟。按下面的阶段推进；每次回复只走一段，落到工具上，再问一个问题。他想中途停下就调 campaign_done 记下进度。\n")
        append(procedure)
    }

    /** 开战时发给模型的第一句（对话里不显示）。 */
    fun opening(topic: String): String =
        "[战役·开始] 开始“$title”。" + (if (topic.isNotBlank()) "题目：$topic。" else "") + "从阶段 1 开始，先调工具看清现状，再用两三句话告诉他这场要走几步、大概多久（15–40 分钟），然后问第一个问题。"

    companion object {
        fun of(name: String): Campaign? = entries.firstOrNull { it.name == name || it.title == name }
    }
}
