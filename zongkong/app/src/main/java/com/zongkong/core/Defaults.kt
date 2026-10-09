package com.zongkong.core

import java.time.LocalDate

/** 默认关卡、谋划方法卡、四部门说明、常见娱乐应用。 */
object Defaults {

    fun gates(): List<Gate> = listOf(
        Gate(
            id = "plan_morning",
            dept = Dept.PLAN,
            title = "晨间部署",
            instruction = "开工前 10 分钟：定下今天唯一的主线，最多 3 件次要的事，每件写清几点做、做到什么程度算完。" +
                "没部署之前，娱乐应用一律拦截。",
            template = """
                主线（今天最重要的一件事）：
                · 做什么：
                · 几点到几点：
                · 做到什么程度算完成：
                次要（最多 3 件，每件一行：事 / 时间 / 完成标准）：
                1.
                2.
                今天最可能掉链子的地方，和对策：
            """.trimIndent(),
            openAt = 4 * 60,
            deadline = 10 * 60,
            block = BlockMode.FROM_OPEN,
            verify = VerifyMode.AI,
            minChars = 60,
            rubric = """
                有且只有一件主线
                主线写了具体时间段
                主线有能检查的完成标准（数量、页数、题数、产出物），不是“好好学”“尽量”这类空话
                次要的事不超过 3 件
                写了一个可能掉链子的地方和对应对策
            """.trimIndent(),
        ),
        Gate(
            id = "info_daily",
            dept = Dept.INFO,
            title = "收集入库",
            instruction = "把今天看到的、想到的、卡住的东西收进信息收集库。至少 3 条，其中至少 1 条是“问题”——" +
                "问题会流到谋划思考部。可以用 GPT 整理后存进 Notion，也可以在「汇报」页随手记。",
            template = """
                今天收集的（每条一行，标上类型：信息 / 问题 / 灵感 / 待办）：
                1.【问题】
                2.
                3.
            """.trimIndent(),
            openAt = 4 * 60,
            deadline = 20 * 60,
            verify = VerifyMode.TEXT,
            minChars = 40,
            notionMinPages = 3,
        ),
        Gate(
            id = "act_train",
            dept = Dept.ACT,
            title = "训练打卡",
            instruction = "在行为管理部 App 里练完今天的动作，回来报告练了什么、哪个分解动作最差、下次怎么改。",
            template = """
                练了什么（动作 / 组数 / 时长）：
                最差的一个分解动作：
                下次怎么改：
            """.trimIndent(),
            openAt = 4 * 60,
            deadline = 21 * 60,
            verify = VerifyMode.TEXT,
            minChars = 30,
            launch = "com.behaviordept.app",
        ),
        Gate(
            id = "think_one",
            dept = Dept.THINK,
            title = "谋划一题",
            instruction = "从信息收集库挑一个“问题”，用今天的方法卡想 20 分钟。可以让 AI 当反方，但结论要你自己写。" +
                "产出一张谋划单：结论 + 24 小时内能做的下一步。",
            template = """
                问题（从信息收集部挑的）：
                今天的方法卡：{method}
                我的初判：
                最有力的反驳或风险（可以让 AI 当反方）：
                修正后的结论：
                24 小时内的下一步：
                置信度（0–100%）：
            """.trimIndent(),
            openAt = 4 * 60,
            deadline = 21 * 60 + 30,
            verify = VerifyMode.AI,
            minChars = 120,
            rubric = """
                问题具体，不是“怎么变优秀”这种大而空的问题
                写了自己的初步判断
                至少有一条有力的反驳或风险，而且认真回应了，不是走过场
                修正后的结论和初判相比有变化，或者说明了为什么不变
                下一步具体到动作，24 小时内能做完
                写了置信度
            """.trimIndent(),
        ),
        Gate(
            id = "hq_review",
            dept = Dept.HQ,
            title = "日终验收",
            instruction = "对照晨间部署逐条过：完成了没有、没完成的真实原因是什么、明天第一步做什么。" +
                "AI 会看到今天的实际数据（各关卡、娱乐时长、被弹回次数、紧急放行），对不上会被打回。",
            template = """
                主线：完成 / 没完成，实际做到：
                次要：
                没完成的真实原因（不是借口）：
                今天最大的一个偏差：
                明天第一步（几点、做什么）：
            """.trimIndent(),
            openAt = 18 * 60,
            deadline = 23 * 60,
            verify = VerifyMode.AI,
            minChars = 80,
            rubric = """
                逐条对照了晨间部署，说清楚每件事完成没有
                没完成的事写出了真实原因，而不是泛泛的“没时间”“状态不好”
                和今天的实际数据（娱乐时长、被弹回次数、紧急放行）对得上，没有隐瞒
                写了明天第一步，具体到时间和动作
            """.trimIndent(),
        ),
        Gate(
            id = "plan_week",
            dept = Dept.PLAN,
            title = "周部署",
            instruction = "每周日：回看这周，定下周最多 3 个目标，每个目标有量化标准和时间预算。",
            template = """
                上周回看：定的目标完成了几个？最大的问题？
                下周目标（最多 3 个，每个：目标 / 量化标准 / 每周投入几小时）：
                1.
                2.
                下周已知的干扰和对策：
            """.trimIndent(),
            days = setOf(7),
            openAt = 4 * 60,
            deadline = 21 * 60,
            verify = VerifyMode.AI,
            minChars = 100,
            rubric = """
                回看了上周，说了完成情况和最大的问题
                目标不超过 3 个
                每个目标有量化标准
                每个目标有时间预算
                考虑了已知的干扰
            """.trimIndent(),
        ),
    )

