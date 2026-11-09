package io.github.ccch1mneyyy.dsh_app.phone.a11y

// 微信里误入的那几个页面（D5，接口约定 §3 `wechat-hit`）：视频号 / 朋友圈 / 小说阅读页。
//
// 只看微信自己的页面类名。这一条**只记账，不得升级成违抗**（契约原文），所以判不出来就不管，
// 也不去翻页面文本 —— 多猜一次就是把正常看聊天记成违抗的风险。

object WechatPages {
    const val PKG = "com.tencent.mm"

    /** 连续停留多久才算误入（契约：3 秒）。 */
    const val DWELL_MS = 3_000L

    /** 下拉通知栏这类系统层窗口不算离开当前页面。 */
    private val OVERLAYS = setOf("com.android.systemui")

    /** 上报用的页面名，和契约里的 `page` 对齐。 */
    fun page(className: String?): String? {
        val c = className?.lowercase() ?: return null
        return when {
            c.contains("finder") -> "finder"        // 视频号
            c.contains("sns") -> "moment"           // 朋友圈
            c.contains("reader") || c.contains("novel") -> "novel" // 小说 / 阅读页
            else -> null
        }
    }

    fun isOverlay(pkg: String?): Boolean = pkg != null && pkg in OVERLAYS
}

/** 一次误入：哪个页面、停了多久。 */
data class WechatHit(val page: String, val dwellMs: Long)

/**
 * 跟踪「此刻停在哪个误入页面」。离开那个页面时结算一次，去哪都算：微信别的页面、
 * 回聊天、切到别的 App、灭屏。停满 [WechatPages.DWELL_MS] 才返回一次 [WechatHit]。
 *
 * 纯逻辑，不碰 Android，时间由调用方给（单调时钟）。
 */
class WechatStay {
    private var page: String? = null
    private var since = 0L

    /** 窗口切换。返回值非空 = 刚离开的那一页停够了，要上报。 */
    fun onWindow(pkg: String?, className: String?, now: Long): WechatHit? {
        if (WechatPages.isOverlay(pkg)) return null
        val next = if (pkg == WechatPages.PKG) WechatPages.page(className) else null
        if (next != null && next == page) return null
        val hit = settle(now)
        page = next
        since = if (next != null) now else 0L
        return hit
    }

    /** 灭屏：人已经不在看了，按灭屏那一刻结算。 */
    fun onScreenOff(now: Long): WechatHit? {
        val hit = settle(now)
        page = null
        since = 0L
        return hit
    }

    private fun settle(now: Long): WechatHit? {
        val open = page ?: return null
        val dwell = now - since
        return if (dwell >= WechatPages.DWELL_MS) WechatHit(open, dwell) else null
    }
}
