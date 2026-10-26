package cn.companion.pet.core.training

/**
 * 内置示例题：明确标注为示例，不是真实题库。
 *
 * 使用虚构材料，不复用真实事故或争议事实，避免把事实争议混进逻辑练习。
 * 没有可靠题库时，UI 必须显示“内置示例”字样，不能冒充正式题源。
 *
 * 每题都带 rubricPoints（评分依据）与 nextStep（下一步），
 * 因为这两项要随题保存在训练快照里，恢复后才能继续用。
 *
 * 本批扩到 6 道，覆盖五种可判的认知动作，足以演示“五题换休息”：
 * 范围过度推广、因果倒置、样本偏差、以偏概全、用个例否定整体、以及一道开放题。
 */
object BuiltInQuestions {

    val SAMPLES: List<TrainingQuestion> = listOf(
        TrainingQuestion(
            id = "sample-scope-01",
            stem = "20 名自愿参加训练的人中，16 人一周后正确率提高，所以任何人使用该训练都会提高。",
            sentences = listOf(
                "20 名自愿参加训练的人中，16 人一周后正确率提高",
                "所以任何人使用该训练都会提高",
            ),
            premisePolicy = PremisePolicy.CLOSED,
            requiredSlots = listOf(SlotKind.LOCATE, SlotKind.CONNECT),
            builtInSample = true,
            rubricPoints = listOf("指出结论把范围推到了『任何人』", "说明 20 人样本支持不了所有人"),
            nextStep = "这句话只凭材料里的证据够不够支持『所有人都适用』？给一句依据。",
        ),
        TrainingQuestion(
            id = "sample-cause-01",
            stem = "某公司上了新的打卡软件后，迟到人数下降了 30%，所以是这个软件减少了迟到。",
            sentences = listOf(
                "上了新的打卡软件后，迟到人数下降了 30%",
                "所以是这个软件减少了迟到",
            ),
            premisePolicy = PremisePolicy.CLOSED,
            requiredSlots = listOf(SlotKind.LOCATE, SlotKind.CONNECT),
            builtInSample = true,
            rubricPoints = listOf("指出结论把先后顺序当成了因果", "说明可能有别的解释（比如同期改了考勤制度）"),
            nextStep = "有没有别的解释也能说明迟到下降？给一个。",
        ),
        TrainingQuestion(
            id = "sample-sample-01",
            stem = "我们只在一个城市的三个门店做了试用，转化率上升了 5%，所以全部门店都应该立刻推行。",
            sentences = listOf(
                "只在一个城市的三个门店做了试用，转化率上升了 5%",
                "所以全部门店都应该立刻推行",
            ),
            premisePolicy = PremisePolicy.CLOSED,
            requiredSlots = listOf(SlotKind.LOCATE, SlotKind.CONNECT),
            builtInSample = true,
            rubricPoints = listOf("指出样本只有三个门店、一个城市", "说明不同城市门店条件不同"),
            nextStep = "如果只能再测一个地方，你会选哪类门店？为什么？",
        ),
        TrainingQuestion(
            id = "sample-hasty-01",
            stem = "我认识的三个同事都在用这个笔记软件，都说好用，所以它一定比别的软件都好。",
            sentences = listOf(
                "我认识的三个同事都在用这个笔记软件，都说好用",
                "所以它一定比别的软件都好",
            ),
            premisePolicy = PremisePolicy.CLOSED,
            requiredSlots = listOf(SlotKind.LOCATE, SlotKind.CONNECT),
            builtInSample = true,
            rubricPoints = listOf("指出依据只有三个熟人", "说明熟人样本不能代表所有用户"),
            nextStep = "要判断它是不是更好，你会拿什么指标比？",
        ),
        TrainingQuestion(
            id = "sample-conflict-01",
            stem = "这本书说早睡一定让人更专注。我朋友早睡之后还是很困，所以这本书在胡说。",
            sentences = listOf(
                "这本书说早睡一定让人更专注",
                "我朋友早睡之后还是很困，所以这本书在胡说",
            ),
            premisePolicy = PremisePolicy.CLOSED,
            requiredSlots = listOf(SlotKind.LOCATE, SlotKind.CONNECT),
            builtInSample = true,
            rubricPoints = listOf("指出用一个个例否定整体结论", "说明个例与总体趋势可以并存"),
            nextStep = "一个反例能推翻『一定』吗？说清你的判断。",
        ),
        TrainingQuestion(
            id = "sample-open-01",
            stem = "某报告称某习惯让人更长寿。有人指出该报告只调查了城市白领。",
            sentences = listOf(
                "某报告称某习惯让人更长寿",
                "该报告只调查了城市白领",
            ),
            premisePolicy = PremisePolicy.OPEN,
            requiredSlots = listOf(SlotKind.LOCATE, SlotKind.CONNECT, SlotKind.EXAMINE),
            builtInSample = true,
            rubricPoints = listOf("指出调查对象只有城市白领", "说明结论能否外推到其他人未知"),
            nextStep = "要让它可外推，还需要补什么证据？",
        ),
    )

    /** 优先取没过的一题；都过过就按顺序取第一题。 */
    fun pick(answeredIds: Set<String>): TrainingQuestion =
        SAMPLES.firstOrNull { it.id !in answeredIds } ?: SAMPLES.first()

    /** 取若干道不同的题（用于题组换休息）。 */
    fun pickMany(answeredIds: Set<String>, count: Int): List<TrainingQuestion> {
        val remaining = SAMPLES.filter { it.id !in answeredIds }
        val base = if (remaining.size >= count) remaining else SAMPLES
        return base.take(count)
    }
}
