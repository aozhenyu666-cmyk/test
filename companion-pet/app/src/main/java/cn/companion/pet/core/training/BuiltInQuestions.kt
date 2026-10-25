package cn.companion.pet.core.training

/**
 * 内置示例题：明确标注为示例，不是真实题库。
 *
 * 使用虚构材料，不复用真实事故或争议事实，避免把事实争议混进逻辑练习。
 * 没有可靠题库时，UI 必须显示“内置示例”字样，不能冒充正式题源。
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
        ),
        TrainingQuestion(
            id = "sample-open-01",
            stem = "某报告称某习惯让人更长寿。有人指出该报告只调查了城市白领。" ,
            sentences = listOf(
                "某报告称某习惯让人更长寿",
                "该报告只调查了城市白领",
            ),
            premisePolicy = PremisePolicy.OPEN,
            requiredSlots = listOf(SlotKind.LOCATE, SlotKind.CONNECT, SlotKind.EXAMINE),
            builtInSample = true,
        ),
    )

    /** 优先取没过的一题；都过过就按顺序取第一题。 */
    fun pick(answeredIds: Set<String>): TrainingQuestion =
        SAMPLES.firstOrNull { it.id !in answeredIds } ?: SAMPLES.first()
}
