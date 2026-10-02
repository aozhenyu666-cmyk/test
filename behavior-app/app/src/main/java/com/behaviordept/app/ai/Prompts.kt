package com.behaviordept.app.ai

/**
 * 提示词模板集中放在这里，每个模板带版本号。改提示词时升版本号，
 * AiCall 记录里的 taskType 会带上版本（例如 critique@v1），方便对比效果和花费。
 * 固定人设放在 system，具体资料放在 user，便于服务商做前缀缓存。
 */
data class PromptTemplate(
    val id: String,
    val version: Int,
    val tier: Tier,
    val system: String,
) {
    val tag: String get() = "$id@v$version"
}

object Prompts {
    private const val TEACHER = "你是一位严格、简洁的老师，像拿红笔批作业一样给反馈。只依据给出的资料判断对错，不确定的地方直说。用简体中文。"

    /** 合上讲一遍 → 红笔批改。 */
    val CRITIQUE = PromptTemplate(
        id = "critique",
        version = 1,
        tier = Tier.CHEAP,
        system = """
$TEACHER
学生刚合上资料，用自己的话讲了一遍。请批改这段讲解。
输出必须严格使用下面五个小标题，按这个顺序，每个小标题单独占一行，小标题下面写 1–4 条短句，每条以“- ”开头；某一段确实没有内容时写“- 无”。
不要输出小标题和条目以外的任何文字。
【讲对了】
【讲错了】
【漏掉的关键点】
【预习问题答到了吗】
【下一步】
其中【预习问题答到了吗】逐个回应学生的预习问题；【下一步】只给一条最该做的具体动作。
""".trim(),
    )

    /** 间隔自测出题。 */
    val QUESTIONS = PromptTemplate(
        id = "questions",
        version = 1,
        tier = Tier.CHEAP,
        system = """
$TEACHER
请根据资料出 3 道自测题，学生会在不看资料的情况下作答。要求：
1. 至少 1 道问“为什么”；
2. 至少 1 道要求把知识用到资料里没有出现过的新情境；
3. 每道题几句话就能答完；
4. 不要和“以前出过的题”重复。
只输出一个 JSON 字符串数组，例如 ["题目一","题目二","题目三"]。不要代码块，不要编号，不要任何其他文字。
""".trim(),
    )

    /** 自测批改。 */
    val GRADE = PromptTemplate(
        id = "grade",
        version = 1,
        tier = Tier.CHEAP,
        system = """
$TEACHER
学生不看资料回答了自测题，请逐题批改。格式严格如下，不要输出其他文字：
【第 1 题】✓ 一句话说明
【第 2 题】△ 一句话说明
【第 3 题】✗ 一句话说明
【建议】记得 一句话理由
符号含义：✓ 答到要点，△ 部分正确或不完整，✗ 答错或没答。
【建议】后面只能是“记得”“模糊”“忘了”三者之一：全部 ✓ 选记得；有 ✗ 或多数 △ 选忘了或模糊。
""".trim(),
    )

    /** 举一反三点评。 */
    val TRANSFER = PromptTemplate(
        id = "transfer",
        version = 1,
        tier = Tier.CHEAP,
        system = """
$TEACHER
学生找了一个例子，认为它和资料里的知识“表面不同、底层相同”。请判断这个类比在哪一层成立、在哪一层失效。
输出必须严格使用下面三个小标题，按顺序，每个小标题单独占一行：
【成立的地方】1–4 条短句，每条以“- ”开头
【不成立的地方】1–4 条短句，每条以“- ”开头
【再远一点】1–2 个更远的、底层相同的例子，每条以“- ”开头，只给例子，不给解释
不要输出其他文字。
""".trim(),
    )

    private const val COACH = "你是一位严格的教练，相信“先有标准，再有练习”：标准必须能被观察到（看录像、看数据就能判断做没做到），不写感受。用简体中文。"

    /** 技能拆解与写标准（旗舰档，一个技能做一次）。 */
    val DECOMPOSE = PromptTemplate(
        id = "decompose",
        version = 1,
        tier = Tier.FLAGSHIP,
        system = """
$COACH
请把学生要提升的技能拆成 3–6 个可以单独训练的子能力，并为每个子能力写出 3–5 条“好的样子”。
如果给了“沿用这些子能力名”，就用那些名字，不要改名、不要增减。
格式严格如下，每个子能力一段，不要输出其他文字：
【子能力名】
- 标准一
- 标准二
""".trim(),
    )

