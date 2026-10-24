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
)

data class AgentModelConfig(
    val endpoint: String,
    val model: String,
    val apiKey: String,
)

/** 决策来源：生产是 OpenAI 兼容模型，测试是固定 JSON；不负责预算和工具执行。 */
interface AgentDecisionSource {
    suspend fun decide(context: AgentContext): String?
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
    private val readOnly = setOf(ActionKind.READ_MEMORY, ActionKind.READ_MATERIALS)

    fun authorize(
        decision: AgentDecision.ToolCall,
        autonomy: Boolean,
        planner: ActionPlanner,
        now: Long,
        userActive: Boolean = true,
    ): AgentAuthorization {
        if (!autonomy) return AgentAuthorization(false, false, "自主模式未开启")
        if (decision.kind in readOnly) {
            return AgentAuthorization(true, false, "只读工具可自动执行")
        }
        if (decision.sourceIds.isEmpty()) {
            return AgentAuthorization(false, false, "写入/外部动作缺少真实来源")
        }
        val plan: ActionPlan = planner.decide(
            kind = decision.kind,
            sourceIds = decision.sourceIds,
            now = now,
            lastActionAt = 0L,
            quiet = false,
            userActive = userActive,
            budgetRemaining = 1,
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
        }
    }

    fun needsConfirmation(decision: AgentDecision.ToolCall): String =
        "我想" + label(decision.kind) + "，需要你确认；这次没有执行。"

    fun label(kind: ActionKind): String = when (kind) {
        ActionKind.READ_MEMORY -> "读取记忆"
        ActionKind.READ_MATERIALS -> "查看素材"
        ActionKind.SAVE_NOTE -> "保存念头"
        ActionKind.OPEN_ASSISTANT -> "打开 AI 应用"
        ActionKind.SPEAK -> "朗读"
    }
}
