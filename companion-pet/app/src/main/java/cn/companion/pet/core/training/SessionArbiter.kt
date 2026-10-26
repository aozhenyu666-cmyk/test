package cn.companion.pet.core.training

/**
 * 会话种类。系统里同时存在三种：
 * - TRAINING：新的训练器（TrainingController），持有题目/槽位/评分。
 * - STRICT_THINK：旧的严格思考回合（StrictSessionMachine），持有严格回合。
 * - EXTERNAL_AI：外部 AI 的一次会话（ChatSession），负责拉回与到期。
 *
 * 这三种都必须**同一时刻只有一个持有交互权**，否则会出现两个气泡、
 * 两个计时器互相暂停、或旧严格回合与新训练器同时结算。
 */
enum class SessionKind { NONE, TRAINING, STRICT_THINK, EXTERNAL_AI }

/**
 * 单一持有者仲裁。
 *
 * 规则（按优先级，写死在代码里，模型无权决定）：
 * 1. 已有持有者且未结束时，**任何新会话都不得抢占**，返回 Reject 并给出原因。
 * 2. 训练优先于严格思考、严格思考优先于外部 AI：申请时若已有更高或同级持有者，一律拒绝。
 * 3. 释放只能由持有者自己发起；释放后无人持有。
 * 4. 过期/结束的持有者应显式 release，不会自动失效——避免两套计时各算各的。
 */
class SessionArbiter {

    @Volatile
    private var holder: SessionKind = SessionKind.NONE

    @Volatile
    private var holderId: String = ""

    val current: SessionKind get() = holder
    val currentId: String get() = holderId

    fun isHeldBy(kind: SessionKind, id: String = ""): Boolean =
        holder == kind && (id.isEmpty() || holderId == id)

    fun isIdle(): Boolean = holder == SessionKind.NONE

    /**
     * 申请交互权。成功返回 null；被占用返回可读原因。
     * [sessionId] 用于区分同一类的不同实例（例如两次训练）。
     */
    @Synchronized
    fun acquire(kind: SessionKind, sessionId: String): String? {
        if (kind == SessionKind.NONE) return "无效的会话类型"
        // 同一实例重复申请：幂等成功。
        if (holder == kind && holderId == sessionId) return null
        if (holder != SessionKind.NONE) {
            return "当前已有" + label(holder) + "在进行（" + holderId.take(8) + "），先结束它再开始" + label(kind)
        }
        holder = kind
        holderId = sessionId
        return null
    }

    /**
     * 释放交互权。只有持有者才能释放，避免误放别人的。
     * [force] 只跳过实例号核对，**仍然要求种类一致**：严格陪想收尾时 force 释放，
     * 不能顺手把正在进行的训练或外部 AI 会话的锁也放掉。
     * 只有 kind = NONE 且 force（恢复/诊断路径）才无条件清空。
     * 返回是否真的释放了。
     */
    @Synchronized
    fun release(kind: SessionKind, sessionId: String = "", force: Boolean = false): Boolean {
        if (holder == SessionKind.NONE) return false
        val wipeAll = force && kind == SessionKind.NONE
        if (!wipeAll) {
            if (holder != kind) return false
            if (!force && sessionId.isNotEmpty() && holderId != sessionId) return false
        }
        holder = SessionKind.NONE
        holderId = ""
        return true
    }

    fun label(kind: SessionKind): String = when (kind) {
        SessionKind.TRAINING -> "训练"
        SessionKind.STRICT_THINK -> "严格陪想"
        SessionKind.EXTERNAL_AI -> "外部 AI 会话"
        SessionKind.NONE -> "无"
    }

    /** 诊断用：当前持有者的可读描述。 */
    fun describe(): String = if (holder == SessionKind.NONE) "无会话" else label(holder) + "（" + holderId.take(8) + "）"
}
