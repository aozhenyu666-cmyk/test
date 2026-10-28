package com.zongkong.sentinel

/**
 * 哨兵要不要拦：纯逻辑，单元测试覆盖。
 *
 * 总控每隔几秒把“心跳 + 拦截名单 + 是否在休假”写过来。心跳停了 [STALE_MS]（或者总控下线前主动写了 at = 0），
 * 就算总控掉线：拦截名单里的应用一律拦——掉线不再等于畅玩。
 */
object Decide {
    /** 总控心跳停了这么久，算掉线。 */
    const val STALE_MS = 90_000L

    data class State(
        /** 总控上一次写的时刻（心跳）。0 = 总控说自己下线了。 */
        val at: Long,
        val blocked: Set<String>,
        val strict: Boolean,
        /** 休假 / 紧急放行到几点：这之前哨兵也不拦。 */
        val freeUntil: Long,
    )

    /** 总控从没写过名单时用的：常见的短视频、视频、社区、游戏、小说。 */
    val DEFAULT_BLOCKED = setOf(
        "com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite", "com.ss.android.ugc.live",
        "com.smile.gifmaker", "com.kuaishou.nebula", "tv.danmaku.bili", "com.bilibili.app.in",
        "com.xingin.xhs", "com.sina.weibo", "com.ss.android.article.news", "com.ss.android.article.video",
        "com.tencent.qqlive", "com.qiyi.video", "com.youku.phone", "com.duowan.kiwi", "air.tv.douyu.android",
        "com.tencent.tmgp.sgame", "com.tencent.tmgp.pubgmhd", "com.miHoYo.Yuanshen", "com.dragon.read",
        "com.kmxs.reader", "com.zhihu.android", "com.hupu.games", "com.douban.frodo", "com.tencent.weishi",
    )

    fun mainAlive(s: State?, now: Long): Boolean = s != null && s.at > 0 && now - s.at < STALE_MS

    /** 掉线多久了（毫秒）；在线或者从没连上过返回 null。 */
    fun downFor(s: State?, now: Long): Long? = if (s == null || mainAlive(s, now)) null else if (s.at <= 0) 0L else now - s.at

    /**
     * 打开 [pkg] 时要不要拦。
     * 总控没装、或者从没连上过哨兵：不拦（还没开始守）。总控在线：不拦（总控自己管）。休假 / 紧急放行中：不拦。
     */
    fun shouldBlock(s: State?, now: Long, pkg: String, mainInstalled: Boolean, released: Boolean = false, confirmedFault: Boolean = false): Boolean {
        if (!mainInstalled || s == null) return false
        if (released) return false // 解除闸门生效中（走过冷静期 + 二次核验）
        if (!confirmedFault && mainAlive(s, now)) return false
        if (now < s.freeUntil) return false
        val list = s.blocked.takeIf { it.isNotEmpty() } ?: DEFAULT_BLOCKED
        return pkg in list
    }

    /** 掉线时自己修总控：两分钟最多一次。 */
    fun shouldHeal(lastHealAt: Long, now: Long): Boolean = now - lastHealAt > 120_000L

    fun ago(ms: Long): String = when {
        ms < 60_000 -> "${ms / 1000} 秒"
        ms < 3_600_000 -> "${ms / 60_000} 分钟"
        else -> "${ms / 3_600_000} 小时 ${(ms % 3_600_000) / 60_000} 分钟"
    }
}
