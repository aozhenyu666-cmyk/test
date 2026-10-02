package com.behaviordept.app.training

import com.behaviordept.app.data.Template

/*
 * 内置的技能树、标准和练习。没有 AI 也能直接开练；之后可以手改，也可以让 AI 重写。
 * 标准必须能被观察到（设计原则 4）：写“做了什么”，不写“感觉怎么样”。
 */

data class SeedDrill(val title: String, val method: String, val minutes: Int, val metric: String)

data class SeedCategory(val name: String, val standards: List<String>, val drills: List<SeedDrill>)

data class SeedSkill(val name: String, val template: String, val categories: List<SeedCategory>)

object Seeds {
    val DELTA_FORCE = SeedSkill(
        name = "三角洲行动",
        template = Template.COMPETITIVE,
        categories = listOf(
            SeedCategory(
                "信息",
                listOf(
                    "进点、开门前先停 1–2 秒听脚步和枪声，再决定走哪条路",
                    "每次交火前能说出敌人大概在哪、几个人",
                    "听到枪声后 3 秒内判断出方向和大概距离",
                    "换点时清楚自己会暴露给哪几个角度",
                ),
                listOf(
                    SeedDrill(
                        "听声报点",
                        "进人机局或靶场，每次听到脚步/枪声先停下，说出方向和距离，再转过去核对。记判断对的比例。",
                        15,
                        "判断正确率%",
                    ),
                    SeedDrill(
                        "交火前报点",
                        "实战里每次交火前，在心里说出“几个人、在哪”。局后数一数：说对了几次 / 一共交火几次。",
                        30,
                        "报对比例%",
                    ),
                ),
            ),
            SeedCategory(
                "枪法",
                listOf(
                    "拐角前准星已经在头线高度（预瞄），转出后不需要大幅拉枪",
                    "超出武器有效距离不长按扫射，中远距离点射或短连发",
                    "开火前先停稳，不在移动中开第一枪",
                    "靶场 25 米 10 连发，弹着点能落在一个手掌大的范围内",
                ),
                listOf(
                    SeedDrill(
                        "预瞄拐角",
                        "靶场或训练场选 5 个拐角，贴墙走位转出，准星不动直接开火。每个拐角 4 次，记首发命中次数。",
                        10,
                        "首发命中率%",
                    ),
                    SeedDrill(
                        "压枪 10 发",
                        "常用主武器，25 米靶连发 10 发为一组，打 10 组。弹着点都在一个手掌范围内算合格。",
                        10,
                        "合格组数（满分 10）",
                    ),
                    SeedDrill(
                        "急停开火",
                        "左右横移中松开方向键急停再开枪，20 次。记急停后首发命中次数。",
                        8,
                        "首发命中率%",
                    ),
                ),
            ),
            SeedCategory(
                "身法",
                listOf(
                    "peek 只露出需要的角度，打完立刻回掩体，不站在原地对枪",
                    "同一个位置不连续 peek 两次",
                    "换弹、打药之前先进掩体",
                    "挨打时第一反应是脱离视线，而不是原地回头找人",
                ),
                listOf(
                    SeedDrill(
                        "单向 peek",
                        "选一个掩体，练“探出—开火—回撤”，每次只露半个身位，20 次。记被反打（掉血）的次数。",
                        10,
                        "被反打次数（越低越好）",
                    ),
                    SeedDrill(
                        "交火后换位",
                        "实战里每次交火后必须换一个位置再打。局后数一数在同一位置重复 peek 了几次。",
                        30,
                        "重复 peek 次数（越低越好）",
                    ),
                ),
            ),
            SeedCategory(
                "决策",
                listOf(
                    "每次交火前说出“打 / 不打 + 一个理由”",
                    "进局前定好撤离点和带出价值目标，到了就撤，不贪",
                    "配装和这局目标一致（打架 / 摸金 / 苟撤离）",
                    "以少打多时先拉开距离，逐个处理",
                ),
                listOf(
                    SeedDrill(
                        "交火前说打不打",
                        "实战里每次交火前出声或在心里说“打/不打，因为……”。局后记说出来的次数 / 交火次数。",
                        30,
                        "说出比例%",
                    ),
                    SeedDrill(
                        "撤离计划",
                        "进局前写下：撤离点、带出价值目标、最晚撤离时间。局后记有没有按计划撤（按计划 100，否则 0）。",
                        30,
                        "按计划撤离%",
                    ),
                    SeedDrill(
                        "预测式看录像",
                        "看一段高手录像，每次交火前暂停，说出你会怎么打，再看他怎么打。记你和他一致的比例。",
                        20,
                        "预测一致率%",
                    ),
                ),
            ),
        ),
    )

