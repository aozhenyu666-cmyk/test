package com.moling.core.flow

import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkWire
import com.moling.core.mind.BEIJING
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/*
 * 第十三批 G1/G2：本地计划代驾（PlanDriver）。
 *
 * 这里只回答三件事，彼此分开：
 *  1. 计划草稿是哪一份（PlanDraft）。
 *  2. 用户/保底策略有没有在本地采用它（PlanAdoption）。本地采用只影响今天页和提醒，
 *     不改 FlowPlan.confirmed 的旧语义，也不代表总控执行了。
 *  3. 如果要交给总控，逐条动作在 PlanOperation 账本里走：准备 → 发送中 → 总控待确认 / 已执行 / 拒绝 / 结果未知。
 *
 * 铁律：
 *  - 没有总控回执就不算执行；本地永远不伪造 receipt；
 *  - operationId 由 (planId, planRevision, attempt, index, 指令内容) 确定性算出，同一动作重复提交只留一条；
 *  - 结果未知（UNKNOWN）不盲重发；
 *  - 计划改版会取消尚未发送的旧命令，已发送的按回执处理；
 *  - 换日/太久没看的草稿不自动采用，重启也不会突然下发。
 *
 * 本类不碰 Android、不碰网络；调用方负责把 state 存盘、把指令交给总控。
 */

@Serializable
enum class AdoptionState(val label: String) {
    DRAFT("草稿（没采用）"),
    LOCAL_ADOPTED("本地已采用（未交给总控）"),
    EXECUTION_REQUESTED("已请求交总控（还没发完）"),
    ZK_PENDING("总控等确认"),
    ACTIVE("总控已执行"),
    REJECTED("总控拒绝"),
    EXPIRED("过期"),
    CANCELLED("已取消"),
}

