package com.zongkong.core

import java.time.LocalDate

/** 默认关卡、谋划方法卡、四部门说明、常见娱乐应用。 */
object Defaults {

    fun gates(): List<Gate> = listOf(
        Gate(
            id = "plan_morning",
            dept = Dept.PLAN,
            title = "今日部署",
            instruction = "开工前：从事项里挑今天要推进的行动，排进时间，每个写清完成依据。" +
                "可以去 GPT 定好后把交接块导入，也可以在事项页里直接排。没部署之前，娱乐应用一律拦截。",
            openAt = 4 * 60,
            deadline = 10 * 60,
            block = BlockMode.FROM_OPEN,
            verify = VerifyMode.EVIDENCE,
            minChars = 0,
            evidence = "PLANNED_TODAY",
            evidenceMin = 1,
        ),
        Gate(
            id = "info_daily",
            dept = Dept.INFO,
            title = "收集并关联",
            instruction = "把今天看到的、想到的、卡住的东西收进来，并且挂到相关的事情上——收进来却用不上的材料不算数。" +
                "在「收集」页随手记、分享链接或截图给总控，或者在 Notion 记录库里写，都算。",
            openAt = 4 * 60,
            deadline = 20 * 60,
            verify = VerifyMode.EVIDENCE,
            minChars = 0,
            notionMinPages = 3,
            evidence = "LINKED_NOTES",
            evidenceMin = 2,
        ),
        Gate(
            id = "act_train",
            dept = Dept.ACT,
            title = "一手训练",
            instruction = "在一手里练完今天的组数（一组是几手有效回答，一手按你最近的达标情况自动加减目标）。练完不用写汇报，总控直接读一手的记录，够了自动打勾。",
            openAt = 4 * 60,
            deadline = 21 * 60,
            verify = VerifyMode.EVIDENCE,
            evidence = "YISHOU_SETS",
            evidenceMin = 2,
            launch = "com.yishou.app",
        ),
        Gate(
            id = "think_one",
            dept = Dept.THINK,
            title = "推进一个判断",
            instruction = "挑一件事，带着前情去和 GPT 讨论（总控会把前情整理好复制给你），形成判断、关键不确定和下一步，" +
                "把交接块导回总控。结论可以是“还需要补充信息”，只要写清缺什么、去哪查。也可以在下面直接写你的判断。",
            template = """
                问题：
                今天的方法卡：{method}
                我的判断：
                最有力的反驳或风险：
                关键不确定（还缺什么信息）：
                下一步：
            """.trimIndent(),
            openAt = 4 * 60,
            deadline = 21 * 60 + 30,
            verify = VerifyMode.EVIDENCE,
            minChars = 0,
            evidence = "CONCLUSIONS",
            evidenceMin = 1,
        ),
        Gate(
            id = "hq_review",
            dept = Dept.HQ,
            title = "日终验收",
            instruction = "总控已经把今天的清单列好了：排了什么、做了没有、时间花在哪。看一眼，补一句明天第一步，交上来就行。" +
                "不评分、不打回——清单是照着记录列的，不用你写报告。",
            openAt = 18 * 60,
            deadline = 23 * 60,
            verify = VerifyMode.TEXT,
            minChars = 10,
        ),
        Gate(
            id = "plan_week",
            dept = Dept.PLAN,
            title = "本周计划",
            instruction = "进入计划模式（统筹规划部 → 本周计划），五分钟：看一眼上周 → 选最多 3 个方向 → 每个方向这周交出 1–2 样 → 每天给多少时间 → 预演最可能卡住的地方 → 你点头立项。也可以让总控起草，你只管点头。",
            days = setOf(7, 1),
            openAt = 4 * 60,
            deadline = 21 * 60,
            verify = VerifyMode.EVIDENCE,
            evidence = "WEEK_PLAN",
            evidenceMin = 1,
        ),
    )