    val CIVIL_EXAM = SeedSkill(
        name = "考公",
        template = Template.EXAM,
        categories = listOf(
            SeedCategory(
                "常识判断",
                listOf(
                    "近一年时政按月过过一遍，每月能说出 5 件大事",
                    "宪法、民法典的高频条文能用自己的话复述",
                    "不会的题 30 秒内放弃，不耗时间",
                ),
                listOf(SeedDrill("时政 + 常识 20 题", "限时 10 分钟做 20 道常识题，对完答案把错题涉及的知识点写一句话。", 15, "正确率%")),
            ),
            SeedCategory(
                "言语理解与表达",
                listOf(
                    "片段阅读先找转折词、主旨句，再看选项",
                    "逻辑填空能说出两个相近选项的区别",
                    "每题平均不超过 50 秒",
                ),
                listOf(
                    SeedDrill("片段阅读限时", "20 道片段阅读，限时 16 分钟。错题写出“主旨句在哪”。", 20, "正确率%"),
                    SeedDrill("逻辑填空辨析", "20 道逻辑填空，限时 14 分钟。错题把两个干扰选项的差别各写一句。", 20, "正确率%"),
                ),
            ),
            SeedCategory(
                "数量关系",
                listOf(
                    "拿到题先判断题型和值不值得做，难题直接跳过",
                    "代入、特值、比例、方程四种方法能说出各用在什么题",
                    "单题不超过 1.5 分钟",
                ),
                listOf(SeedDrill("方法专项 10 题", "选一种方法（代入/特值/比例）做 10 道题，限时 15 分钟，做完写每题为什么用这个方法。", 20, "正确率%")),
            ),
            SeedCategory(
                "判断推理",
                listOf(
                    "图形推理先数数量，再看位置和样式",
                    "定义判断先圈出关键词再对照选项",
                    "逻辑判断能把题干写成“如果…那么…”",
                ),
                listOf(
                    SeedDrill("图推 20 题", "20 道图形推理，限时 16 分钟。错题写出正确规律属于哪一类。", 20, "正确率%"),
                    SeedDrill("逻辑判断 15 题", "15 道逻辑判断，限时 15 分钟。每道先写出推理形式再选。", 20, "正确率%"),
                ),
            ),
            SeedCategory(
                "资料分析",
                listOf(
                    "增长率、比重、平均数、倍数的公式能默写无误",
                    "选项差距大时用估算，不精算",
                    "每题不超过 1 分钟",
                ),
                listOf(
                    SeedDrill("公式默写 + 速算", "默写 10 个核心公式，再做 20 道速算题，限时 10 分钟。", 15, "正确率%"),
                    SeedDrill("整篇限时", "一篇资料 5 道题，限时 6 分钟，做 2 篇。", 15, "正确率%"),
                ),
            ),
            SeedCategory(
                "申论",
                listOf(
                    "归纳概括要点齐全，尽量用材料原词",
                    "大作文有清楚的总—分—总结构，每段有一个分论点",
                    "按分值分配时间和字数",
                ),
                listOf(SeedDrill("归纳概括 1 题", "限时做 1 道归纳概括题，对照参考答案数要点。", 30, "要点得分率%")),
            ),
        ),
    )

    /** 考试登记时各模块的默认题量（国考地市级行测，可以改）。 */
    val EXAM_DEFAULT_TOTALS = mapOf(
        "常识判断" to 20,
        "言语理解与表达" to 40,
        "数量关系" to 15,
        "判断推理" to 40,
        "资料分析" to 20,
    )

    /** 防线默认管的 App：抖音、B 站、贴吧、小红书、红果短剧。 */
    val ENTERTAINMENT_PACKAGES = listOf(
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "tv.danmaku.bili",
        "com.baidu.tieba",
        "com.xingin.xhs",
        "com.phoenix.read",
    )

    val KNOWN_APP_NAMES = mapOf(
        "com.ss.android.ugc.aweme" to "抖音",
        "com.ss.android.ugc.aweme.lite" to "抖音极速版",
        "tv.danmaku.bili" to "B站",
        "com.baidu.tieba" to "贴吧",
        "com.xingin.xhs" to "小红书",
        "com.phoenix.read" to "红果短剧",
    )
}
