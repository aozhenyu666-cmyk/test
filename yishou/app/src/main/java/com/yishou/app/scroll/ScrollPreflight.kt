package com.yishou.app.scroll

/**
 * 刷着练启动前该说什么。
 *
 * 之前这里什么都不说：没配识图、没勾关注的应用、通知被关着，点「开始」后照样说「开了」，
 * 然后永远不出题——用户只能以为这东西坏了。把判断单独拎出来，好测，也好在界面上直接显示。
 */
data class ScrollReady(
    /** 缺了这些就真没法用 */
    val blockers: List<String>,
    /** 能用，但某个功能不会触发 */
    val warnings: List<String>,
) {
    val canStart: Boolean get() = blockers.isEmpty()
    fun firstMessage(): String = (blockers + warnings).firstOrNull().orEmpty()
}

object ScrollChecks {

    /**
     * @param visionReady 识图接口配好了吗（出题要靠它看屏幕）
     * @param watchedCount 关注的应用有几个
     * @param notifyAllowed 通知权限开着吗
     */
    fun preflight(visionReady: Boolean, watchedCount: Int, notifyAllowed: Boolean): ScrollReady {
        val blockers = buildList {
            if (!visionReady) add("还没填识图模型：一手看不懂屏幕，出不了题。去 设置 → 识图模型。")
            if (!notifyAllowed) add("通知被关着：开始后你看不到「考我一下」，也收不到问题。去 设置 → 权限 打开通知。")
        }
        val warnings = buildList {
            if (watchedCount == 0) {
                add("还没勾关注的应用：自动出题不会触发，只能手动点「考我一下」。去 设置 → 关注的应用。")
            }
        }
        return ScrollReady(blockers, warnings)
    }

    /**
     * 已经在跑、但当前前台 App 不在关注名单里时，说明为什么一直没自动问。
     * 返回 null 表示不需要提示。
     */
    fun idleHint(running: Boolean, watchedCount: Int, currentAppWatched: Boolean?, intervalMinutes: Int): String? {
        if (!running) return null
        if (watchedCount == 0) return "没勾关注的应用，自动出题不会触发"
        // currentAppWatched 给 null 表示调用方不知道当前是哪个 App，那就别乱说
        if (currentAppWatched == false) return "当前这个 App 不在关注名单里，自动出题不会触发"
        if (intervalMinutes <= 0) return "自动出题关着（选的是「不自动」），只能手动点「考我一下」"
        return null
    }
}
