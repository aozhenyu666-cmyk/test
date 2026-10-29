package com.zongkong.sentinel

/**
 * 策略版本仲裁（报告 P0-03）。
 *
 * 问题：总控会把「拦截名单 + 是否强制 + 策略版本」推给哨兵。推送是异步的，
 * 网络/进程抖动时**旧消息可能后到**。如果哨兵无脑覆盖，就会出现：
 * 用户刚把窗口结束（revision=7，不拦），一条迟到的 revision=6 推送把状态改回「要拦」，
 * 于是哨兵在用户已经退出监管之后继续拦娱乐应用 —— 典型的「旧策略冒充最新策略」。
 *
 * 规则很简单，但必须集中在一处，否则每个消费点都会漏：
 *  - revision **更大**的才接受（严格单调）；
 *  - revision **相同**视为重复推送，内容可以更新（心跳/时间戳），但不算「新策略」；
 *  - revision **更小**一律拒绝，并如实记一笔，不静默丢弃。
 *
 * 纯 Kotlin，无 Android 依赖，可直接 JVM 单测。
 */
object PolicyArbiter {

    /** 一次推送的仲裁结论。 */
    enum class Verdict {
        /** 首次收到，接受。 */
        ACCEPT_FIRST,

        /** revision 前进，接受为新策略。 */
        ACCEPT_NEWER,

        /** revision 相同：接受（同一策略的重复推送/心跳），但不算策略更新。 */
        ACCEPT_SAME,

        /** revision 倒退：拒绝，保留现有策略。 */
        REJECT_STALE,
    }

    data class Result(
        val verdict: Verdict,
        /** 是否应该把这份推送写进存储。 */
        val apply: Boolean,
        /** 是否算「策略发生了变化」（界面/日志用）。 */
        val policyChanged: Boolean,
        /** 一句话中文说明，给诊断用。 */
        val note: String,
    )

    /**
     * @param currentRevision 哨兵现在持有的版本；null = 还没收到过任何策略。
     * @param incoming 本次推送的版本。
     */
    fun arbitrate(currentRevision: Int?, incoming: Int): Result = when {
        currentRevision == null -> Result(
            Verdict.ACCEPT_FIRST, apply = true, policyChanged = true,
            note = "首次收到策略（版本 $incoming）",
        )

        incoming > currentRevision -> Result(
            Verdict.ACCEPT_NEWER, apply = true, policyChanged = true,
            note = "策略更新：$currentRevision → $incoming",
        )

        incoming == currentRevision -> Result(
            Verdict.ACCEPT_SAME, apply = true, policyChanged = false,
            note = "同一策略的重复推送（版本 $incoming），只刷新时间戳",
        )

        else -> Result(
            Verdict.REJECT_STALE, apply = false, policyChanged = false,
            note = "拒绝过期策略：收到 $incoming，当前已是 $currentRevision",
        )
    }
}