@Serializable
data class PlanDraft(
    val planId: String,
    val day: String,
    /** 计划内容的版本：内容（水道/意图/风险）每变一次 +1。 */
    val revision: Long = 1,
    val contentHash: String = "",
    val intent: String = "",
    val mainTitle: String = "",
    val mainStartMin: Int = -1,
    /** 计划对应的焦点/主题键；变了要重核，不能沿用上一份的采纳。 */
    val focusKey: String = "",
    /** 旧 confirmed 的快照，只用于兼容显示；采纳不会改它。 */
    val confirmed: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class PlanAdoption(
    val planId: String,
    val day: String,
    val planRevision: Long,
    /** 采纳状态自身的版本：每次改动/取消/重来 +1，和 planRevision 分开。 */
    val adoptionRevision: Long = 1,
    val state: AdoptionState = AdoptionState.DRAFT,
    /** 旧字段，只为读回旧文件保留；F13-1 起不再累计“可见时间”。 */
    val visibleMs: Long = 0,
    val visibleSince: Long = 0,
    val lastTickAt: Long = 0,
    val adoptedAt: Long = 0,
    /** 下一次“一键采用”提醒的时间（0 = 不提醒）。 */
    val eligibilityAt: Long = 0,
    val createdAt: Long = 0,
    val commandIds: List<String> = emptyList(),
    val lastReceiptId: String = "",
    val message: String = "",
    /** 这条草稿的「一键采用」提醒已经发出的时间；0 = 还没提醒。 */
    val remindedAt: Long = 0,
)

@Serializable
enum class OperationState(val label: String) {
    PREPARED("准备（没发）"),
    SENDING("发送中"),
    ZK_PENDING("总控待确认"),
    ACTIVE("总控已执行"),
    EXECUTED("总控确认完成"),
    REJECTED("总控拒绝"),
    CANCELLED("已取消（没发）"),
    UNKNOWN("结果未知（不重发）"),
    EXPIRED("过期（没发）"),
}

/** 一条准备交给总控的动作。状态和回执都留在这里，界面可按 planId → operationId → receiptId 追。 */
@Serializable
data class PlanOperation(
    val operationId: String,
    val planId: String,
    val planRevision: Long,
    /** 这次采纳/执行的版本，用来区分“取消后重新采纳再发一次”。 */
    val attempt: Long,
    val index: Int,
    val command: ZkCommand,
    val contentHash: String,
    val grantKind: String = "",
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val state: OperationState = OperationState.PREPARED,
    val attempts: Int = 0,
    val receiptId: String = "",
    val receiptAt: Long = 0,
    val message: String = "",
)

@Serializable
data class PlanDriverState(
    val drafts: List<PlanDraft> = emptyList(),
    val adoption: PlanAdoption? = null,
    val operations: List<PlanOperation> = emptyList(),
    val seq: Long = 0,
    val lastTickAt: Long = 0,
)

/**
 * 计划代驾的设置。到点只发一条「一键采用」提醒，**绝不自动采用**；
 * 本地采用只能由他点出来（第十四批 F13-1）。
 */
@Serializable
data class AdoptPolicy(
    /** 到点要不要提醒。提醒本身不改任何状态。 */
    val remind: Boolean = true,
    /** 草稿登记后多久提醒一次，按墙钟算（不看屏幕亮不亮）。默认 20 分钟。 */
    val remindAfterMs: Long = 20 * 60_000L,
    /** 草稿放太久就不再提醒（避免隔夜草稿突然生效）。 */
    val maxDraftAgeMs: Long = 12 * 60 * 60_000L,
    /** 准备发出的动作多久没发就过期（不发）。 */
    val operationTtlMs: Long = 10 * 60_000L,
    /** 只留最近几天的草稿。 */
    val keepDrafts: Int = 7,
)

@Serializable
enum class ConflictKind { NO_DRAFT, STALE_DAY, PLAN_CHANGED, DRAFT_TOO_OLD, FOCUS_CHANGED, ALREADY_EXECUTING, INVALID_PLAN }

@Serializable
data class PlanConflict(val kind: ConflictKind, val message: String)

data class PlanResult(val ok: Boolean, val message: String, val conflicts: List<PlanConflict> = emptyList()) {
    companion object {
        fun ok(message: String) = PlanResult(true, message)
        fun fail(message: String, vararg conflicts: PlanConflict) = PlanResult(false, message, conflicts.toList())
    }
}

/** 总控给一条动作的回执（本地只转抄，不生成）。[auto] = 在授权书范围内倒计时执行。 */
@Serializable
data class PlanReceipt(
    val receiptId: String,
    val ok: Boolean,
    val auto: Boolean = false,
    val message: String = "",
    val at: Long = 0,
)

/** 发送/回执时调用方需要做的下一步。只给账本，不替调用方发。 */
data class ExecutionPreparation(
    val ok: Boolean,
    val message: String,
    val operations: List<PlanOperation> = emptyList(),
    val conflicts: List<PlanConflict> = emptyList(),
)

object PlanContents {
    /** 计划内容的稳定 hash：意图、水道（含时间/状态）、风险。 */
    fun hash(plan: FlowPlan): String {
        val sb = StringBuilder()
        // 只算“计划内容”：旧 confirmed 和运行时 state（在流/流过了）不算改稿，避免一确认/一推进就重置采纳。
        sb.append(plan.day).append('|').append(plan.intent).append('|')
        plan.channels.sortedBy { it.id }.forEach {
            sb.append(it.id).append('~').append(it.kind.name).append('~').append(it.title).append('~')
                .append(it.startMin).append('~').append(it.minutes).append('~')
                .append(it.doneWhen).append('~').append(it.planB).append('~').append(it.why).append(';')
        }
        sb.append('|')
        plan.risks.forEach { sb.append(it.atMin).append('~').append(it.what).append('~').append(it.response).append(';') }
        return sha256(sb.toString())
    }

    fun commandHash(c: ZkCommand): String = sha256(ZkWire.toMap(c).toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" })

    fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

private fun dayOf(now: Long): String = java.time.Instant.ofEpochMilli(now).atZone(BEIJING).toLocalDate().toString()

/**
 * 计划代驾状态机。线程安全由调用方保证（BrainHost 在 IO 线程串行调用；测试单线程）。
 * [onChange] 在每次状态变化后调用，用来存盘/推给界面。
 */
class PlanDriver(
    initial: PlanDriverState = PlanDriverState(),
    private val policyProvider: () -> AdoptPolicy = { AdoptPolicy() },
    private val onChange: (PlanDriverState) -> Unit = {},
) {
    private var state: PlanDriverState = initial

    fun snapshot(): PlanDriverState = state

    fun policy(): AdoptPolicy = policyProvider()

    /** 当前这一天（[today]）的草稿；没有返回 null。 */
    fun draft(today: String): PlanDraft? = state.drafts.firstOrNull { it.day == today }

    /** 当前这一天被采纳/请求执行的记录；不是今天的草稿返回 null。 */
    fun adoption(today: String): PlanAdoption? = state.adoption?.takeIf { it.day == today }

    fun operation(operationId: String): PlanOperation? = state.operations.firstOrNull { it.operationId == operationId }

    // ------------------------------------------------------------------ 草稿

    /**
     * 把大脑排好的计划登记为草稿。内容没变就不动版本；内容变了 revision + 1，
     * 正在倒计时的采纳重来、尚未发送的旧命令取消。旧 confirmed 只做快照，不改。
     */
    fun putDraft(plan: FlowPlan, now: Long, focusKey: String = ""): PlanResult {
        val hash = PlanContents.hash(plan)
        val existing = state.drafts.firstOrNull { it.day == plan.day }
        val draft = when {
            existing == null -> PlanDraft(
                planId = "plan-${plan.day}-${now}",
                day = plan.day,
                revision = 1,
                contentHash = hash,
                intent = plan.intent,
                mainTitle = plan.channels.firstOrNull { it.kind == ChannelKind.MAIN }?.title.orEmpty(),
                mainStartMin = plan.channels.firstOrNull { it.kind == ChannelKind.MAIN }?.startMin ?: -1,
                focusKey = focusKey,
                confirmed = plan.confirmed,
                createdAt = now,
                updatedAt = now,
            )
            existing.contentHash == hash -> existing.copy(
                intent = plan.intent,
                confirmed = plan.confirmed,
                focusKey = focusKey.ifBlank { existing.focusKey },
                updatedAt = now,
            )
            else -> existing.copy(
                revision = existing.revision + 1,
                contentHash = hash,
                intent = plan.intent,
                mainTitle = plan.channels.firstOrNull { it.kind == ChannelKind.MAIN }?.title.orEmpty(),
                mainStartMin = plan.channels.firstOrNull { it.kind == ChannelKind.MAIN }?.startMin ?: -1,
                focusKey = focusKey.ifBlank { existing.focusKey },
                confirmed = plan.confirmed,
                updatedAt = now,
            )
        }
        val focusChanged = focusKey.isNotBlank() && existing != null && existing.focusKey.isNotBlank() && existing.focusKey != focusKey
        val changed = existing != null && (existing.contentHash != hash || focusChanged)
        val drafts = (state.drafts.filterNot { it.day == plan.day } + draft)
            .sortedBy { it.day }
            .takeLast(policy().keepDrafts.coerceAtLeast(1))
        var adopt = state.adoption
        var ops = state.operations
        var message = "记下草稿「${draft.intent}」"
        if (changed && adopt != null && adopt.planId == draft.planId) {
            val sticky = adopt.state == AdoptionState.CANCELLED || adopt.state == AdoptionState.REJECTED
            if (sticky) {
                // F13-2：他说过“不采纳 / 被拒绝”就粘住，改稿不偷偷变回草稿；要用只能他再点。
                adopt = adopt.copy(
                    planRevision = draft.revision,
                    adoptionRevision = adopt.adoptionRevision + 1,
                    lastTickAt = now,
                    eligibilityAt = 0,
                    remindedAt = 0,
                    message = "计划改版了；你还没采纳，还是没采纳（要用再点采纳）",
                )
                message = adopt.message
            } else {
                val unsent = adopt.state == AdoptionState.EXECUTION_REQUESTED || adopt.state == AdoptionState.ZK_PENDING
                val active = adopt.state == AdoptionState.ACTIVE
                if (unsent) {
                    ops = ops.map {
                        if (it.planId == draft.planId && it.planRevision < draft.revision &&
                            it.state in setOf(OperationState.PREPARED, OperationState.SENDING)
                        ) {
                            it.copy(state = OperationState.CANCELLED, message = "计划改版，取消未发送")
                        } else it
                    }
                }
                adopt = adopt.copy(
                    planRevision = draft.revision,
                    adoptionRevision = adopt.adoptionRevision + 1,
                    state = AdoptionState.DRAFT,
                    visibleMs = 0,
                    visibleSince = 0,
                    lastTickAt = now,
                    eligibilityAt = 0,
                    adoptedAt = 0,
                    message = when {
                        active -> "计划改版了；总控里已经执行的动作不受本地改稿影响，新版本重新倒计时"
                        unsent -> "计划改版了：没发的旧命令已取消，已经发出的按总控回执处理，新版本重新倒计时"
                        else -> "计划改版了，倒计时重来"
                    },
                )
                message = adopt.message
            }
        }
        // 今天的草稿才有采纳记录；未来几天先只存草稿，到那天再开始计时。
        if (plan.day == dayOf(now) && (adopt == null || adopt.planId != draft.planId)) {
            adopt = newAdoption(draft, now)
        }
        commit(state.copy(drafts = drafts, adoption = adopt, operations = ops, seq = state.seq + 1))
        return PlanResult.ok(message)
    }

    // ------------------------------------------------------------------ 本地采用

    /**
     * 计划每分钟走一次。**只发「一键采用」提醒，从不自动采用**（第十四批 F13-1）。
     * 倒计时按草稿登记后的墙钟走，不再看屏幕亮不亮 / App 在不在前台。
     */
    fun tick(now: Long): PlanDriverState {
        val today = dayOf(now)
        val drafts = state.drafts
        var adopt = state.adoption
        if (adopt != null && adopt.day != today) {
            if (adopt.state in setOf(AdoptionState.DRAFT, AdoptionState.LOCAL_ADOPTED)) {
                adopt = adopt.copy(state = AdoptionState.EXPIRED, message = "换日了，昨天的计划没有自动采用")
            }
        }
        val draft = drafts.firstOrNull { it.day == today }
        if (draft != null && (adopt == null || adopt.planId != draft.planId)) adopt = newAdoption(draft, now)
        if (draft != null && adopt != null && adopt.planId == draft.planId) {
            adopt = when (adopt.state) {
                AdoptionState.DRAFT -> {
                    val p = policy()
                    val dueAt = draft.createdAt + p.remindAfterMs
                    val canRemind = p.remind && !draft.confirmed &&
                        now - draft.createdAt <= p.maxDraftAgeMs &&
                        adoptionConflicts(draft, adopt, now, today).isEmpty()
                    if (canRemind && now >= dueAt) {
                        if (adopt.remindedAt == 0L) {
                            adopt.copy(
                                eligibilityAt = 0,
                                lastTickAt = now,
                                remindedAt = now,
                                message = "到点了：点「一键采用」才用这份计划（只影响今天页/提醒，没交给总控）",
                            )
                        } else {
                            adopt.copy(eligibilityAt = 0, lastTickAt = now)
                        }
                    } else {
                        adopt.copy(lastTickAt = now, eligibilityAt = if (canRemind) dueAt else 0)
                    }
                }
                AdoptionState.LOCAL_ADOPTED, AdoptionState.EXECUTION_REQUESTED, AdoptionState.ZK_PENDING ->
                    adopt.copy(lastTickAt = now, eligibilityAt = 0)
                else -> adopt.copy(lastTickAt = now, eligibilityAt = 0)
            }
        }
        val ops = expireOperations(state.operations, now)
        commit(state.copy(drafts = drafts, adoption = adopt, operations = ops, lastTickAt = now))
        return state
    }

    /**
     * 用户点「采纳」（含到点提醒后的「一键采用」）。这是本地采用**唯一**的入口：
     * 只把状态改成 LOCAL_ADOPTED，不发指令、不改 FlowPlan.confirmed。
     */
    fun adopt(plan: FlowPlan?, now: Long, focusKey: String = ""): PlanResult {
        val today = dayOf(now)
        val draft = draft(today) ?: return PlanResult.fail("今天还没有草稿，先排一条", PlanConflict(ConflictKind.NO_DRAFT, "没有今天的草稿"))
        val conflicts = planConflicts(plan, draft, now, today, focusKey)
        if (conflicts.isNotEmpty()) return PlanResult(false, conflicts.first().message, conflicts)
        val adopt = adoption(today) ?: newAdoption(draft, now)
        if (adopt.state in setOf(AdoptionState.EXECUTION_REQUESTED, AdoptionState.ZK_PENDING, AdoptionState.ACTIVE)) {
            return PlanResult(false, "已经交给总控了，本地采纳不能再改它", listOf(PlanConflict(ConflictKind.ALREADY_EXECUTING, "已经在执行/等回执")))
        }
        val next = adopt.copy(
            planRevision = draft.revision,
            adoptionRevision = adopt.adoptionRevision + 1,
            state = AdoptionState.LOCAL_ADOPTED,
            adoptedAt = now,
            lastTickAt = now,
            eligibilityAt = 0,
            message = "你采纳了这份计划：只在本地用，没有交给总控",
        )
        commit(state.copy(adoption = next, seq = state.seq + 1))
        return PlanResult.ok(next.message)
    }

    /**
     * 取消本地采纳 / 停止继续交总控。
     * 返回说明里写清楚：几条还没发（已取消）、几条已经发出（按回执，不在本地撤销现实动作）。
     */
    fun cancel(now: Long, reason: String = "你取消了"): PlanResult {
        val adopt = state.adoption ?: return PlanResult.fail("没有正在使用的计划")
        val unsent = state.operations.filter {
            it.planId == adopt.planId && it.planRevision == adopt.planRevision &&
                it.state in setOf(OperationState.PREPARED, OperationState.SENDING, OperationState.EXPIRED)
        }
        val sent = state.operations.filter {
            it.planId == adopt.planId && it.planRevision == adopt.planRevision &&
                it.state in setOf(OperationState.ZK_PENDING, OperationState.ACTIVE, OperationState.EXECUTED, OperationState.UNKNOWN)
        }
        val ops = state.operations.map {
            if (it in unsent && it.state != OperationState.EXPIRED) {
                it.copy(state = OperationState.CANCELLED, message = reason)
            } else it
        }
        val detail = buildString {
            append(reason)
            if (unsent.isNotEmpty()) append("；还没发的 ${unsent.size} 条已取消")
            if (sent.isNotEmpty()) append("；已经发给总控的 ${sent.size} 条按回执处理，本地撤销不了现实动作")
            if (unsent.isEmpty() && sent.isEmpty()) append("；没有要发的动作")
        }
        val next = adopt.copy(
            state = AdoptionState.CANCELLED,
            adoptionRevision = adopt.adoptionRevision + 1,
            lastTickAt = now,
            eligibilityAt = 0,
            message = detail,
        )
        commit(state.copy(adoption = next, operations = ops, seq = state.seq + 1))
        return PlanResult.ok(detail)
    }

    /** 重新开始今天草稿的倒计时（例如改了焦点/发现草稿过时）。 */
    fun resetCountdown(now: Long): PlanResult {
        val today = dayOf(now)
        val draft = draft(today) ?: return PlanResult.fail("没有今天的草稿")
        val next = newAdoption(draft, now).copy(message = "倒计时重新开始")
        commit(state.copy(adoption = next, seq = state.seq + 1))
        return PlanResult.ok(next.message)
    }

    // ------------------------------------------------------------------ 执行账本（只记录，不发送）

    /**
     * 为一份已本地采用的计划准备指令。operationId 确定性生成，重复调用不会多加动作；
     * 只是把状态写到 EXECUTION_REQUESTED，调用方再按 [operations] 里 PREPARED 的逐条发。
     */
    fun prepareExecution(plan: FlowPlan?, commands: List<ZkCommand>, now: Long, grantKind: String = ""): ExecutionPreparation {
        val today = dayOf(now)
        val draft = draft(today) ?: return ExecutionPreparation(false, "今天还没有草稿，不能准备执行")
        val conflicts = planConflicts(plan, draft, now, today)
        if (conflicts.isNotEmpty()) return ExecutionPreparation(false, conflicts.first().message, conflicts = conflicts)
        val adopt = adoption(today) ?: return ExecutionPreparation(false, "还没本地采用，不能准备执行")
        if (adopt.state != AdoptionState.LOCAL_ADOPTED && adopt.state != AdoptionState.EXECUTION_REQUESTED) {
            return ExecutionPreparation(
                ok = false,
                message = "现在的状态不能准备执行：${adopt.state.label}",
                conflicts = listOf(PlanConflict(ConflictKind.ALREADY_EXECUTING, adopt.state.label)),
            )
        }
        if (commands.isEmpty()) return ExecutionPreparation(false, "没有可执行的指令")
        val attempt = if (adopt.state == AdoptionState.EXECUTION_REQUESTED) adopt.adoptionRevision else adopt.adoptionRevision + 1
        val ttl = policy().operationTtlMs
        val fresh = commands.mapIndexed { index, cmd ->
            val cmdHash = PlanContents.commandHash(cmd)
            val operationId = "op-" + PlanContents.sha256("${draft.planId}|${draft.revision}|${attempt}|${index}|${cmdHash}").take(32)
            state.operations.firstOrNull { it.operationId == operationId } ?: PlanOperation(
                operationId = operationId,
                planId = draft.planId,
                planRevision = draft.revision,
                attempt = attempt,
                index = index,
                command = cmd,
                contentHash = cmdHash,
                grantKind = grantKind,
                createdAt = now,
                expiresAt = now + ttl,
            )
        }
        val added = fresh.filter { f -> state.operations.none { it.operationId == f.operationId } }
        val ops = state.operations + added
        val adopt2 = if (adopt.state == AdoptionState.LOCAL_ADOPTED) adopt.copy(
            state = AdoptionState.EXECUTION_REQUESTED,
            adoptionRevision = attempt,
            lastTickAt = now,
            commandIds = fresh.map { it.operationId },
            message = "准备了 ${fresh.size} 条，还没发；发之前可以取消",
        ) else adopt.copy(lastTickAt = now, commandIds = fresh.map { it.operationId })
        commit(state.copy(operations = ops, adoption = adopt2, seq = state.seq + 1))
        return ExecutionPreparation(true, "准备了 ${fresh.size} 条，还没发", fresh, emptyList())
    }

    /** 调用方开始发送这一条（发给总控之前记一笔）。 */
    fun markSending(operationId: String, now: Long): PlanOperation? {
        val op = operation(operationId) ?: return null
        if (op.state != OperationState.PREPARED) return op
        val next = op.copy(state = OperationState.SENDING, attempts = op.attempts + 1)
        commit(state.copy(operations = state.operations.map { if (it.operationId == operationId) next else it }))
        return next
    }

    /**
     * 记总控回执。重复记同一张回执、或对已经终态的动作再记，都不改状态（幂等）。
     * 总控说 ok 但没执行（auto=false）→ ZK_PENDING；授权内执行 → ACTIVE；拒绝 → REJECTED。
     */
    fun recordReceipt(operationId: String, receipt: PlanReceipt, now: Long): PlanOperation? {
        val op = operation(operationId) ?: return null
        if (op.state in setOf(OperationState.ACTIVE, OperationState.EXECUTED, OperationState.REJECTED, OperationState.CANCELLED)) return op
        if (receipt.receiptId.isNotBlank() && op.receiptId == receipt.receiptId) return op
        val next = when {
            !receipt.ok -> op.copy(state = OperationState.REJECTED, receiptId = receipt.receiptId, receiptAt = now, message = receipt.message)
            receipt.auto -> op.copy(state = OperationState.ACTIVE, receiptId = receipt.receiptId, receiptAt = now, message = receipt.message)
            else -> op.copy(state = OperationState.ZK_PENDING, receiptId = receipt.receiptId, receiptAt = now, message = receipt.message)
        }
        val ops = state.operations.map { if (it.operationId == operationId) next else it }
        var adopt = state.adoption
        if (adopt != null && adopt.planId == op.planId && adopt.planRevision == op.planRevision) {
            val mine = ops.filter {
                it.planId == op.planId && it.planRevision == op.planRevision &&
                    it.state !in setOf(OperationState.CANCELLED, OperationState.EXPIRED)
            }
            val rejected = mine.count { it.state == OperationState.REJECTED }
            val active = mine.count { it.state == OperationState.ACTIVE || it.state == OperationState.EXECUTED }
            val pending = mine.count { it.state == OperationState.ZK_PENDING }
            adopt = when {
                mine.isNotEmpty() && rejected == mine.size -> adopt.copy(state = AdoptionState.REJECTED, lastReceiptId = receipt.receiptId, message = "总控拒绝了")
                mine.isNotEmpty() && active == mine.size -> adopt.copy(state = AdoptionState.ACTIVE, lastReceiptId = receipt.receiptId, message = "总控说这些动作已经执行")
                pending > 0 -> adopt.copy(state = AdoptionState.ZK_PENDING, lastReceiptId = receipt.receiptId, message = "总控等确认")
                else -> adopt.copy(lastReceiptId = receipt.receiptId)
            }
        }
        commit(state.copy(operations = ops, adoption = adopt, seq = state.seq + 1))
        return next
    }

    /** 总控后来的结果（查 receipt 得到），只对已发送的动作生效。终态不改，重复结果幂等。 */
    fun recordFinal(operationId: String, receiptId: String, finalState: String, message: String, now: Long): PlanOperation? {
        val op = operation(operationId) ?: return null
        if (op.state == OperationState.EXECUTED || op.state == OperationState.REJECTED) return op
        val kind = finalState.trim().uppercase()
        if (kind !in setOf("DONE", "FAILED", "DECLINED", "EXPIRED", "ACTIVE", "PENDING")) return op
        val next = when (kind) {
            "DONE" -> op.copy(state = OperationState.EXECUTED, receiptId = receiptId.ifBlank { op.receiptId }, receiptAt = now, message = message)
            "FAILED", "DECLINED" -> op.copy(state = OperationState.REJECTED, receiptId = receiptId.ifBlank { op.receiptId }, receiptAt = now, message = message)
            "EXPIRED" -> op.copy(state = OperationState.EXPIRED, receiptId = receiptId.ifBlank { op.receiptId }, receiptAt = now, message = message)
            "ACTIVE" -> op.copy(state = OperationState.ACTIVE, receiptId = receiptId.ifBlank { op.receiptId }, receiptAt = now, message = message)
            else -> op.copy(state = OperationState.ZK_PENDING, receiptId = receiptId.ifBlank { op.receiptId }, receiptAt = now, message = message)
        }
        commit(state.copy(operations = state.operations.map { if (it.operationId == operationId) next else it }, seq = state.seq + 1))
        return next
    }

    /**
     * IPC 断了/进程被杀，不确定总控收没收到：记 UNKNOWN。
     * 不自动重发（不盲重试）；有 receiptId 的话由调用方去查，查到的结果走 [recordFinal]。
     */
    fun markUnknown(operationId: String, now: Long, message: String = "结果未知，等查回执"): PlanOperation? {
        val op = operation(operationId) ?: return null
        if (op.state != OperationState.SENDING) return op
        val next = op.copy(state = OperationState.UNKNOWN, receiptAt = now, message = message)
        commit(state.copy(operations = state.operations.map { if (it.operationId == operationId) next else it }, seq = state.seq + 1))
        return next
    }

    /** 只把“还没发且过期”的动作标过期；已发出的不动。 */
    fun expire(now: Long): List<PlanOperation> {
        val ops = expireOperations(state.operations, now)
        val changed = ops.filterIndexed { i, o -> state.operations[i] != o }
        commit(state.copy(operations = ops))
        return changed
    }

    /** 未发送、可取消的动作数。 */
    fun unsentCount(): Int = state.operations.count { it.state in setOf(OperationState.PREPARED, OperationState.SENDING) }

    /** 已经发出/有回执的动作数。 */
    fun sentCount(): Int = state.operations.count {
        it.state in setOf(OperationState.ZK_PENDING, OperationState.ACTIVE, OperationState.EXECUTED, OperationState.UNKNOWN)
    }

    /** 给界面/调试页的一句话：本地状态和总控回执分开写。 */
    fun describe(now: Long): String {
        val today = dayOf(now)
        val d = draft(today)
        val a = adoption(today)
        return buildString {
            append(if (d == null) "今天没有计划草稿" else "草稿 v${d.revision}：${d.intent}")
            if (a != null) {
                append("｜${a.state.label}")
                if (a.state == AdoptionState.DRAFT && a.eligibilityAt > now) {
                    append("，还要看 ${(a.eligibilityAt - now) / 60_000 + 1} 分钟")
                }
            }
            append("｜发给总控的：${state.operations.count { it.planId == a?.planId && it.planRevision == a?.planRevision }} 条")
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun newAdoption(draft: PlanDraft, now: Long) = PlanAdoption(
        planId = draft.planId,
        day = draft.day,
        planRevision = draft.revision,
        adoptionRevision = 1,
        state = AdoptionState.DRAFT,
        createdAt = now,
        lastTickAt = now,
        message = "草稿放这儿；到点提醒你一键采用，你点了才算",
    )

    private fun planConflicts(plan: FlowPlan?, draft: PlanDraft, now: Long, today: String, focusKey: String = ""): List<PlanConflict> {
        val out = mutableListOf<PlanConflict>()
        if (plan == null) {
            out += PlanConflict(ConflictKind.NO_DRAFT, "没有计划内容可采纳")
            return out
        }
        if (plan.day != today || draft.day != today) {
            out += PlanConflict(ConflictKind.STALE_DAY, "昨天的计划不能今天采用，重新排一条")
            return out
        }
        if (PlanContents.hash(plan) != draft.contentHash) {
            out += PlanConflict(ConflictKind.PLAN_CHANGED, "计划改过了，先重新记草稿再采纳")
        }
        if (now - draft.createdAt > policy().maxDraftAgeMs) {
            out += PlanConflict(ConflictKind.DRAFT_TOO_OLD, "草稿放太久了，重新排一条再采用")
        }
        if (focusKey.isNotBlank() && draft.focusKey.isNotBlank() && draft.focusKey != focusKey) {
            out += PlanConflict(ConflictKind.FOCUS_CHANGED, "焦点换了，这份计划要重新核对")
        }
        return out
    }

    private fun adoptionConflicts(draft: PlanDraft, adopt: PlanAdoption, now: Long, today: String): List<PlanConflict> {
        val out = mutableListOf<PlanConflict>()
        if (draft.day != today) out += PlanConflict(ConflictKind.STALE_DAY, "换日了")
        if (adopt.planRevision != draft.revision) out += PlanConflict(ConflictKind.PLAN_CHANGED, "计划改版了")
        if (now - draft.createdAt > policy().maxDraftAgeMs) out += PlanConflict(ConflictKind.DRAFT_TOO_OLD, "草稿太久")
        return out
    }

    private fun expireOperations(ops: List<PlanOperation>, now: Long): List<PlanOperation> = ops.map {
        if (it.state in setOf(OperationState.PREPARED, OperationState.SENDING, OperationState.ZK_PENDING) && it.expiresAt in 1..now) {
            it.copy(state = OperationState.EXPIRED, message = "准备太久没发，过期了")
        } else it
    }

    private fun commit(next: PlanDriverState) {
        state = next
        onChange(next)
    }
}

/** 兼容旧字段：总控那边已经确认过（旧 confirmed）只用于显示，不参与本地采纳判断。 */
fun FlowPlan.legacyConfirmed(): Boolean = confirmed
