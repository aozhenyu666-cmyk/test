package io.github.ccch1mneyyy.dsh_app.loop.proactive

/**
 * D7 只读面板（接口约定 §2 `state` / §5 `obligations`）：一份 `/plan` 快照进，界面要显示的那几行出。
 *
 * 纯函数，不碰 Android、不碰运行时状态，所以能进全量单测。手机在这里**只显示**：
 * 主状态取 `state.layers[0]`（§7.2 第 1 条：`rules` 只为没有 `state` 的老引擎兜底），
 * 义务和额度照插件给的字段，自己不判断锁不锁、也不算额度。
 *
 * 追问锁剩余不在这里——它每秒都在变，锁页自己有一行倒计时。
 */
internal fun panelTextOf(plan: Map<*, *>): String? {
    val state = plan["state"] as? Map<*, *>
    val layer = (state?.get("layers") as? List<*>)?.firstOrNull() as? Map<*, *>
    val kind = stateLabelOf(layer?.get("kind") as? String ?: state?.get("state") as? String)
    val lines = ArrayList<String>(3)
    kind?.let { k ->
        val why = (layer?.get("why") as? String)?.trim().orEmpty()
        lines.add(if (why.isEmpty()) "现在：$k" else "现在：$k · $why")
    }
    val obligations: List<*> = plan["obligations"] as? List<*> ?: emptyList<Any?>()
    val owed = obligations.mapNotNull { o ->
        ((o as? Map<*, *>)?.get("text") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }.take(3)
    if (owed.isNotEmpty()) lines.add("义务：" + owed.joinToString("；"))
    val wall = plan["wall"] as? Map<*, *>
    (wall?.get("relaxRemaining") as? Number)?.toInt()?.let { left ->
        lines.add(
            "额度：" + when {
                wall?.get("tightenToday") == true -> "今天收紧了，不放风"
                wall?.get("relaxToday") == true -> "本周还剩 $left 次放松，今天已经在放风"
                else -> "本周还剩 $left 次放松"
            },
        )
    }
    return if (lines.isEmpty()) null else lines.joinToString("\n")
}

/** `state` / `layers[].kind` 的中文名；锁页说「问完回到哪里」也用它，免得两处各写一套。 */
internal fun stateLabelOf(kind: String?): String? = when (kind) {
    "night" -> "深夜锁"
    "evening" -> "晚间对账"
    "morning" -> "晨间立项"
    "review" -> "验收"
    "followup" -> "追问锁"
    "sprint" -> "冲刺"
    "brain" -> "共脑"
    "meal" -> "吃饭"
    "break" -> "放风"
    "standby" -> "待命"
    "default" -> "默认锁"
    null, "" -> null
    else -> kind
}
