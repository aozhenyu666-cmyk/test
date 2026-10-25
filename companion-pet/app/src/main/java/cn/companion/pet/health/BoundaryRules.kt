package cn.companion.pet.health

/*
 * 边界纪律（报告 §3.5 / §1，本轮明确禁止的越界）。
 *
 * 这不是运行时代码，而是把「健康协议不许碰什么」写成可执行断言：
 * 一旦有人把认知评估、任务完成判定、对话状态塞进健康协议，这些测试会先红。
 *
 *  1. 健康协议只统计「运行与执行」——它没有任何输入是「任务是否完成」「回答是否合格」。
 *  2. 「专注计时结束」不等于「思考任务通过」：两者在数据上完全不连通。
 *  3. 收到用户一句回应不自动解除认知任务：健康/执行状态机里没有这种转换。
 *  4. 不接管同桌的认知评估：这里只有健康事实、规则、锁屏回执。
 */
object BoundaryRules {

    /** 健康协议能够观察到的事实清单（白名单）。新增字段必须先想清楚是否越界。 */
    val HEALTH_FACTS_WHITELIST: Set<String> = setOf(
        "installed", "permissionEnabled", "registered", "instanceId",
        "dispatcherAck", "eventProgress", "ruleRevision",
        "lastReceiptState", "lastReceiptAtMono", "lockCapability",
    )

    /** 明确不允许进入健康协议的东西。 */
    val FORBIDDEN_IN_HEALTH: Set<String> = setOf(
        "taskCompleted", "thinkingTaskPassed", "answerQuality", "cognitionScore",
        "conversationResolved", "userRepliedSoTaskDone", "screenText", "recordings",
    )

    /**
     * 专注计时结束只说明「这个时段的约束结束」。它不能推出思考任务通过。
     * 返回值永远 false：留成函数是为了让调用方必须显式写出来，而不是默认想当然。
     */
    fun focusExpiryImpliesTaskPassed(): Boolean = false

    /** 收到一句回应不自动解除认知任务。 */
    fun userReplyResolvesCognitiveTask(): Boolean = false
}
