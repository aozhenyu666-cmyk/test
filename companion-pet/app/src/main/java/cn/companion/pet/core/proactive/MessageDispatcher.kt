package cn.companion.pet.core.proactive

/**
 * 消息投递的四个状态，**严格分开，不许互相冒充**。
 *
 * 用户长期反馈"它明明在想但从来不说"，根因之一就是把这四个混成一个 Boolean。
 * 现在每次投递都留一条 [DeliveryRecord]，界面与诊断页读它，而不是靠猜。
 */
enum class DeliveryState {
    /** 模型返回了非空正文。**这不等于用户看到了。** */
    GENERATED,

    /** 通知真的发出去（NotificationManager 未抛异常、通道未被禁用）。 */
    PUBLISHED,

    /** 悬浮气泡/聊天页**实际 attach**（拿到了渲染回执）。 */
    DISPLAYED,

    /** 用户点了通知或打开了对应会话。 */
    READ,

    /** 生成或投递彻底失败。 */
    FAILED,

    /** 拖太久没发出去，作废（**不补发**，避免重启后集中暴发）。 */
    EXPIRED,
}

/** 一条投递的完整记录。 */
data class DeliveryRecord(
    val id: String,
    val createdAt: Long,
    /** 生成的正文。可能为空（生成失败）。 */
    val text: String = "",
    val state: DeliveryState = DeliveryState.GENERATED,
    /** 通知 id；每条消息独立，不覆盖。 */
    val notificationId: Int = 0,
    /** 触发理由，用于诊断。 */
    val trigger: String = "",
    /** 为什么失败/为什么没展示，一句话。 */
    val note: String = "",
    val updatedAt: Long = createdAt,
    val attempts: Int = 0,
) {
    /** 只有这两种才算"用户有机会看到"。 */
    val reachedUser: Boolean get() = state == DeliveryState.PUBLISHED ||
        state == DeliveryState.DISPLAYED ||
        state == DeliveryState.READ

    /** 终态：不会再变化。 */
    val terminal: Boolean get() = state in setOf(
        DeliveryState.DISPLAYED, DeliveryState.READ, DeliveryState.FAILED, DeliveryState.EXPIRED,
    )
}

/**
 * 投递通道。实现方负责真正的发送（通知、气泡、TTS）。
 *
 * 契约：**返回 true 只代表这一步成功**，不代表用户看见。
 * 具体的状态推进由 [MessageDispatcher] 负责，通道不许自己宣布"已送达"。
 */
interface DeliveryChannel {
    /** 通道名字，用于留痕（如 "notification" / "bubble"）。 */
    val name: String

    /**
     * 尝试投递。
     * @return true = 这一步成功（通知已发出 / 气泡已 attach）
     */
    suspend fun send(record: DeliveryRecord): Boolean
}

/**
 * 消息投递器：按优先级尝试多个通道，记录真实状态。
 *
 * 关键要求（来自任务书）：
 * - **不依赖 Activity / Compose / UI 回调**：通道由调用方注入，通知通道必须能在纯后台工作；
 * - 失败重试与去重；
 * - 明确区分生成成功 / 通知发布 / 实际展示 / 已读。
 *
 * 纯逻辑：不碰 Android，通道是接口，便于 JVM 测。
 */
class MessageDispatcher(
    private val channels: List<DeliveryChannel>,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** 最多重试几次。 */
    private val maxAttempts: Int = 3,
    /** 超过这个时长还没发出去就作废，不补发。 */
    private val expireAfterMillis: Long = 15 * 60_000L,
) {
    private val records = ArrayDeque<DeliveryRecord>()

    /** 通知 id 递增计数器：保证每条消息独立，不互相覆盖。 */
    private var nextNotificationId = 1000

    fun nextNotificationId(): Int = nextNotificationId++

    fun recent(): List<DeliveryRecord> = records.toList()

    fun find(id: String): DeliveryRecord? = records.firstOrNull { it.id == id }

    /**
     * 从「已经生成好正文」开始的一次投递。
     *
     * @param text 模型生成的正文；空串直接判 FAILED（生成成功但没有内容不算说出口）
     */
    suspend fun dispatch(text: String, trigger: String = ""): DeliveryRecord {
        val t = now()
        var record = DeliveryRecord(
            id = "msg-" + t.toString(36) + "-" + nextNotificationId.toString(36),
            createdAt = t,
            text = text,
            state = DeliveryState.GENERATED,
            notificationId = nextNotificationId(),
            trigger = trigger,
            updatedAt = t,
        )
        records.addLast(record)
        trim()

        if (text.isBlank()) {
            return update(record, DeliveryState.FAILED, note = "生成内容为空")
        }
        if (t - record.createdAt > expireAfterMillis) {
            return update(record, DeliveryState.EXPIRED, note = "生成后太久没发出去")
        }

        // 按顺序尝试通道；任意一个成功即停（第一个成功的就是主通道）。
        for (channel in channels) {
            var attempts = 0
            while (attempts < maxAttempts) {
                attempts++
                record = record.copy(attempts = attempts, updatedAt = now())
                val ok = runCatching { channel.send(record) }.getOrDefault(false)
                if (ok) {
                    // 通知/气泡发出去了。只有能"拿到渲染回执"的通道才算 DISPLAYED，
                    // 其余只能算 PUBLISHED——不许冒充用户看见了。
                    val reached = if (channel.name == CHANNEL_BUBBLE) DeliveryState.DISPLAYED else DeliveryState.PUBLISHED
                    return update(record, reached, note = "via " + channel.name)
                }
            }
            // 这个通道彻底失败，换下一个；但要把原因留下来。
            record = record.copy(note = record.note.ifBlank { channel.name + " 发送失败" }, updatedAt = now())
        }
        return update(record, DeliveryState.FAILED, note = "所有通道都失败：" + channels.joinToString("/") { it.name })
    }

    /** 用户点了通知/打开会话时调用。只有已经不是 FAILED/EXPIRED 的才升级到 READ。 */
    fun markRead(id: String): DeliveryRecord? {
        val r = find(id) ?: return null
        if (r.state == DeliveryState.FAILED || r.state == DeliveryState.EXPIRED) return r
        return update(r, DeliveryState.READ, note = "用户已查看")
    }

    /**
     * 重启后恢复：把没到终态的记录标成 EXPIRED（不补发），
     * 避免"关机一晚上，开机连炸十条"。
     */
    fun expirePendingAfterRestart(): List<DeliveryRecord> {
        val t = now()
        val changed = mutableListOf<DeliveryRecord>()
        for (i in records.indices) {
            val r = records[i]
            if (!r.terminal) {
                val next = r.copy(
                    state = DeliveryState.EXPIRED,
                    note = "重启时未投递完成，不补发",
                    updatedAt = t,
                )
                records[i] = next
                changed += next
            }
        }
        return changed
    }

    private fun update(record: DeliveryRecord, state: DeliveryState, note: String = ""): DeliveryRecord {
        val next = record.copy(state = state, note = note, updatedAt = now())
        val idx = records.indexOfFirst { it.id == record.id }
        if (idx >= 0) records[idx] = next
        return next
    }

    private fun trim() {
        while (records.size > MAX_RECORDS) records.removeFirst()
    }

    companion object {
        const val MAX_RECORDS = 100

        /** 能给出"真的画出来了"回执的通道名。 */
        const val CHANNEL_BUBBLE = "bubble"

        /** 只能证明"已发出"的通道名。 */
        const val CHANNEL_NOTIFICATION = "notification"
    }
}
