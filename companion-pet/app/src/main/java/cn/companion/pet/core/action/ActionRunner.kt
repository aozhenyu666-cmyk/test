package cn.companion.pet.core.action

import java.util.concurrent.CompletableFuture

/** 第一批受限工具；不含无障碍写操作、Shell、外部评论或 QQ 群发。 */
enum class ActionKind {
    READ_MEMORY,
    READ_MATERIALS,
    SAVE_NOTE,
    OPEN_ASSISTANT,
    SPEAK,
}

enum class ActionStatus { SUCCESS, UNAVAILABLE, FAILED, CANCELLED }

data class ActionRequest(
    val id: String,
    val kind: ActionKind,
    val args: Map<String, String> = emptyMap(),
)

data class ActionResult(
    val actionId: String,
    val kind: ActionKind,
    val status: ActionStatus,
    val reason: String = "",
    val data: Map<String, String> = emptyMap(),
)

/** 真实执行或 fake 执行都走这个接口；结果必须能区分四种状态。 */
interface ActionAdapter {
    fun execute(request: ActionRequest): ActionResult
}

/**
 * 工具执行器：
 * - 同一个 execution id 只执行一次，重试返回同一结果（写操作不会重复保存/拉起）；
 * - 可取消；取消中的结果统一为 CANCELLED；
 * - 参数只接受白名单 key 和长度限制；
 * - 不持有模型预算；本轮没有模型工具调用，模型尝试与动作次数分开记录。
 */
class ActionRunner(
    private val adapters: Map<ActionKind, ActionAdapter>,
    private val maxIdLength: Int = 80,
) {
    private val lock = Any()
    private val results = HashMap<String, ActionResult>()
    private val cancelled = HashSet<String>()
    private val inFlight = HashMap<String, CompletableFuture<ActionResult>>()

    fun execute(request: ActionRequest): ActionResult {
        val future: CompletableFuture<ActionResult>
        val owner: Boolean
        synchronized(lock) {
            results[request.id]?.let { return it }
            if (request.id.isBlank() || request.id.length > maxIdLength) {
                return fail(request, "执行 ID 不合法")
            }
            if (request.id in cancelled) {
                val r = cancelled(request)
                results[request.id] = r
                return r
            }
            val existing = inFlight[request.id]
            if (existing != null) {
                future = existing
                owner = false
            } else {
                future = CompletableFuture()
                inFlight[request.id] = future
                owner = true
            }
        }
        if (!owner) return future.get()

        val result = try {
            validate(request) ?: runAdapter(request)
        } catch (t: Throwable) {
            fail(request, "执行异常：" + (t.message ?: "unknown").take(80))
        }
        val finalResult: ActionResult
        synchronized(lock) {
            finalResult = if (request.id in cancelled) cancelled(request) else result
            results[request.id] = finalResult
            inFlight.remove(request.id)
            future.complete(finalResult)
        }
        return finalResult
    }

    fun cancel(id: String): Boolean = synchronized(lock) {
        if (results.containsKey(id)) return false
        cancelled.add(id)
        true
    }

    /** 锁屏/离开界面时调用：正在执行的、尚未开始的动作都取消。 */
    fun cancelAll() {
        synchronized(lock) {
            cancelled.addAll(inFlight.keys)
        }
    }

    fun result(id: String): ActionResult? = synchronized(lock) { results[id] }

    private fun runAdapter(request: ActionRequest): ActionResult {
        val adapter = adapters[request.kind] ?: return ActionResult(
            request.id, request.kind, ActionStatus.UNAVAILABLE, "这个动作还没有可用执行器",
        )
        return adapter.execute(request)
    }

    private fun validate(request: ActionRequest): ActionResult? {
        val allowed = when (request.kind) {
            ActionKind.READ_MEMORY -> setOf("query", "limit")
            ActionKind.READ_MATERIALS -> setOf("limit")
            ActionKind.SAVE_NOTE -> setOf("text")
            ActionKind.OPEN_ASSISTANT -> setOf("target")
            ActionKind.SPEAK -> setOf("text")
        }
        if (request.args.keys.any { it !in allowed }) return fail(request, "动作参数不在白名单")
        val max = when (request.kind) {
            ActionKind.READ_MEMORY -> 200
            ActionKind.READ_MATERIALS -> 10
            ActionKind.SAVE_NOTE -> 500
            ActionKind.OPEN_ASSISTANT -> 40
            ActionKind.SPEAK -> 300
        }
        if (request.args.values.any { it.length > max * 4 }) return fail(request, "动作参数过长")
        if (request.kind == ActionKind.SAVE_NOTE && request.args["text"].isNullOrBlank()) return fail(request, "没有可保存的念头")
        if (request.kind == ActionKind.SPEAK && request.args["text"].isNullOrBlank()) return fail(request, "没有可播报的文字")
        return null
    }

    private fun fail(request: ActionRequest, reason: String): ActionResult =
        ActionResult(request.id, request.kind, ActionStatus.FAILED, reason)

    private fun cancelled(request: ActionRequest): ActionResult =
        ActionResult(request.id, request.kind, ActionStatus.CANCELLED, "已取消")
}

data class ActionPlan(
    val kind: ActionKind,
    val sourceIds: List<String>,
    val allowed: Boolean,
    val reason: String,
)

/**
 * 主动动作的第一轮决策：只做本地 dry-run，输出“准备做什么、依据哪个来源、为何现在做”。
 * 不启动模型循环，不直接执行。
 */
class ActionPlanner(
    private val cooldownMillis: Long = 10 * 60 * 1000L,
) {
    fun decide(
        kind: ActionKind,
        sourceIds: List<String>,
        now: Long,
        lastActionAt: Long,
        quiet: Boolean,
        userActive: Boolean,
        budgetRemaining: Int,
    ): ActionPlan {
        val reason = when {
            quiet -> "安静时段"
            !userActive -> "用户不在"
            budgetRemaining <= 0 -> "今日预算已用完"
            sourceIds.isEmpty() && kind != ActionKind.READ_MEMORY && kind != ActionKind.READ_MATERIALS -> "没有真实来源"
            lastActionAt > 0 && now - lastActionAt < cooldownMillis -> "冷却中"
            else -> "条件满足，可以进入下一步"
        }
        val allowed = reason == "条件满足，可以进入下一步"
        return ActionPlan(kind, sourceIds, allowed, reason)
    }
}