    /** 谋划思考部的方法卡，按天轮换。 */
    data class Method(val name: String, val how: String)

    val methods = listOf(
        Method("第一性拆解", "这个问题最底层由哪几个事实和约束组成？抛开“一般都这么做”，从零开始会怎么做？"),
        Method("反方辩论", "先写下你的判断，再让 AI 扮演最强的反方。至少接住一条你一开始没想到的反驳。"),
        Method("事前验尸", "假设三个月后这件事彻底失败了。最可能的三个原因是什么？每个现在能怎么预防？"),
        Method("五个为什么", "挑一个反复出现的问题，连问五次“为什么”，找到你能改的那一层，而不是停在表面。"),
        Method("类比迁移", "找一个表面完全不同、结构相同的领域，那里的人是怎么解决这类问题的？能搬过来哪一点？"),
        Method("决策矩阵", "列出 2–4 个选项和 3–4 个标准，逐项打分。看结果和你的直觉差在哪里，差的那一块就是要想清楚的。"),
        Method("10-10-10", "这个决定在 10 分钟后、10 个月后、10 年后分别意味着什么？哪个时间尺度在主导你？"),
    )

    fun methodOf(date: LocalDate): Method = methods[(date.toEpochDay().mod(methods.size.toLong())).toInt()]

    /** 模板里的 {method} 换成当天的方法卡。 */
    fun fillTemplate(template: String, date: LocalDate): String =
        template.replace("{method}", methodOf(date).let { "${it.name}——${it.how}" })

    /** 四部门的职责说明，显示在「部门」页。 */
    data class Guide(
        val dept: Dept,
        val duty: String,
        val input: String,
        val output: String,
        val rhythm: String,
        val tools: String,
        val pitfall: String,
    )

