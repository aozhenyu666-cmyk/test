package cn.companion.pet.core.agent

import cn.companion.pet.core.action.ActionKind
import cn.companion.pet.core.action.ActionPlan
import cn.companion.pet.core.action.ActionPlanner
import cn.companion.pet.core.action.ActionResult
import cn.companion.pet.core.action.ActionStatus
import kotlinx.serialization.Serializable

enum class AgentTrigger { MATERIAL_SAVED, NOTE_SAVED, PROACTIVE, USER_MESSAGE }

enum class AgentTurnStatus {
    EXECUTED,
    NO_ACTION,
    NEEDS_CONFIRMATION,
    DENIED,
    INVALID,
    BUDGET_EXHAUSTED,
    MODEL_FAILED,
    TIMEOUT,
    CANCELLED,
    DISABLED,
}

/** 模型只能输出两类结果；JSON 解析失败就是 Invalid，不猜测。 */
sealed interface AgentDecision {
    data class ToolCall(
        val kind: ActionKind,
        val args: Map<String, String>,
        val sourceIds: List<String>,
        val reason: String,
    ) : AgentDecision

    data class NoAction(val reason: String) : AgentDecision
    data class Invalid(val reason: String) : AgentDecision
}

/** 决策提示只带场景标签、可见记忆摘要和事件 ID；不带原始屏幕、聊天全文或 Key。 */
data class AgentContext(
    val trigger: AgentTrigger,
    val now: Long,
    val day: String,
    val scene: String,
    val foreground: String,
    val memoryLines: List<String>,
    val materialTitles: List<String>,
    val eventIds: List<String>,
    val budgetRemaining: Int,
    /** 本次触发对应的真实事件；写入/删除撤销后不能再用。 */
    val eventId: String? = null,
    val quiet: Boolean = false,
    val userActive: Boolean = true,
    val lastActionAt: Long = 0L,
    /** 最新一张有效观察的摘要；只是不可信材料，不是指令。 */
    val observation: String? = null,
    /** 当前活动目标的摘要；只作为计划背景，不是可直接执行的命令。 */
    val goalSummary: String? = null,
    val goalId: String? = null,
    val taskId: String? = null,
    /** 最近一次合法观察 ID；设备动作/待确认可绑定它，避免过期观察被复用。 */
    val observationId: String? = null,
)

data class AgentModelConfig(
    val endpoint: String,
    val model: String,
    val apiKey: String,
)

/** 决策来源：生产是 OpenAI 兼容模型，测试是固定 JSON；不负责预算和工具执行。 */
interface AgentDecisionSource {
    suspend fun decide(context: AgentContext): String?

    /** 是否会真的发第二次回执模型请求；默认不报告，避免测试/本地 fake 白占预算。 */
    val canReport: Boolean get() = false

    /** 受预算保护的一次回执总结；返回 null 时由 AgentLoop 使用本地如实模板。 */
    suspend fun report(context: AgentContext, decision: AgentDecision.ToolCall, result: ActionResult): String? = null
}

interface AgentBudgetPort {
    fun reserve(day: String): Boolean
    fun remaining(day: String): Int
}

interface AgentTraceSink {
    fun append(trace: AgentTrace)
}

@Serializable
data class AgentTrace(
    val id: String,
    val at: Long,
    val trigger: String,
    val autonomy: Boolean,
    val status: String,
    val tool: String? = null,
    val decisionReason: String = "",
    val actionId: String? = null,
    val actionStatus: String? = null,
    val resultReason: String = "",
    val modelAttempts: Int = 0,
    val eventId: String? = null,
)

data class AgentTurnResult(
    val turnId: String,
    val status: AgentTurnStatus,
    val decision: AgentDecision?,
    val execution: ActionResult?,
    val text: String,
    val reason: String,
    val modelAttempts: Int,
)

/** 只读工具可自动；写入、打开外部 App、朗读必须由用户确认，本轮不自动执行。 */
data class AgentAuthorization(val allowed: Boolean, val requiresConfirmation: Boolean, val reason: String)

object AgentPolicy {
    private val readOnly = setOf(
        ActionKind.READ_MEMORY, ActionKind.READ_MATERIALS,
        ActionKind.GET_DEVICE_STATE, ActionKind.FIND_NODE, ActionKind.GET_LAST_OBSERVATION,
    )

