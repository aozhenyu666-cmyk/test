package com.zongkong.sentinel

import com.zongkong.sentinel.health.HealthStatus

/**
 * 总控到底还管不管着——[Store.mainGuarding] 的结论（纯 Kotlin，JVM 单测直接调）。
 *
 * 为什么不再用 Boolean：这个值同时决定两件事——「要不要把总控的心跳续上」（`Decide.State.at`）
 * 和「要不要接管拦截」。用 Boolean 的时候，`UNKNOWN` / `SUSPECT` / `RECOVERING` / `PAUSED`
 * 都被算成了「在」，心跳被它一路续着，`Decide.STALE_MS`（90 秒）的过期闸门永远等不到，
 * 就是一条假在线路径。所以这里把话说死：**只有正面证据才算在**。
 *
 * 没有复用 [HealthStatus]：那是 v2 协议里的健康词表，而这里要回答的是「总控这个进程还管不管」，
 * 老版总控根本没有 healthStatus（只有 `guard` 布尔），硬塞进 HealthStatus 会多出一个不存在的状态。
 */
enum class MainGuard(
    /** 是否算「总控还在管着」。只有 [GUARDING] 是 true。 */
    val guarding: Boolean,
) {
    /** 有正面证据：新版总控回了 v2 的 HEALTHY，或者老版总控明确说 guard=true。 **只有这个值能续心跳。** */
    GUARDING(true),

    /** 没有正面证据：未知、可疑、恢复中、暂停、无障碍关了、确认失联、没注册，以及任何看不懂的答复。 */
    NOT_GUARDING(false),
    ;

    companion object {
        /** v2 总控回的字段名（和 `com.zongkong.app` 的 `SentinelWakeProvider` 写的是同一个）。 */
        const val KEY_STATUS = "healthStatus"

        /** 老版总控回的字段名：它自己说眼睛在不在。没有 [KEY_STATUS] 时才看它。 */
        const val KEY_GUARD = "guard"

        /** 总控明确表示「这个请求不认」时回的字段名（签名不对 / 方法不对）。 */
        const val KEY_ERROR = "error"

        /**
         * 纯函数：把总控 `wake` 的答复（[android.os.Bundle] 摊平后的 Map）解释成结论。
         *
         * 一律白名单：证不了健康的都算不在。
         *  - 没答复 / 带了 error → 不在；
         *  - 有 `healthStatus`（v2）→ 只有 `HEALTHY` 算在，其余一律不在；
         *  - 没有 `healthStatus`（老版）→ 退回看 `guard`，只有 `true` 算在；
         *  - 看不懂的字符串 → 不在（宁可当它掉线，也不假在线）。
         */
        fun interpret(reply: Map<String, Any?>?): MainGuard {
            if (reply == null) return NOT_GUARDING
            if (reply.containsKey(KEY_ERROR)) return NOT_GUARDING
            if (reply.containsKey(KEY_STATUS)) {
                return if (statusOf(reply[KEY_STATUS]) == HealthStatus.HEALTHY) GUARDING else NOT_GUARDING
            }
            // 老版总控：只有它明说 guard=true 才算在（缺字段、类型不对都当不在）。
            return if (reply[KEY_GUARD] == true) GUARDING else NOT_GUARDING
        }

        /** 字符串 → [HealthStatus]；不认识就 null（不抛异常，不当 HEALTHY）。 */
        private fun statusOf(raw: Any?): HealthStatus? {
            val name = (raw as? String)?.trim()?.uppercase() ?: return null
            return HealthStatus.entries.firstOrNull { it.name == name }
        }
    }
}