    /**
     * 老配置升级：关卡存在 config.json 里，改了默认值老用户拿不到。这里把明确过时的那几道换成新的。
     * - 0.8：日终验收不再由 AI 按标准逐条审（“他又说我没完成”），改成交今天的清单。
     * - 对话段数：默认 1 → 3（用户说“光一个对话不太行”）。老配置里的 1 分不清是旧默认还是用户自己设的，
     *   所以只在没迁移过的配置上动手，且只动 1；其它值原样留着，只把标记置上，之后不再回头看。
     */
    fun migrate(c: Config): Config {
        val fresh = gates().associateBy { it.id }
        val gates = c.gates.map { g ->
            when {
                g.id == "hq_review" && g.verify == VerifyMode.AI && g.rubric.contains("逐条对照") -> fresh.getValue("hq_review")
                else -> g
            }
        }
        val chat = migrateChat(c.chat)
        return if (gates == c.gates && chat == c.chat) c else c.copy(gates = gates, chat = chat)
    }

    /**
     * 老配置没写过 threadsPerDay 时读出来的就是旧默认 1：升级后改成 3，并打上标记。
     *
     * 用户自己设过的 2 / 5 这类值一个字都不动——他的选择比新默认更可信。那种配置以后也不会被碰到，
     * 因为这里的条件就只有“等于 1”；所以不需要为了挡住它而写标记，`migrate` 也保持
     * “没东西要改就原对象返回”的老约定（[com.zongkong.app.data.Store] 每次读配置都调它）。
     * 设置页写段数时会一并置标记，用户之后自己选回 1 段是算数的。
     */
    private fun migrateChat(chat: ChatConfig): ChatConfig {
        if (chat.threadsMigrated || chat.threadsPerDay != 1) return chat
        return chat.copy(threadsPerDay = 3, threadsMigrated = true)
    }

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
            output = "周部署（≤3 个目标 + 量化标准 + 时间预算）；今日部署（排进时间的行动，每个有完成依据）；日终验收。",
            rhythm = "每天早上 10 分钟部署，晚上 10 分钟验收；每周日 30 分钟周部署。",
            tools = "总控里直接交；验收结果会写进 Notion 总控日志，GPT 读日志就能帮你看一周的偏差。",
            pitfall = "计划写得太满、没有完成标准。一天只有一件主线，完成标准必须能检查。",
        ),
        Guide(
            Dept.ACT,
            duty = "管住手、练好思考动作。一手负责练：打开娱乐应用前先应一手，陪练窗口里一手一手地推进，每天按组训练；总控负责盯：做事时开专注锁，节律没交、娱乐超额就弹回。",
            input = "统筹规划部排好的行动（交给一手陪练）；日终验收里暴露出来的执行问题。",
            output = "一手里的有效回答（按组计）、更新后的断点、每晚总结出的一条规则；总控里的专注时长和被弹回次数。",
            rhythm = "每天按一手的目标组数练（连续两天达标加一组，没达标减一组）；陪练窗口每天一次。",
            tools = "一手（入口思考页、陪练窗口、分组训练、预判本、回响）；总控（专注锁、拦截名单、娱乐上限）。两边自动连着：总控严管时一手不重复拦，总控直接读一手的组数验收。",
            pitfall = "只靠拦截不练思考，拦得住手拦不住心。被弹回时别硬扛，回到当前一步，或者去一手应一手。",
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
        "com.zhihu.android", // 知乎
        "com.hupu.games", // 虎扑
        "com.douban.frodo", // 豆瓣
        "com.tencent.weishi", // 微视
        "com.baidu.haokan", // 好看视频
        "tv.danmaku.bilibilihd", // 哔哩哔哩 HD
        "com.ss.android.ugc.aweme.mobile", // 抖音（其他渠道）
        "com.taobao.idlefish", // 闲鱼
        "com.twitter.android", // X / Twitter
        "com.instagram.android", // Instagram
        "com.google.android.youtube", // YouTube
        "com.netease.mc", // 我的世界（网易）
        "com.tencent.tmgp.cf", // 穿越火线
        "com.miHoYo.hkrpg", // 崩坏：星穹铁道
        "com.tencent.jkchess", // 金铲铲之战
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