    fun authorize(
        decision: AgentDecision.ToolCall,
        autonomy: Boolean,
        planner: ActionPlanner,
        context: AgentContext,
    ): AgentAuthorization {
        if (!autonomy) return AgentAuthorization(false, false, "自主模式未开启")
        if (context.quiet) return AgentAuthorization(false, false, "安静时段")
        if (!context.userActive) return AgentAuthorization(false, false, "用户不在")
        if (context.budgetRemaining <= 0) return AgentAuthorization(false, false, "今日预算已用完")
        if (decision.kind in readOnly) {
            return AgentAuthorization(true, false, "只读工具可自动执行")
        }
        if (decision.sourceIds.isEmpty()) {
            return AgentAuthorization(false, false, "写入/外部动作缺少真实来源")
        }
        val authorized = context.eventIds.toSet()
        if (decision.sourceIds.any { it !in authorized }) {
            return AgentAuthorization(false, false, "来源不在本次授权上下文")
        }
        val plan: ActionPlan = planner.decide(
            kind = decision.kind,
            sourceIds = decision.sourceIds,
            now = context.now,
            lastActionAt = context.lastActionAt,
            quiet = context.quiet,
            userActive = context.userActive,
            budgetRemaining = context.budgetRemaining,
        )
        if (!plan.allowed) return AgentAuthorization(false, false, plan.reason)
        return AgentAuthorization(true, true, "需要用户明确确认")
    }
}

/** 用回执生成一句可核对的话；不让模型再自由发挥成功/失败。 */
object AgentReport {
    fun text(decision: AgentDecision.ToolCall, result: ActionResult): String {
        val ok = result.status == ActionStatus.SUCCESS
        if (!ok) return "我试了" + label(decision.kind) + "，但没成功：" + result.reason
        val observed = result.data["text"]?.replace("\n", " · ")?.take(160)
        return when (decision.kind) {
            ActionKind.READ_MEMORY -> "我看了下记住的事：" + (observed ?: result.reason)
            ActionKind.READ_MATERIALS -> "我看了下素材：" + (observed ?: result.reason)
            ActionKind.SAVE_NOTE -> "我把这个念头记下了。"
            ActionKind.OPEN_ASSISTANT -> "我打开了 " + (decision.args["target"] ?: "AI 应用") + "，但只能算系统接受了请求。"
            ActionKind.SPEAK -> "我提交了朗读；成功只代表系统引擎接受，不代表你已经听到。"
            ActionKind.GET_DEVICE_STATE -> "设备状态：" + (result.data["foreground"] ?: result.reason)
            ActionKind.FIND_NODE -> "找到：" + (result.data.values.firstOrNull() ?: result.reason)
            ActionKind.OPEN_APP -> "我请求打开应用；需要重新观察确认前台。"
            ActionKind.TAP_NODE -> "我点了控件；需要重新观察确认结果。"
            ActionKind.SCROLL -> "我滚动了页面；需要重新观察确认结果。"
            ActionKind.INPUT_TEXT -> "我写入了文字；需要重新观察确认输入框。"
            ActionKind.BACK -> "我请求返回；需要重新观察确认前台。"
            ActionKind.GET_LAST_OBSERVATION -> "最近观察：" + result.reason
        }
    }

    fun lead(result: ActionResult): String = when (result.status) {
        ActionStatus.SUCCESS -> "（回执：成功）"
        ActionStatus.UNAVAILABLE -> "（回执：暂不可用）"
        ActionStatus.CANCELLED -> "（回执：已取消；已发生的操作不回滚）"
        ActionStatus.FAILED -> "（回执：失败）"
    }

    fun needsConfirmation(decision: AgentDecision.ToolCall): String =
        "我想" + label(decision.kind) + "，需要你确认；这次没有执行。"

    fun label(kind: ActionKind): String = when (kind) {
        ActionKind.READ_MEMORY -> "读取记忆"
        ActionKind.READ_MATERIALS -> "查看素材"
        ActionKind.SAVE_NOTE -> "保存念头"
        ActionKind.OPEN_ASSISTANT -> "打开 AI 应用"
        ActionKind.SPEAK -> "朗读"
        ActionKind.GET_DEVICE_STATE -> "读取设备状态"
        ActionKind.FIND_NODE -> "查找控件"
        ActionKind.OPEN_APP -> "打开应用"
        ActionKind.TAP_NODE -> "点击控件"
        ActionKind.SCROLL -> "滚动页面"
        ActionKind.INPUT_TEXT -> "输入文字"
        ActionKind.BACK -> "返回"
        ActionKind.GET_LAST_OBSERVATION -> "读取最近观察"
    }
}
