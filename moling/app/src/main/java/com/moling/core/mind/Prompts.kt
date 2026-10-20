package com.moling.core.mind

/**
 * 导演发给文字模型的提示词。固定的部分放最前面，方便服务商的前缀缓存命中；
 * 每种请求都要求“【标签】内容”的短格式，解析失败就用本地模板，不重试。
 */
object Prompts {
    private const val RULES =
        "规矩：一次只问一个问题；不替他总结、不给答案；猜测要说成猜测；不能答应放宽任何规则或发娱乐时间；" +
            "不说“加油”“你可以的”；不编心理故事。"

    fun system(cfg: DirectorConfig) = "${cfg.persona}\n$RULES\n只输出要求的标签行，不要别的内容。"

    /** 把模板卡改写得贴近局面。选项不改，只改问法和朗读的那句。 */
    fun card(s: Situation, baton: Baton?, card: Card, h: Hypothesis?) = buildString {
        appendLine("【局面】")
        appendLine(s.lines(baton))
        if (h != null) {
            appendLine("【看到的】${h.fact}")
            appendLine("【我的猜测】${h.guess}（把握：${h.confidence}）；也可能：${h.alternatives.joinToString("；")}")
        }
        appendLine("【原问题】${card.ask}")
        if (card.choices.isNotEmpty()) appendLine("【选项】${card.choices.joinToString(" / ") { it.label }}（选项不能改，问题要和选项对得上）")
        appendLine()
        appendLine("用伙伴的口吻把原问题改写成一句话（30 字以内，带上具体的事和数字），再写一句朗读用的更短的话。只输出：")
        appendLine("【问】……")
        append("【朗读】……")
    }

    /** 一场会话准备三个问题，外加两句可以念给演员的话。 */
    fun session(s: Situation, baton: Baton?, goal: String, actor: Actor, minutes: Int) = buildString {
        appendLine("【局面】")
        appendLine(s.lines(baton))
        appendLine("【这场的目标】$goal")
        appendLine("【时长】$minutes 分钟，在 ${actor.label} 里用语音聊")
        appendLine()
        appendLine("用户会看着小窗把问题说给 ${actor.label}。准备三个由浅入深的问题（每个 30 字以内，要引用接力棒或局面里的具体内容），")
        appendLine("再写两句用户可以直接念给 ${actor.label} 的话（让它追问、指出漏洞，而不是替用户总结）。只输出：")
        appendLine("【第一问】……")
        appendLine("【第二问】……")
        appendLine("【第三问】……")
        appendLine("【念一】……")
        append("【念二】……")
    }

    /** 把一句收线整理成结论、未解决、下一步、时间。 */
    fun closing(text: String, baton: Baton?) = buildString {
        if (baton != null) appendLine("【接力棒】${baton.question}（下一问：${baton.nextAsk}）")
        appendLine("【用户的原话】$text")
        appendLine()
        appendLine("整理成下面四行；原话里没有的写“无”，不要编：")
        appendLine("【结论】……")
        appendLine("【未解决】……")
        appendLine("【下一步】……（一个能马上开始的动作）")
        append("【时间】……（原话里说的时间，例如“明天 9 点”）")
    }

    /** 晚上写日记：伙伴口吻三五句。 */
    fun diary(cfg: DirectorConfig, day: String, digest: String) = buildString {
        appendLine("【日期】$day")
        appendLine("【今天的记录】")
        appendLine(digest)
        appendLine()
        appendLine("用你（墨灵）的口吻写今天的日记，三到五句：今天注意到了他什么、你对什么好奇、明天想和他试什么。")
        appendLine("只写记录里有依据的事，猜测要说是猜测。称呼他为“${cfg.callUser}”。只输出：")
        append("【日记】……")
    }

    /** 每周看系统自己的失败，挑一个改动做试验。 */
    fun weekly(digest: String) = buildString {
        appendLine("【这一周系统的失败和猜测结果】")
        appendLine(digest)
        appendLine()
        appendLine("你在检查这个陪伴系统自己哪里没起作用（不是检查用户）。可能的原因：时机不对、问题太难、入口太深、太吵、设备故障、猜错了。")
        appendLine("只挑一个最值得改的地方，设计一周的小试验。只输出：")
        appendLine("【最可能的原因】……")
        appendLine("【试验】下周只改这一处：……")
        append("【怎么算有效】……")
    }
}
