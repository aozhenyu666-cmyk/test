package com.yishou.app.partner

/** 单调时钟控制期限；墙上时间只用于数据库，不让改系统时间延长一场。 */
class PartnerTimer(minutes: Int, val started: Long, allowed: Set<String>) {
    private val allowed = allowed.toSet()
    val deadline = started + minutes * 60_000L
    val presence = Presence(allowed)

    fun expired(at: Long): Boolean = at >= deadline
    fun allows(pkg: String, at: Long): Boolean = !expired(at) && pkg in allowed
    fun foreground(pkg: String?, at: Long) {
        if (pkg == null) presence.unknown(at.coerceAtMost(deadline))
        else presence.onForeground(pkg, at.coerceAtMost(deadline))
    }
    fun finish(at: Long) = presence.finish(at.coerceAtMost(deadline))
    fun text(at: Long): String {
        presence.tick(at.coerceAtMost(deadline))
        if (expired(at)) return "到点了，回来收线"
        val away = presence.awaySince
        if (away != null && at - away > 120_000) return "离开 ${(at - away) / 60_000} 分钟了，回去吗？"
        return "伴读中 · 还剩 ${(deadline - at + 59_999) / 60_000} 分钟"
    }
}
