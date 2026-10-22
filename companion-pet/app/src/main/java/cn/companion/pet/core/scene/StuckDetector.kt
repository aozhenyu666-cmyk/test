package cn.companion.pet.core.scene

/**
 * 学习场景的“卡壳探头”：同一页面超过 [stuckMillis] 没有滚动，触发一次。
 *
 * 页面用调用方给的 key 区分（例如 包名 + 窗口类名）。同一页面只触发一次，
 * 换页面或发生滚动后重置。
 */
class StuckDetector(private val stuckMillis: Long = 90_000L) {

    private var pageKey: String? = null
    private var quietSince: Long = 0L
    private var fired = false

    fun onPage(key: String, at: Long) {
        if (key == pageKey) return
        pageKey = key
        quietSince = at
        fired = false
    }

    fun onScroll(at: Long) {
        quietSince = at
        fired = false
    }

    /** 到点且这一页还没触发过时返回 true，并记为已触发。 */
    fun check(now: Long): Boolean {
        if (pageKey == null || fired) return false
        if (now - quietSince < stuckMillis) return false
        fired = true
        return true
    }
}
