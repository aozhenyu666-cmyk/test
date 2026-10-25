package com.zongkong.sentinel.watch

/**
 * 紧急暂停（报告 §3.6，规范 §6）—— 和 [`com.zongkong.sentinel.Release`] 的「正常提前结束监管」**分开**：
 *
 * | | 正常解除（Release.kt） | 紧急暂停（本文件） |
 * | --- | --- | --- |
 * | 目的 | 提前结束本轮监管 | 马上停掉本轮锁屏 |
 * | 门槛 | 手写分析 + 冷静期 2h + 二次核验 | 无门槛，本地一键 |
 * | 依赖 | 无网络（本地判题） | 无网络、**无模型** |
 * | 时长 | 30 分钟，到点自动恢复 | 到用户取消或本轮窗口结束 |
 * | 记录 | 分析文字 | 原因（选填）+ 使用时间 + 各客户端确认情况 |
 *
 * 铁律：
 *  - **立即生效**：不写够字数、不等两小时、不问模型。
 *  - **不谎称全停**：只有收到客户端确认的才写「已确认暂停」，其余显示「暂停未确认」。
 *  - 一个客户端没回，不能拦住其他客户端停。
 *
 * 纯逻辑，JVM 单测可覆盖。
 */
object Emergency {

    data class Record(
        /** 墙上时间：用户按下紧急暂停的时刻。 */
        val atWall: Long,
        /** 单调时间：用来算「本轮已暂停多久」，不受改钟影响。 */
        val atMono: Long,
        val reason: String,
        /** 只暂停这一条规则；null = 所有生效规则。 */
        val policyId: String? = null,
        /** 包名 → 是否收到「我要停了」的确认。没确认的界面必须显示「暂停未确认」。 */
        val confirmed: Map<String, Boolean> = emptyMap(),
    ) {
        /** 传播到 [targets] 之后的结论：accepted 里是确认的，其余是未确认。 */
        fun confirmations(targets: List<String>): List<Pair<String, Boolean>> =
            targets.map { it to (confirmed[it] == true) }

        val confirmedCount: Int get() = confirmed.count { it.value }

        fun allConfirmed(targets: List<String>): Boolean = targets.isNotEmpty() && targets.all { confirmed[it] == true }
    }

    /** 按紧急暂停：没有任何前置条件，返回要落盘的记录。 */
    fun trigger(
        nowWall: Long,
        nowMono: Long,
        reason: String,
        policyId: String? = null,
        targets: List<String> = emptyList(),
        reached: List<String> = emptyList(),
    ): Record = Record(
        atWall = nowWall,
        atMono = nowMono,
        reason = reason.ifBlank { "紧急暂停（未填原因）" },
        policyId = policyId,
        confirmed = targets.associateWith { it in reached },
    )

    /** 某一轮监管是否仍在紧急暂停中（窗口结束即自然失效，不悄悄延长）。 */
    fun activeFor(rec: Record?, nowWall: Long, policy: com.zongkong.sentinel.health.Policy?): Boolean {
        if (rec == null) return false
        if (policy == null) return true
        if (rec.policyId != null && rec.policyId != policy.policyId) return false
        // 本轮窗口已经结束：暂停也就结束了，不需要额外的「恢复」动作。
        return nowWall < policy.endsAtWall
    }

    /** 界面文案：**只写到确认了的那些**。 */
    fun describe(rec: Record, targets: List<String>): String {
        val yes = targets.filter { rec.confirmed[it] == true }
        val no = targets - yes.toSet()
        val head = "紧急暂停已于 ${rec.atWall} 生效（原因：${rec.reason}）"
        if (targets.isEmpty()) return "$head；没有已连接客户端，未传播"
        if (no.isEmpty()) return "$head；已确认停止：${yes.joinToString("、")}"
        if (yes.isEmpty()) return "$head；**暂停未确认**：${no.joinToString("、")} 都没回"
        return "$head；已确认停止：${yes.joinToString("、")}；**暂停未确认**：${no.joinToString("、")}"
    }
}
