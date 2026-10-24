package cn.companion.pet.core.action

import java.util.concurrent.CompletableFuture

/** 第一批受限工具；不含无障碍写操作、Shell、外部评论或 QQ 群发。 */
@kotlinx.serialization.Serializable
enum class ActionKind {
    READ_MEMORY,
    READ_MATERIALS,
    SAVE_NOTE,
    OPEN_ASSISTANT,
    SPEAK,
    GET_DEVICE_STATE,
    FIND_NODE,
    OPEN_APP,
    TAP_NODE,
    SCROLL,
    INPUT_TEXT,
    BACK,
    GET_LAST_OBSERVATION,
}

@kotlinx.serialization.Serializable
enum class ActionStatus { SUCCESS, UNAVAILABLE, FAILED, CANCELLED }

data class ActionRequest(
    val id: String,
    val kind: ActionKind,
    val args: Map<String, String> = emptyMap(),
)

@kotlinx.serialization.Serializable
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
    fun execute(request: ActionRequest, cancellation: ActionCancellation): ActionResult = execute(request)
    /** Inspect an existing durable effect; never execute the original effect again. */
    fun recover(receipt: WriteReceipt): ActionResult? = null
}

/** Cooperative cancellation delivered to adapters; already committed effects are not rolled back. */
class ActionCancellation {
    private val lock = Any()
    @Volatile var isCancelled: Boolean = false
        private set
    private val callbacks = mutableListOf<() -> Unit>()

    fun throwIfCancelled() {
        if (isCancelled) throw java.util.concurrent.CancellationException("Action cancelled")
    }

    fun onCancel(callback: () -> Unit): () -> Unit {
        val callNow = synchronized(lock) {
            if (isCancelled) true else { callbacks += callback; false }
        }
        if (callNow) callback()
        return { synchronized(lock) { callbacks.remove(callback) }; Unit }
    }

    internal fun cancel() {
        val pending = synchronized(lock) {
            if (isCancelled) return
            isCancelled = true
            callbacks.toList().also { callbacks.clear() }
        }
        pending.forEach { runCatching { it() } }
    }
}

/**
 * Process-local write deduplication, fresh reads, bounded identity history, and cooperative cancellation.
 * This is a synchronous API: Android callers dispatch it off the main thread and own their UI scope.
 * Optional journal protects writes across process death. Reads always use current visibility.
 */
