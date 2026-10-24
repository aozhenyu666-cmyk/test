package cn.companion.pet.core.agent

import cn.companion.pet.core.action.ActionKind
import cn.companion.pet.core.action.ActionPlanner
import cn.companion.pet.core.action.ActionRequest
import cn.companion.pet.core.action.ActionResult
import cn.companion.pet.core.action.ActionRunner
import cn.companion.pet.core.action.ActionStatus
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * A1 最小决策循环：事件触发 -> 模型输出 tool_call/no_action -> 授权 -> 执行一次 ActionRunner
 * -> 回执生成可核对的话 -> 写决策轨迹。不循环自我调用，不自动重试副作用。
 */
class AgentLoop(
    private val source: AgentDecisionSource,
    private val runner: ActionRunner,
    private val budget: AgentBudgetPort,
    private val trace: AgentTraceSink,
    private val planner: ActionPlanner,
    private val timeoutMillis: Long = 15_000,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val pending: cn.companion.pet.core.action.PendingActionStore? = null,
) {
    @Volatile
    private var activeActionId: String? = null

    /** 只取消本轮正在执行的工具；模型请求由调用方的协程取消。 */
    fun cancel() {
        activeActionId?.let { runner.cancel(it) }
    }

    suspend fun run(
        context: AgentContext,
        autonomy: Boolean,
        cancelled: () -> Boolean = { false },
        stableTurnId: String? = null,
        /** 目标任务限定的工具；模型选了别的就拒绝，不执行。null 表示不限。 */
        allowedKinds: Set<ActionKind>? = null,
    ): AgentTurnResult {
        val turnId = stableTurnId ?: ("agent-" + idFactory())
        if (!autonomy) return finish(turnId, context, AgentTurnStatus.DISABLED, null, null, "", "自主模式未开启", 0)
        if (cancelled()) return finish(turnId, context, AgentTurnStatus.CANCELLED, null, null, "", "已取消", 0)
        if (context.budgetRemaining <= 0 || !budget.reserve(context.day)) {
            return finish(turnId, context, AgentTurnStatus.BUDGET_EXHAUSTED, null, null, "", "今日模型预算已用完", 0)
        }

        val content = try {
            withTimeout(timeoutMillis) { source.decide(context) }
        } catch (_: TimeoutCancellationException) {
            return finish(turnId, context, AgentTurnStatus.TIMEOUT, null, null, "", "模型决策超时", 1)
        } catch (_: CancellationException) {
            return finish(turnId, context, AgentTurnStatus.CANCELLED, null, null, "", "模型请求已取消", 1)
        } catch (_: Exception) {
            return finish(turnId, context, AgentTurnStatus.MODEL_FAILED, null, null, "", "模型请求失败", 1)
        }
        if (cancelled()) return finish(turnId, context, AgentTurnStatus.CANCELLED, null, null, "", "已取消", 1)
        if (content.isNullOrBlank()) return finish(turnId, context, AgentTurnStatus.MODEL_FAILED, null, null, "", "模型没有返回内容", 1)

        val decision = runCatching { AgentDecisionParser.parse(content) }
            .getOrElse { AgentDecision.Invalid("JSON 字段格式错误") }
        return when (decision) {
            is AgentDecision.Invalid -> finish(turnId, context, AgentTurnStatus.INVALID, decision, null, "", decision.reason, 1)
            is AgentDecision.NoAction -> finish(turnId, context, AgentTurnStatus.NO_ACTION, decision, null, "", decision.reason, 1)
            is AgentDecision.ToolCall -> {
                // 模型常常漏写 source_ids：这一轮本来就是被这条真实事件（用户的念头/素材）触发的，
                // 用它作来源即可；以前漏写就被静默拒绝，用户看到的是“没有任何反应”。
                val call = if (decision.sourceIds.isEmpty() && context.eventId != null)
                    decision.copy(sourceIds = listOf(context.eventId)) else decision
                val auth = if (allowedKinds != null && call.kind !in allowedKinds)
                    AgentAuthorization(false, false, "和目标的这一步不符")
                else AgentPolicy.authorize(call, autonomy, planner, context)
                if (!auth.allowed) {
                    finish(turnId, context, AgentTurnStatus.DENIED, call, null, "", auth.reason, 1)
                } else if (auth.requiresConfirmation) {
                    finish(turnId, context, AgentTurnStatus.NEEDS_CONFIRMATION, call, null,
                        AgentReport.needsConfirmation(call), auth.reason, 1)
                } else {
                    execute(turnId, context, call)
                }
            }
        }
    }

    private suspend fun execute(turnId: String, context: AgentContext, decision: AgentDecision.ToolCall): AgentTurnResult {
        val actionId = turnId + "-" + decision.kind.name.lowercase()
        activeActionId = actionId
        val result: ActionResult = try {
            runner.execute(ActionRequest(actionId, decision.kind, decision.args))
        } catch (_: Exception) {
            ActionResult(actionId, decision.kind, ActionStatus.FAILED, "工具执行异常")
        } finally {
            activeActionId = null
        }
        val status = if (result.status == ActionStatus.CANCELLED) AgentTurnStatus.CANCELLED else AgentTurnStatus.EXECUTED
        val fallback = AgentReport.text(decision, result)
        // 已取消就不再发第二次模型请求；回执如实说明可能已发生的副作用。
        val report = if (status == AgentTurnStatus.CANCELLED) fallback to 0
        else report(context, decision, result, fallback)
        return finish(turnId, context, status, decision, result, report.first, result.reason, 1 + report.second)
    }

    /**
     * 第二次、且只第二次模型调用：把有界回执交给模型总结/判断下一步。
     * 失败、超时或预算不足一律回退本地模板；绝不再发第三次。
     */
    private suspend fun report(
        context: AgentContext,
        decision: AgentDecision.ToolCall,
        result: ActionResult,
        fallback: String,
    ): Pair<String, Int> {
        if (!source.canReport) return fallback to 0
        if (budget.remaining(context.day) <= 0) return fallback to 0
        if (!budget.reserve(context.day)) return fallback to 0
        val content = try {
            withTimeout(timeoutMillis) { source.report(context, decision, result) }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (_: CancellationException) {
            null
        } catch (_: Exception) {
            null
        }
        if (content.isNullOrBlank()) return fallback to 1
        val lead = AgentReport.lead(result)
        return (lead + content.take(180)) to 1
    }

    private fun finish(
        turnId: String,
        context: AgentContext,
        status: AgentTurnStatus,
        decision: AgentDecision?,
        result: ActionResult?,
        text: String,
        reason: String,
        attempts: Int,
    ): AgentTurnResult {
        val tool = (decision as? AgentDecision.ToolCall)?.kind?.name
        val decisionReason = when (decision) {
            is AgentDecision.ToolCall -> decision.reason
            is AgentDecision.NoAction -> decision.reason
            is AgentDecision.Invalid -> decision.reason
            null -> ""
        }
        var pendingId: String? = null
        if (status == AgentTurnStatus.NEEDS_CONFIRMATION && decision is AgentDecision.ToolCall) {
            pendingId = runCatching {
                pending?.create(
                    kind = decision.kind,
                    args = decision.args,
                    preview = AgentReport.needsConfirmation(decision) + " | " + decision.args.entries.joinToString(", ") { it.key + "=" + it.value.take(60) },
                    // 绑定包名，不是显示名：批准时拿当前前台包名比对（审计 P0-1）。
                    packageName = context.foregroundPackage.orEmpty(),
                    sourceIds = decision.sourceIds,
                    now = context.now,
                    taskId = context.taskId.orEmpty(),
                )?.id
            }.getOrNull()
        }
        trace.append(AgentTrace(
            id = turnId,
            at = context.now,
            trigger = context.trigger.name,
            autonomy = status != AgentTurnStatus.DISABLED,
            status = status.name,
            tool = tool,
            decisionReason = decisionReason.take(160),
            actionId = result?.actionId,
            actionStatus = result?.status?.name,
            resultReason = reason.take(160),
            modelAttempts = attempts,
            eventId = context.eventId,
        ))
        return AgentTurnResult(turnId, status, decision, result, text, reason, attempts, pendingId)
    }
}
