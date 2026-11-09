package io.github.ccch1mneyyy.dsh_app.phone.a11y

// 微信里误入的那几个页面（D5，接口约定 §3 `wechat-hit`）：视频号 / 朋友圈 / 小说阅读页。
//
// 只看微信自己的页面类名。这一条**只记账，不得升级成违抗**（契约原文），所以判不出来就不管，
// 也不去翻页面文本 —— 多猜一次就是把正常看聊天记成违抗的风险。

object WechatPages {
    const val PKG = "com.tencent.mm"

    /** 连续停留多久才算误入（契约：3 秒）。 */
    const val DWELL_MS = 3_000L

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

    /** 微信自己的聊天页：契约要求「先返回聊天页才上报」，别的页面不收尾。 */
    fun isChat(className: String?): Boolean {
        val c = className?.lowercase() ?: return false
        return c.contains("launcherui") || c.contains("chattingui") || c.contains("mainui")
    }
}
