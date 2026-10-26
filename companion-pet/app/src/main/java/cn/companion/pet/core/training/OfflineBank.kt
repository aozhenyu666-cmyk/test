package cn.companion.pet.core.training

/**
 * 离线可判题库：固定材料、固定判据、固定选项，本地确定性评分。
 *
 * 来源：全部为原创虚构材料（AI 编写，**待用户人工核对**），不是真题，不复用真实事件。
 * 每一类（family）有两道同类题：做第一道时若答案被选项“说破”，就用另一道当迁移题。
 *
 * 判分只认“完全匹配的选项或列出的极简答案”（忽略空格和标点）。
 * 没列出的说法一律要求澄清，不冒充通用语义评分——宁可误拒，也不误发休息资格。
 */
data class OfflineItem(
    val question: TrainingQuestion,
    /** 同一类缺陷的题共享 family；迁移题从同 family 里找没见过的。 */
    val family: String,
    /** 目标认知动作。 */
    val action: String,
    val locateAsk: String,
    /** 支架选项：第一项不一定是正确项。 */
    val locateOptions: List<String>,
    val locateCorrect: String,
    val locateAccepted: Set<String>,
    val locateEvidence: List<String>,
    val connectAsk: String,
    val connectOptions: List<String>,
    val connectCorrect: String,
    val connectAccepted: Set<String>,
    val connectEvidence: List<String>,
    /** 常见误解：即错误选项想引出的想法。 */
    val misconception: String,
    val hint: String,
    val source: String = "原创（AI 编写，待人工核对）",
    val rubricVersion: Int = 1,
) {
    fun ask(slot: SlotKind): String? = when (slot) {
        SlotKind.LOCATE -> locateAsk
        SlotKind.CONNECT -> connectAsk
        SlotKind.EXAMINE -> null
    }

    fun options(slot: SlotKind?): List<String>? = when (slot) {
        SlotKind.LOCATE -> locateOptions
        SlotKind.CONNECT -> connectOptions
        else -> null
    }

    fun accepts(slot: SlotKind, answer: String): Boolean {
        val n = OfflineBank.normalize(answer)
        if (n.isEmpty()) return false
        val set = when (slot) {
            SlotKind.LOCATE -> locateAccepted + locateCorrect
            SlotKind.CONNECT -> connectAccepted + connectCorrect
            SlotKind.EXAMINE -> return false
        }
        return set.any { OfflineBank.normalize(it) == n }
    }

    fun evidence(slot: SlotKind): List<String> = when (slot) {
        SlotKind.LOCATE -> locateEvidence
        SlotKind.CONNECT -> connectEvidence
        SlotKind.EXAMINE -> emptyList()
    }
}

object OfflineBank {

    private val PUNCT = Regex("[\\s，。！？、；：,.!?;:\"“”'‘’（）()…]+")

    fun normalize(s: String): String = s.replace(PUNCT, "").trim()

    private fun q(id: String, stem: String, sentences: List<String>) = TrainingQuestion(
        id = id,
        stem = stem,
        sentences = sentences,
        premisePolicy = PremisePolicy.CLOSED,
        requiredSlots = listOf(SlotKind.LOCATE, SlotKind.CONNECT),
        builtInSample = true,
    )