class ActionRunner(
    private val adapters: Map<ActionKind, ActionAdapter>,
    private val maxIdLength: Int = 80,
    private val maxRememberedActions: Int = 2048,
    private val journal: FileActionJournal? = null,
) {
    private data class Entry(
        val request: ActionRequest,
        val future: CompletableFuture<ActionResult> = CompletableFuture(),
        val cancellation: ActionCancellation = ActionCancellation(),
    )
    private val lock = Any()
    private val results = HashMap<String, ActionResult>()
    private val cancelledIds = HashSet<String>()
    private val fingerprints = HashMap<String, String>()
    private val inFlight = HashMap<String, Entry>()

    fun execute(request: ActionRequest): ActionResult {
        val frozen = try { request.copy(args = java.util.Collections.unmodifiableMap(LinkedHashMap(request.args))) }
        catch (_: Exception) { return fail(request, "动作参数无法读取") }
        if (frozen.id.isBlank() || frozen.id.length > maxIdLength) return fail(frozen, "执行 ID 不合法")
        val fingerprint = fingerprint(frozen)
        val entry: Entry
        val owner: Boolean
        synchronized(lock) {
            val previous = fingerprints[frozen.id]
            if (previous != null && previous != fingerprint) return fail(frozen, "同一执行 ID 不能更换动作或参数")
            if (previous == null) {
                if ((fingerprints.keys + cancelledIds).size >= maxRememberedActions) return fail(frozen, "本次运行的动作记录已达上限")
                fingerprints[frozen.id] = fingerprint
            }
            results[frozen.id]?.let { return it }
            if (frozen.id in cancelledIds) return cancelled(frozen)
            val existing = inFlight[frozen.id]
            if (existing != null) { entry = existing; owner = false }
            else { entry = Entry(frozen); inFlight[frozen.id] = entry; owner = true }
        }
        if (!owner) return try { entry.future.get() } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cancelled(frozen)
        }
        val result = try {
            entry.cancellation.throwIfCancelled()
            validate(frozen) ?: runDurable(frozen, fingerprint, entry.cancellation).let {
                if (it.actionId == frozen.id && it.kind == frozen.kind) it else fail(frozen, "执行器返回的动作身份不一致")
            }
        } catch (_: java.util.concurrent.CancellationException) {
            cancelled(frozen)
        } catch (_: Exception) {
            // Exception text may contain private paths or adapter arguments; keep it out of results.
            fail(frozen, "动作执行失败，请重试或检查能力是否可用")
        }
        return synchronized(lock) {
            val final = if (frozen.id in cancelledIds && (journal == null || isRead(frozen.kind))) cancelled(frozen) else result
            // Reads must use the current visibility snapshot after an edit/forget, never cached content.
            if (!isRead(frozen.kind) && final.data["needsReview"] != "true") results[frozen.id] = final
            else results.remove(frozen.id)
            inFlight.remove(frozen.id)
            entry.future.complete(final)
            final
        }
    }

    fun cancel(id: String): Boolean {
        val entry = synchronized(lock) {
            if (id.isBlank() || id.length > maxIdLength || results.containsKey(id)) return false
            if (id !in fingerprints && id !in cancelledIds && (fingerprints.keys + cancelledIds).size >= maxRememberedActions) return false
            cancelledIds.add(id)
            inFlight[id]?.also {
                val result = if (journal != null && !isRead(it.request.kind)) cancelled(it.request).copy(
                    reason = "已请求取消，操作结果需要核对；已发生的操作不回滚", data = mapOf("needsReview" to "true")) else cancelled(it.request)
                if (!isRead(it.request.kind)) results[id] = result
                it.future.complete(result)
            }
        }
        entry?.cancellation?.cancel()
        return true
    }

    /** Cancels registered work, including work waiting on an adapter. New calls need their own scope. */
    fun cancelAll() {
        val ids = synchronized(lock) { inFlight.keys.toList() }
        ids.forEach { cancel(it) }
    }

    fun cancelKind(kind: ActionKind) {
        val ids = synchronized(lock) { inFlight.filterValues { it.request.kind == kind }.keys.toList() }
        ids.forEach { cancel(it) }
    }

    /** May inspect/repair an already saved note receipt; never repeats the original action. Off-main. */
    fun result(id: String): ActionResult? {
        val store = journal ?: return peekResult(id)
        val receipt = store.receipt(id) ?: return peekResult(id)
        receipt.result?.let { return it }
        return try {
            val recovered = adapters[receipt.kind]?.recover(receipt)
                ?: return FileActionJournal.unknown(id, receipt.kind)
            if (recovered.actionId != id || recovered.kind != receipt.kind || recovered.data["needsReview"] == "true")
                return FileActionJournal.unknown(id, receipt.kind)
            store.commit(ActionRequest(id, receipt.kind), receipt.fingerprint, recovered)
        } catch (_: Exception) { FileActionJournal.unknown(id, receipt.kind) }
    }

    internal fun peekResult(id: String): ActionResult? = synchronized(lock) { results[id] }

    fun history(limit: Int = 10): List<ActionHistoryEntry> = journal?.history(limit).orEmpty()

    private fun runDurable(request: ActionRequest, fingerprint: String, cancellation: ActionCancellation): ActionResult {
        val store = journal ?: return runAdapter(request, cancellation)
        if (isRead(request.kind)) {
            return try {
                if (store.receipt(request.id) != null) fail(request, "同一执行 ID 不能更换动作或参数")
                else runAdapter(request, cancellation)
            } catch (_: Exception) { fail(request, "执行记录不可用，无法核对动作身份") }
        }
        val claim = try { store.prepare(request, fingerprint) }
        catch (_: Exception) { return fail(request, "执行记录不可用，未开始新操作") }
        if (claim is WriteClaim.Rejected) return fail(request, claim.reason)
        return try {
            val result = if (claim is WriteClaim.Existing) {
                claim.receipt.result ?: adapters[request.kind]?.recover(claim.receipt)
                    ?: return FileActionJournal.unknown(request.id, request.kind)
            } else runAdapter(request, cancellation)
            if (result.actionId != request.id || result.kind != request.kind) return FileActionJournal.unknown(request.id, request.kind)
            if (result.data["needsReview"] == "true") return FileActionJournal.unknown(request.id, request.kind)
            store.commit(request, fingerprint, result)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            FileActionJournal.unknown(request.id, request.kind)
        } catch (_: Exception) {
            // Prepared is retained even if an adapter committed an effect before losing its receipt.
            FileActionJournal.unknown(request.id, request.kind)
        }
    }

    private fun runAdapter(request: ActionRequest, cancellation: ActionCancellation): ActionResult {
        cancellation.throwIfCancelled()
        val adapter = adapters[request.kind] ?: return ActionResult(
            request.id, request.kind, ActionStatus.UNAVAILABLE, "这个动作还没有可用执行器",
        )
        return adapter.execute(request, cancellation)
    }

    private fun validate(request: ActionRequest): ActionResult? {
        val allowed = when (request.kind) {
            ActionKind.READ_MEMORY -> setOf("query", "limit")
            ActionKind.READ_MATERIALS -> setOf("limit")
            ActionKind.SAVE_NOTE, ActionKind.SPEAK -> setOf("text")
            ActionKind.OPEN_ASSISTANT -> setOf("target")
            ActionKind.GET_DEVICE_STATE, ActionKind.BACK, ActionKind.GET_LAST_OBSERVATION -> emptySet()
            ActionKind.FIND_NODE -> setOf("query", "by_resource_id")
            ActionKind.OPEN_APP -> setOf("package")
            ActionKind.TAP_NODE -> setOf("node_id")
            ActionKind.SCROLL -> setOf("node_id", "forward")
            ActionKind.INPUT_TEXT -> setOf("node_id", "text")
        }
        if (request.args.keys.any { it !in allowed }) return fail(request, "动作参数不在白名单")
        val max = when (request.kind) {
            ActionKind.READ_MEMORY -> 200
            ActionKind.READ_MATERIALS -> 10
            ActionKind.SAVE_NOTE -> 500
            ActionKind.OPEN_ASSISTANT -> 40
            ActionKind.SPEAK -> 300
            ActionKind.GET_DEVICE_STATE, ActionKind.BACK, ActionKind.GET_LAST_OBSERVATION -> 0
            ActionKind.FIND_NODE -> 120
            ActionKind.OPEN_APP -> 200
            ActionKind.TAP_NODE -> 80
            ActionKind.SCROLL -> 80
            ActionKind.INPUT_TEXT -> 300
        }
        if (request.args.values.any { cn.companion.pet.core.text.UnicodeText.count(it) > max }) return fail(request, "动作参数过长")
        if (request.kind == ActionKind.SAVE_NOTE || request.kind == ActionKind.SPEAK ||
            request.kind == ActionKind.INPUT_TEXT) {
            if (request.args["text"].isNullOrBlank()) return fail(request, "没有可处理的文字")
        }
        if (request.kind == ActionKind.TAP_NODE && request.args["node_id"].isNullOrBlank()) return fail(request, "缺少节点")
        if (request.kind == ActionKind.OPEN_APP && request.args["package"].isNullOrBlank()) return fail(request, "缺少应用包名")
        if (request.kind == ActionKind.INPUT_TEXT && request.args["node_id"].isNullOrBlank()) return fail(request, "缺少输入框")
        request.args["limit"]?.let {
            val limit = it.toIntOrNull()
            val upper = if (request.kind == ActionKind.READ_MEMORY) 20 else 10
            if (limit == null || limit !in 1..upper) return fail(request, "条数不合法")
        }
        request.args["target"]?.let { target ->
            if (cn.companion.pet.core.session.AssistantApp.entries.none { it.name == target }) return fail(request, "未知的 AI App")
        }
        return null
    }

    private fun fingerprint(request: ActionRequest): String = requestFingerprint(request)

    private fun isRead(kind: ActionKind) = kind == ActionKind.READ_MEMORY || kind == ActionKind.READ_MATERIALS ||
        kind == ActionKind.GET_DEVICE_STATE || kind == ActionKind.FIND_NODE || kind == ActionKind.GET_LAST_OBSERVATION
    private fun fail(request: ActionRequest, reason: String) = ActionResult(request.id, request.kind, ActionStatus.FAILED, reason)
    private fun cancelled(request: ActionRequest) = ActionResult(request.id, request.kind, ActionStatus.CANCELLED, "已取消；已开始的原子操作不回滚")
}

internal fun requestFingerprint(request: ActionRequest): String {
    val canonical = request.kind.name + request.args.toSortedMap().entries.joinToString("") {
        it.key.length.toString() + ":" + it.key + it.value.length + ":" + it.value
    }
    return java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
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
    private val sourceAvailable: (String) -> Boolean = { it.isNotBlank() },
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
            sourceIds.any { !sourceAvailable(it) } -> "来源已失效或不存在"
            lastActionAt > 0 && now - lastActionAt < cooldownMillis -> "冷却中"
            else -> "条件满足，可以进入下一步"
        }
        val allowed = reason == "条件满足，可以进入下一步"
        return ActionPlan(kind, sourceIds, allowed, reason)
    }
}
