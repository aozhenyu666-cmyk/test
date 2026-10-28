package com.yishou.app.healthwire

/**
 * 回包内容的**纯逻辑**构造（不碰 Bundle，方便 JVM 单测）。
 *
 * 为什么要单独抽出来：一手有「只读窗口包名、不读屏幕正文」的透明原则，
 * 以及「不同步任务/对话/记忆/认知评估」的边界（规范 §0 铁律 7）。
 * 把「回包里到底能放哪些字段」写成一份可测清单，比靠 code review 更可靠。
 *
 * [pingPayload] 与 [emergencyStopPayload] 白名单之外的键一律不允许出现；
 * 单测 `HealthProviderWireTest` 会逐个钉住。
 */
object ProviderWire {

    /** 协议版本（规范 §3.1/§3.2 冻结为 2）。 */
    const val PROTOCOL_VERSION = 2

    /**
     * ping 回包：只证明「provider 活着 + 是否处于暂停」。
     *
     * **明确不含**：屏幕文字 / 窗口内容、任务与断点、对话、集数与统计、认知评估结果。
     * 也**不是**健康凭据（健康只认 challenge 的 dispatcherAck，规范 §0 铁律 1）。
     */
    fun pingPayload(pkg: String, instanceId: String?, stopped: Boolean, atMono: Long): Map<String, Any?> =
        linkedMapOf(
            "ok" to true,
            "v" to PROTOCOL_VERSION,
            "pkg" to pkg,
            "instanceId" to (instanceId ?: ""),
            "stopped" to stopped,
            "atMono" to atMono,
            // 让哨兵一眼看出：provider 能响应 ≠ 无障碍在真的处理挑战。
            "providerAlive" to true,
        )

    /** emergencyStop 回包：ok + 已停止的 policyId 摘要 + 时间戳（给哨兵显示「已确认停止」）。 */
    fun emergencyStopPayload(
        pkg: String,
        policyIds: List<String>,
        revision: Int,
        atWall: Long,
        atMono: Long,
        alreadyStopped: Boolean,
        note: String,
    ): Map<String, Any?> = linkedMapOf(
        "ok" to true,
        "v" to PROTOCOL_VERSION,
        "pkg" to pkg,
        "stopped" to true,
        "alreadyStopped" to alreadyStopped,
        "policyIds" to policyIds.joinToString(","),
        "stoppedCount" to policyIds.size,
        "ruleRevision" to revision,
        "atWall" to atWall,
        "atMono" to atMono,
        "note" to note,
    )

    /**
     * 白名单：ping / emergencyStop 允许出现在回包里的键。
     * 加字段必须先加进这里，并想清楚「这是不是一手的透明原则允许的信息」。
     */
    val PING_KEYS: Set<String> = setOf(
        "ok", "v", "pkg", "instanceId", "stopped", "atMono", "providerAlive",
    )

    val EMERGENCY_KEYS: Set<String> = setOf(
        "ok", "v", "pkg", "stopped", "alreadyStopped", "policyIds", "stoppedCount",
        "ruleRevision", "atWall", "atMono", "note",
    )

    /** 绝不允许出现在任何回包里的键（透明原则 / 边界）。 */
    val FORBIDDEN_KEYS: Set<String> = setOf(
        "screenText", "windowText", "contentText", "text", "content", "ocr",
        "taskTitle", "taskGoal", "breakdown", "breakpoint", "rounds", "answer",
        "conversation", "memory", "cognition", "cognitive", "summary", "note_text",
    )
}