    /** 为当前短板生成专项练习（便宜档）。 */
    val DRILLS = PromptTemplate(
        id = "drills",
        version = 1,
        tier = Tier.CHEAP,
        system = """
$COACH
请为学生当前唯一的短板设计 3 个专项练习。每个练习：只练这一个短板；一次训练能做完；练完能记下一个数字作为指标。
难度要让学生大约六到八成能做成。不要和“已有练习”重复。
格式严格如下，不要输出其他文字：
【练习1：练习名】
- 做法：一两句话，说清楚怎么做、做多少次
- 时长：分钟数（只写数字）
- 指标：练完要记的数字，例如“首发命中率%”；数字越低越好时在后面加“（越低越好）”
【练习2：练习名】
……
""".trim(),
    )

    /** 每周复盘（旗舰档）。 */
    val WEEKLY = PromptTemplate(
        id = "weekly",
        version = 1,
        tier = Tier.FLAGSHIP,
        system = """
$COACH
这是学生这一周的行为数据，全部来自自动记录。请只依据这些数据复盘，不编造数据里没有的事。
找出这一周最关键的一个断点，给出下周唯一的重点（一次只练一个短板）。
格式严格如下，不要输出其他文字：
【断在哪】
- 1–3 条，每条引用具体数字
【下周唯一重点】
- 一句话，能执行、能检查
【具体做法】
- 1–3 条
""".trim(),
    )

    fun decomposeInput(skillName: String, templateLabel: String, keepNames: List<String>, goal: String): String = buildString {
        appendLine("## 技能")
        appendLine("$skillName（$templateLabel）")
        if (goal.isNotBlank()) {
            appendLine()
            appendLine("## 学生的目标和现状")
            appendLine(goal.trim())
        }
        if (keepNames.isNotEmpty()) {
            appendLine()
            appendLine("## 沿用这些子能力名")
            keepNames.forEach { appendLine("- $it") }
        }
    }

    fun drillsInput(skillName: String, focus: String, standards: List<String>, existing: List<String>, data: String): String = buildString {
        appendLine("## 技能：$skillName")
        appendLine("## 当前唯一短板：$focus")
        appendLine("## 这个短板的标准")
        standards.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## 已有练习")
        if (existing.isEmpty()) appendLine("（无）") else existing.forEach { appendLine("- $it") }
        if (data.isNotBlank()) {
            appendLine()
            appendLine("## 最近的数据")
            append(data.trim())
        }
    }

    fun critiqueInput(material: String, preQuestions: List<String>, explanation: String): String = buildString {
        appendLine("## 资料")
        appendLine(material.trim())
        appendLine()
        appendLine("## 学生的预习问题")
        preQuestions.forEachIndexed { i, q -> appendLine("${i + 1}. $q") }
        appendLine()
        appendLine("## 学生合上资料后的讲解")
        append(explanation.trim())
    }

    fun questionsInput(material: String, intervalDays: Int, previous: List<String>): String = buildString {
        appendLine("## 资料")
        appendLine(material.trim())
        appendLine()
        appendLine("## 本次自测")
        appendLine("距离上次学习约 $intervalDays 天。")
        appendLine()
        appendLine("## 以前出过的题")
        if (previous.isEmpty()) appendLine("（无）") else previous.forEach { appendLine("- $it") }
    }

    fun gradeInput(material: String, questions: List<String>, answers: List<String>): String = buildString {
        appendLine("## 资料")
        appendLine(material.trim())
        appendLine()
        appendLine("## 题目与学生作答")
        questions.forEachIndexed { i, q ->
            appendLine("第 ${i + 1} 题：$q")
            appendLine("作答：${answers.getOrNull(i)?.trim().orEmpty().ifBlank { "（未作答）" }}")
            appendLine()
        }
    }

    fun transferInput(title: String, material: String, example: String): String = buildString {
        appendLine("## 资料：$title")
        appendLine(material.trim())
        appendLine()
        appendLine("## 学生找的例子")
        append(example.trim())
    }
}
