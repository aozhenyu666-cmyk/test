package com.yishou.app.dsh

object DshHandoff {
    const val VERSION = 1
    const val ACTION_TRAIN = "com.yishou.app.action.TRAIN_FOR_DSH"
    const val ACTION_PASS = "io.github.ccch1mneyyy.dsh_app.action.YISHOU_PASS"
    const val EXTRA_VERSION = "dsh_protocol"
    const val EXTRA_EVENT_ID = "dsh_event_id"
    const val EXTRA_NONCE = "dsh_nonce"
    const val EXTRA_TARGET_PACKAGE = "dsh_target_package"
    const val EXTRA_TARGET_LABEL = "dsh_target_label"
    const val EXTRA_REPLY_PACKAGE = "dsh_reply_package"
    const val EXTRA_PASS_MINUTES = "dsh_pass_minutes"
    const val EXTRA_REF = "dsh_ref"

    /** 没有对应应用组时用这个组名；它的放行时长走默认值。 */
    const val DSH_GROUP = "DSH Go"
    private const val DSH_PACKAGE = "io.github.ccch1mneyyy.dsh_app"

    data class Request(
        val eventId: String,
        val nonce: String,
        val targetPackage: String,
        val targetLabel: String,
        val replyPackage: String,
    )

    /** 只检查形状，任何一项不对就返回 null。 */
    fun parseRequest(get: (String) -> Any?): Request? {
        if ((get(EXTRA_VERSION) as? Number)?.toInt() != VERSION) return null
        val eventId = (get(EXTRA_EVENT_ID) as? String)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
        val nonce = (get(EXTRA_NONCE) as? String)?.takeIf { it.length in 16..64 } ?: return null
        val target = (get(EXTRA_TARGET_PACKAGE) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 255 } ?: return null
        val reply = (get(EXTRA_REPLY_PACKAGE) as? String)
            ?.takeIf { it == DSH_PACKAGE || it.startsWith("$DSH_PACKAGE.") } ?: return null
        val label = (get(EXTRA_TARGET_LABEL) as? String)?.take(64)?.takeIf { it.isNotBlank() } ?: target
        return Request(eventId, nonce, target, label, reply)
    }

    /** 不能在一手拿到可回传的放行时，告诉用户为什么该回拦截页。 */
    fun blockedReason(inWindow: Boolean, partnerPending: Boolean, onlyRecall: Boolean): String? = when {
        inWindow -> "陪练窗口进行中，这次不能在一手答题放行"
        partnerPending -> "上次伴读还没收线，这次不能在一手答题放行"
        onlyRecall -> "一手现在没有可出的题（题库空、也没配模型）"
        else -> null
    }

    /** 回传取决于放行来源，统计名称变化不应影响交接。 */
    fun shouldSend(dshMode: Boolean, verified: Boolean, isTest: Boolean, group: String): Boolean =
        dshMode && verified && !isTest && group != "早上"

    /** 只在已拿到放行记录后拼凭证；一次性密钥原样带回，不写日志。 */
    fun passExtras(req: Request, minutes: Int, ref: String): Map<String, Any> = linkedMapOf(
        EXTRA_VERSION to VERSION,
        EXTRA_EVENT_ID to req.eventId,
        EXTRA_NONCE to req.nonce,
        EXTRA_PASS_MINUTES to minutes.coerceIn(1, 120),
        EXTRA_REF to ref.take(120),
    )
}
