package cn.companion.pet.core.action

import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ActionPhase { IDLE, PENDING, RUNNING, FINISHED }
data class ActionSessionState(
    val phase: ActionPhase = ActionPhase.IDLE,
    val request: ActionRequest? = null,
    val result: ActionResult? = null,
) {
    val busy get() = phase == ActionPhase.PENDING || phase == ActionPhase.RUNNING
}

/** One UI owner's work. A new explicit submission gets a new identity; checking never executes. */
class ActionSession(
    private val runner: ActionRunner,
    private val executor: Executor,
    private val stopSpeech: (String) -> Unit = {},
) : AutoCloseable {
    private val lock = Any()
    private val mutable = MutableStateFlow(ActionSessionState())
    val state: StateFlow<ActionSessionState> = mutable
    private var closed = false
    private var generation = 0L
    private var executionId: String? = null
    private val speechIds = mutableSetOf<String>()

    fun submit(kind: ActionKind, args: Map<String, String>): Boolean = synchronized(lock) {
        if (closed || mutable.value.busy) return false
        val request = ActionRequest(UUID.randomUUID().toString(), kind, args.toMap())
        val ticket = ++generation
        executionId = request.id
        if (kind == ActionKind.SPEAK) speechIds += request.id
        mutable.value = ActionSessionState(ActionPhase.PENDING, request)
        try {
            executor.execute {
                synchronized(lock) {
                    if (closed || generation != ticket) return@execute
                    mutable.value = ActionSessionState(ActionPhase.RUNNING, request)
                }
                val result = runner.execute(request)
                synchronized(lock) {
                    if (!closed && generation == ticket) mutable.value = ActionSessionState(ActionPhase.FINISHED, request, result)
                }
            }
        } catch (_: RuntimeException) {
            mutable.value = ActionSessionState(ActionPhase.FINISHED, request,
                ActionResult(request.id, kind, ActionStatus.FAILED, "无法安排后台任务"))
        }
        true
    }

    /** Lookup only: an interrupted write must never be submitted again as a way to check it. */
    fun check(request: ActionRequest) = synchronized(lock) {
        if (closed || mutable.value.busy) return
        val ticket = ++generation
        executionId = null // looking up an ID never transfers ownership of its original execution
        mutable.value = ActionSessionState(ActionPhase.PENDING, request)
        try {
            executor.execute {
                synchronized(lock) {
                    if (closed || generation != ticket) return@execute
                    mutable.value = ActionSessionState(ActionPhase.RUNNING, request)
                }
                val result = runCatching { runner.result(request.id) }.getOrNull() ?: ActionResult(request.id, request.kind,
                    ActionStatus.FAILED, "结果未确认，请先核对；不会自动重做")
                synchronized(lock) {
                    if (!closed && generation == ticket) mutable.value = ActionSessionState(ActionPhase.FINISHED, request, result)
                }
            }
        } catch (_: RuntimeException) {
            mutable.value = ActionSessionState(ActionPhase.FINISHED, request,
                ActionResult(request.id, request.kind, ActionStatus.FAILED, "暂时无法核对执行记录，请稍后再查"))
        }
    }

    fun cancel() = synchronized(lock) {
        val previous = mutable.value
        if (previous.busy) {
            val request = previous.request ?: return
            ++generation // queued and late results cannot update a cancelled UI operation
            val cancelled = executionId?.let(runner::cancel) ?: false
            mutable.value = ActionSessionState(ActionPhase.FINISHED, request,
                if (!cancelled) runner.peekResult(request.id) else null)
                .let { if (it.result != null) it else it.copy(result = ActionResult(request.id, request.kind,
                    ActionStatus.CANCELLED, "已请求取消；已发生的操作不回滚")) }
        }
        speechIds.forEach(stopSpeech)
        speechIds.clear()
    }

    override fun close() = synchronized(lock) { cancel(); closed = true; ++generation; Unit }
}