    val ITEMS: List<OfflineItem> = listOf(
        // ---- 以偏概全：样本只来自自愿/主动的人，结论却推到所有人 ----
        OfflineItem(
            question = BuiltInQuestions.SAMPLES.first(),
            family = "scope",
            action = "找出过度推广的范围词，并说出样本缺口",
            locateAsk = "结论把效果推广到了谁？请指出原文里的范围词。",
            locateOptions = listOf("任何人", "16 人"),
            locateCorrect = "任何人",
            locateAccepted = setOf("任何人", "所有人", "范围词是任何人", "结论是任何人都会提高"),
            locateEvidence = listOf("任何人"),
            connectAsk = "只调查了自愿参加的人，能保证任何人都会提高吗？选出依据，或说出缺口。",
            connectOptions = listOf("自愿参加的样本不能代表任何人", "只要多数人提高，就能保证任何人提高"),
            connectCorrect = "自愿参加的样本不能代表任何人",
            connectAccepted = setOf("样本不能代表所有人", "自愿参加者不代表所有人"),
            connectEvidence = listOf("20 名自愿参加训练的人", "任何人使用该训练都会提高"),
            misconception = "多数人有效 = 对所有人有效",
            hint = "请指出材料中的范围或样本缺口；离线核验也可以直接选一个选项。",
        ),
        OfflineItem(
            question = q(
                "bank-scope-02",
                "某读书会 30 名主动报名的会员试用了新笔记法，其中 25 人的测验成绩提高了，所以所有学生都应该改用这种笔记法。",
                listOf("某读书会 30 名主动报名的会员试用了新笔记法，其中 25 人的测验成绩提高了", "所以所有学生都应该改用这种笔记法"),
            ),
            family = "scope",
            action = "找出过度推广的范围词，并说出样本缺口",
            locateAsk = "结论把适用对象推广到了谁？请指出原文里的范围词。",
            locateOptions = listOf("25 人", "所有学生"),
            locateCorrect = "所有学生",
            locateAccepted = setOf("所有", "范围词是所有学生"),
            locateEvidence = listOf("所有学生"),
            connectAsk = "只有主动报名的读书会会员试过，能推出所有学生都适用吗？选出依据，或说出缺口。",
            connectOptions = listOf("多数会员成绩提高，就说明所有学生都适用", "主动报名的会员不能代表所有学生"),
            connectCorrect = "主动报名的会员不能代表所有学生",
            connectAccepted = setOf("样本不能代表所有学生", "报名者不代表所有学生", "样本不能代表所有人"),
            connectEvidence = listOf("30 名主动报名的会员", "所有学生都应该改用这种笔记法"),
            misconception = "多数人有效 = 对所有人有效",
            hint = "请指出试用者是谁、结论又推给了谁；也可以直接选一个选项。",
        ),
        // ---- 先后 ≠ 因果：只比了前后，没排除别的原因 ----
        OfflineItem(
            question = q(
                "bank-cause-01",
                "小区装了新路灯的那个月，夜间失窃案比上个月少了一半，所以是路灯让失窃变少了。",
                listOf("小区装了新路灯的那个月，夜间失窃案比上个月少了一半", "所以是路灯让失窃变少了"),
            ),
            family = "cause",
            action = "找出因果判断，并说出没排除的其他原因",
            locateAsk = "结论认定了什么是原因？请指出原文里下因果判断的那句。",
            locateOptions = listOf("是路灯让失窃变少了", "比上个月少了一半"),
            locateCorrect = "是路灯让失窃变少了",
            locateAccepted = setOf("路灯让失窃变少了", "所以是路灯让失窃变少了"),
            locateEvidence = listOf("是路灯让失窃变少了"),
            connectAsk = "只比了前后两个月，能排除别的原因吗？选出依据，或说出缺口。",
            connectOptions = listOf("只比了前后两个月，不能排除别的原因", "失窃变少和装路灯同时发生，就说明路灯是原因"),
            connectCorrect = "只比了前后两个月，不能排除别的原因",
            connectAccepted = setOf("可能有别的原因", "没有排除其他原因", "没排除别的原因", "同时期可能有别的变化"),
            connectEvidence = listOf("装了新路灯的那个月", "比上个月少了一半"),
            misconception = "前后发生 = 有因果",
            hint = "请说出这里没排除的东西；也可以直接选一个选项。",
        ),
        OfflineItem(
            question = q(
                "bank-cause-02",
                "学校食堂换了新菜单后的那一周，学生请病假的人数减少了，所以是新菜单让学生更健康了。",
                listOf("学校食堂换了新菜单后的那一周，学生请病假的人数减少了", "所以是新菜单让学生更健康了"),
            ),
            family = "cause",
            action = "找出因果判断，并说出没排除的其他原因",
            locateAsk = "结论认定了什么是原因？请指出原文里下因果判断的那句。",
            locateOptions = listOf("请病假的人数减少了", "是新菜单让学生更健康了"),
            locateCorrect = "是新菜单让学生更健康了",
            locateAccepted = setOf("新菜单让学生更健康了", "所以是新菜单让学生更健康了"),
            locateEvidence = listOf("是新菜单让学生更健康了"),
            connectAsk = "只看了换菜单后的一周，能排除别的原因吗？选出依据，或说出缺口。",
            connectOptions = listOf("请假变少紧跟在换菜单之后，就说明菜单是原因", "只看了一周，不能排除天气等别的原因"),
            connectCorrect = "只看了一周，不能排除天气等别的原因",
            connectAccepted = setOf("可能有别的原因", "没有排除其他原因", "没排除别的原因", "同时期可能有别的变化"),
            connectEvidence = listOf("换了新菜单后的那一周", "请病假的人数减少了"),
            misconception = "前后发生 = 有因果",
            hint = "请说出这里没排除的东西；也可以直接选一个选项。",
        ),
        // ---- 样本太小：几个人的反馈推出“一定/绝对” ----
        OfflineItem(
            question = q(
                "bank-size-01",
                "一家奶茶店请 3 位顾客试喝新品，3 人都说好喝，所以这款新品一定会大卖。",
                listOf("一家奶茶店请 3 位顾客试喝新品，3 人都说好喝", "所以这款新品一定会大卖"),
            ),
            family = "size",
            action = "找出说满的词，并说出样本量缺口",
            locateAsk = "结论下得有多肯定？请指出原文里把话说满的词。",
            locateOptions = listOf("一定会大卖", "3 人都说好喝"),
            locateCorrect = "一定会大卖",
            locateAccepted = setOf("一定"),
            locateEvidence = listOf("一定会大卖"),
            connectAsk = "只问了 3 个人，能保证一定大卖吗？选出依据，或说出缺口。",
            connectOptions = listOf("只问了 3 个人，样本太小", "3 个人全说好，说明所有人都会喜欢"),
            connectCorrect = "只问了 3 个人，样本太小",
            connectAccepted = setOf("样本太小", "3个人太少", "人数太少", "人数太少不能保证"),
            connectEvidence = listOf("请 3 位顾客试喝新品", "一定会大卖"),
            misconception = "几个人都说好 = 一定成功",
            hint = "请说出样本有什么问题；也可以直接选一个选项。",
        ),
        OfflineItem(
            question = q(
                "bank-size-02",
                "某 App 让 5 名员工内测新功能，5 人都没发现问题，所以这个功能上线后绝对不会出错。",
                listOf("某 App 让 5 名员工内测新功能，5 人都没发现问题", "所以这个功能上线后绝对不会出错"),
            ),
            family = "size",
            action = "找出说满的词，并说出样本量缺口",
            locateAsk = "结论下得有多肯定？请指出原文里把话说满的词。",
            locateOptions = listOf("5 人都没发现问题", "绝对不会出错"),
            locateCorrect = "绝对不会出错",
            locateAccepted = setOf("绝对"),
            locateEvidence = listOf("绝对不会出错"),
            connectAsk = "只有 5 名员工测过，能保证绝对不出错吗？选出依据，或说出缺口。",
            connectOptions = listOf("5 人都没发现问题，说明不可能有问题", "只有 5 名员工测试，样本太小"),
            connectCorrect = "只有 5 名员工测试，样本太小",
            connectAccepted = setOf("样本太小", "5个人太少", "人数太少", "人数太少不能保证"),
            connectEvidence = listOf("让 5 名员工内测新功能", "绝对不会出错"),
            misconception = "几个人没发现问题 = 绝对没问题",
            hint = "请说出样本有什么问题；也可以直接选一个选项。",
        ),
        // ---- 幸存者偏差：只看成功的人 ----
        OfflineItem(
            question = q(
                "bank-surv-01",
                "杂志采访了 10 位辍学后创业成功的老板，所以辍学更容易创业成功。",
                listOf("杂志采访了 10 位辍学后创业成功的老板", "所以辍学更容易创业成功"),
            ),
            family = "survivor",
            action = "找出结论，并说出被漏掉的那群人",
            locateAsk = "这段话得出了什么判断？请指出原文里的结论。",
            locateOptions = listOf("辍学更容易创业成功", "10 位辍学后创业成功的老板"),
            locateCorrect = "辍学更容易创业成功",
            locateAccepted = setOf("所以辍学更容易创业成功"),
            locateEvidence = listOf("辍学更容易创业成功"),
            connectAsk = "只采访了成功的人，能说明辍学更容易成功吗？选出依据，或说出缺口。",
            connectOptions = listOf("只采访了成功的人，没看辍学后失败的人", "10 个成功例子足以说明辍学更容易成功"),
            connectCorrect = "只采访了成功的人，没看辍学后失败的人",
            connectAccepted = setOf("只看了成功的人", "没看失败的人", "没有统计辍学后失败的人", "只采访成功者"),
            connectEvidence = listOf("10 位辍学后创业成功的老板", "辍学更容易创业成功"),
            misconception = "看到的成功例子 = 全部情况",
            hint = "请说出材料漏掉了哪群人；也可以直接选一个选项。",
        ),
        OfflineItem(
            question = q(
                "bank-surv-02",
                "论坛里晒出的 50 位炒股赚钱的网友都用了同一个软件，所以用这个软件更容易赚钱。",
                listOf("论坛里晒出的 50 位炒股赚钱的网友都用了同一个软件", "所以用这个软件更容易赚钱"),
            ),
            family = "survivor",
            action = "找出结论，并说出被漏掉的那群人",
            locateAsk = "这段话得出了什么判断？请指出原文里的结论。",
            locateOptions = listOf("50 位炒股赚钱的网友", "用这个软件更容易赚钱"),
            locateCorrect = "用这个软件更容易赚钱",
            locateAccepted = setOf("所以用这个软件更容易赚钱"),
            locateEvidence = listOf("用这个软件更容易赚钱"),
            connectAsk = "只看了晒出来赚钱的人，能说明软件让人赚钱吗？选出依据，或说出缺口。",
            connectOptions = listOf("50 个人都赚了，说明软件有效", "只看了晒出来赚钱的人，没看亏钱的人"),
            connectCorrect = "只看了晒出来赚钱的人，没看亏钱的人",
            connectAccepted = setOf("只看了赚钱的人", "没看亏钱的人", "没有统计用了软件亏钱的人", "只看成功者"),
            connectEvidence = listOf("晒出的 50 位炒股赚钱的网友", "用这个软件更容易赚钱"),
            misconception = "看到的成功例子 = 全部情况",
            hint = "请说出材料漏掉了哪群人；也可以直接选一个选项。",
        ),
        // ---- 缺对照：只看做了的人变好了 ----
        OfflineItem(
            question = q(
                "bank-compare-01",
                "参加夏令营的孩子开学后成绩平均提高了 8 分，所以夏令营提高了成绩。",
                listOf("参加夏令营的孩子开学后成绩平均提高了 8 分", "所以夏令营提高了成绩"),
            ),
            family = "control",
            action = "找出结论，并说出缺少的对比",
            locateAsk = "结论认定了什么是原因？请指出原文里下判断的那句。",
            locateOptions = listOf("成绩平均提高了 8 分", "夏令营提高了成绩"),
            locateCorrect = "夏令营提高了成绩",
            locateAccepted = setOf("所以夏令营提高了成绩"),
            locateEvidence = listOf("夏令营提高了成绩"),
            connectAsk = "没参加夏令营的孩子成绩变了多少，材料说了吗？选出依据，或说出缺口。",
            connectOptions = listOf("没有和没参加的孩子比较", "提高了 8 分，就足以说明是夏令营的作用"),
            connectCorrect = "没有和没参加的孩子比较",
            connectAccepted = setOf("缺少对照组", "没有对照组", "没对比没参加的孩子", "没参加的孩子可能也提高了"),
            connectEvidence = listOf("参加夏令营的孩子开学后成绩平均提高了 8 分", "夏令营提高了成绩"),
            misconception = "做了之后变好 = 是它的作用",
            hint = "请说出缺了什么比较；也可以直接选一个选项。",
        ),
        OfflineItem(
            question = q(
                "bank-compare-02",
                "吃了某维生素的老人一年后感冒次数减少了，所以这种维生素能预防感冒。",
                listOf("吃了某维生素的老人一年后感冒次数减少了", "所以这种维生素能预防感冒"),
            ),
            family = "control",
            action = "找出结论，并说出缺少的对比",
            locateAsk = "结论认定了什么是原因？请指出原文里下判断的那句。",
            locateOptions = listOf("这种维生素能预防感冒", "感冒次数减少了"),
            locateCorrect = "这种维生素能预防感冒",
            locateAccepted = setOf("所以这种维生素能预防感冒", "维生素能预防感冒"),
            locateEvidence = listOf("这种维生素能预防感冒"),
            connectAsk = "没吃维生素的老人感冒次数变了多少，材料说了吗？选出依据，或说出缺口。",
            connectOptions = listOf("感冒变少了，就说明是维生素的作用", "没有和没吃维生素的老人比较"),
            connectCorrect = "没有和没吃维生素的老人比较",
            connectAccepted = setOf("缺少对照组", "没有对照组", "没对比没吃的老人", "没吃的老人可能也减少了"),
            connectEvidence = listOf("吃了某维生素的老人一年后感冒次数减少了", "这种维生素能预防感冒"),
            misconception = "做了之后变好 = 是它的作用",
            hint = "请说出缺了什么比较；也可以直接选一个选项。",
        ),
    )

    private val byId: Map<String, OfflineItem> = ITEMS.associateBy { it.question.id }

    /** 只认题号和题干都一致的题：断点里的题被改过就不走本地判分。 */
    fun item(q: TrainingQuestion): OfflineItem? = byId[q.id]?.takeIf { it.question == q }

    fun byId(id: String): OfflineItem? = byId[id]

    val families: List<String> get() = ITEMS.map { it.family }.distinct()
}
