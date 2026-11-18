package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core

/**
 * 2026-11-18 裁决器的唯一输出（插件 /plan 的 `lock`）在手机上的样子。纯 JVM，可测。
 *
 * 手机对任何一个 App 只走一段逻辑：系统底座放行 → open 放行 → needPass 要通行证 →
 * allowlist 看 allow → free 看 deny。和插件 lock_decision.js 的 decideApp 同构。
 */
data class ArbiterDecision(
    val mode: String,
    val allow: Set<String> = emptySet(),
    val deny: Map<String, String> = emptyMap(),
    val needPass: Set<String> = emptySet(),
    val why: String = "",
    val unlockHow: String = "",
    val priority: String = "",
) {
    data class Verdict(val lock: Boolean, val why: String, val needPass: Boolean = false)

    fun decide(pkg: String?, isBase: (String) -> Boolean): Verdict {
        val p = pkg?.trim().orEmpty()
        if (p.isEmpty() || isBase(p)) return Verdict(false, "base")
        if (mode == MODE_OPEN) return Verdict(false, "open")
        if (p in needPass) return Verdict(true, "先跟鲸澜申请通行证", needPass = true)
        return when (mode) {
            MODE_ALLOWLIST -> if (p in allow) Verdict(false, "allow") else Verdict(true, why)
            else -> deny[p]?.let { Verdict(true, it) } ?: Verdict(false, "free")
        }
    }

    companion object {
        const val MODE_OPEN = "open"
        const val MODE_ALLOWLIST = "allowlist"
        const val MODE_FREE = "free"

        /** 从 /plan 的 `lock` 读；读不懂就返回 null（当作没有新裁决，什么都不改）。 */
        fun fromWire(raw: Any?): ArbiterDecision? {
            val m = raw as? Map<*, *> ?: return null
            val mode = (m["mode"] as? String)?.takeIf { it == MODE_OPEN || it == MODE_ALLOWLIST || it == MODE_FREE } ?: return null
            fun strings(key: String): Set<String> =
                (m[key] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }?.toSet() ?: emptySet()
            val deny = LinkedHashMap<String, String>()
            (m["deny"] as? List<*>)?.forEach { e ->
                val d = e as? Map<*, *> ?: return@forEach
                val app = (d["app"] as? String)?.trim().orEmpty()
                if (app.isNotEmpty()) deny[app] = (d["why"] as? String).orEmpty().ifBlank { "临时锁" }
            }
            return ArbiterDecision(
                mode = mode,
                allow = strings("allow"),
                deny = deny,
                needPass = strings("needPass"),
                why = (m["why"] as? String).orEmpty(),
                unlockHow = (m["unlockHow"] as? String).orEmpty(),
                priority = (m["priority"] as? String).orEmpty(),
            )
        }
    }
}