    val guides = listOf(
        Guide(
            Dept.INFO,
            duty = "把每天接触到的信息、冒出来的念头、卡住的问题收进一个地方，不让它们散掉。只管收和分类，不管想清楚。",
            input = "文章、视频、聊天、上课、刷到的东西、脑子里突然冒出来的问题。",
            output = "信息收集库里的条目：一句话标题 + 类型（信息 / 问题 / 灵感 / 待办）+ 为什么值得留。",
            rhythm = "随时收（随手记）；每天至少 3 条，其中至少 1 条是问题；每周日清一次库：问题转谋划，待办转统筹，没用的归档。",
            tools = "GPT 负责把长内容压成一句话；Notion 信息收集库负责存。总控按当天新建的条数查账。",
            pitfall = "只收不分类，最后变成垃圾堆。每条必须标类型，“问题”类是谋划思考部的原料。",
        ),
        Guide(
            Dept.THINK,
            duty = "每天从信息收集库挑一个问题，用固定的方法想透，产出结论和下一步。想不出好结论不要紧，要紧的是每天都有一张完整的谋划单。",
            input = "信息收集库里类型为“问题”的条目；统筹规划部卡住的决定。",
            output = "谋划单：问题 / 方法卡 / 初判 / 最强反驳 / 修正结论 / 24 小时内的下一步 / 置信度。",
            rhythm = "每天一题，20–30 分钟；每周日回看上周的谋划单：结论应验了几个？置信度准不准？",
            tools = "方法卡每天轮换（7 张）。AI 用来当反方、补盲点，不用来替你下结论——结论那一栏必须是你自己写的。",
            pitfall = "一上来就问 AI“我该怎么办”，拿到答案就算想过了。先写初判再问 AI，才有东西可比、可修正。",
        ),
        Guide(
            Dept.PLAN,
            duty = "把时间分给最重要的事。周定方向，日定动作，晚上对账。谋划出的下一步、信息收集里的待办都在这里排进时间。",
            input = "长期目标（国考、省考等）、谋划单里的下一步、信息收集库里的待办。",
            output = "周部署（≤3 个目标 + 量化标准 + 时间预算）；晨间部署（1 主线 + ≤3 次要 + 完成标准）；日终验收。",
            rhythm = "每天早上 10 分钟部署，晚上 10 分钟验收；每周日 30 分钟周部署。",
            tools = "总控里直接交；验收结果会写进 Notion 总控日志，GPT 读日志就能帮你看一周的偏差。",
            pitfall = "计划写得太满、没有完成标准。一天只有一件主线，完成标准必须能检查。",
        ),
        Guide(
            Dept.ACT,
            duty = "把要练的能力拆成分解动作，按动作练，按标准复测。比如羽毛球拆成步伐和几种挥拍，每个动作单独练、单独打分。",
            input = "要养成的技能和习惯；日终验收里暴露出来的执行问题。",
            output = "每天的训练记录：练了哪个分解动作、几组、最差的环节、下次改什么。",
            rhythm = "每天练，每 7 天复测一次。",
            tools = "行为管理部 App 负责练（训练、记录、防线）；总控只负责验收你今天练没练。",
            pitfall = "只练整套不练分解动作，进步看不见。先找最差的一个分解动作，单独练到达标。",
        ),
    )

    /** 常见娱乐应用，首次设置时如果手机上装了就默认勾上。 */
    val suggestedBlocked = listOf(
        "com.ss.android.ugc.aweme", // 抖音
        "com.ss.android.ugc.aweme.lite", // 抖音极速版
        "com.ss.android.ugc.live", // 抖音火山版
        "com.smile.gifmaker", // 快手
        "com.kuaishou.nebula", // 快手极速版
        "tv.danmaku.bili", // 哔哩哔哩
        "com.bilibili.app.in", // 哔哩哔哩（国际版）
        "com.xingin.xhs", // 小红书
        "com.sina.weibo", // 微博
        "com.ss.android.article.news", // 今日头条
        "com.ss.android.article.video", // 西瓜视频
        "com.tencent.qqlive", // 腾讯视频
        "com.qiyi.video", // 爱奇艺
        "com.youku.phone", // 优酷
        "com.duowan.kiwi", // 虎牙
        "air.tv.douyu.android", // 斗鱼
        "com.tencent.tmgp.sgame", // 王者荣耀
        "com.tencent.tmgp.pubgmhd", // 和平精英
        "com.miHoYo.Yuanshen", // 原神
        "com.dragon.read", // 番茄小说
        "com.kmxs.reader", // 七猫小说
    )

    /** 不允许加进拦截名单的应用：总控自己、工作用的几个 App。 */
    val neverBlock = setOf(
        "com.zongkong.app",
        "com.yishou.app",
        "com.behaviordept.app",
        "com.behaviordept.app.debug",
        "notion.id",
        "com.openai.chatgpt",
    )
}
