package cn.companion.pet.core.scene

/**
 * 最近 30 分钟的“行为轨迹”：前台切换和滚动的时间点。
 *
 * 只存包名和时间，不存屏幕文字。所有时间都由调用方传入，方便用假时钟测试。
 * 非线程安全：由服务在主线程上读写。
 */
class ActivityTrail(private val keepMillis: Long = 30 * 60_000L) {

    data class Switch(val pkg: String, val at: Long)

    private val switches = ArrayDeque<Switch>()
    private val scrolls = ArrayDeque<Switch>()

    /** 当前前台包名；还没收到任何窗口事件时为 null。 */
    val foreground: String? get() = switches.lastOrNull()?.pkg

    /** 当前前台从什么时候开始。 */
    val foregroundSince: Long? get() = switches.lastOrNull()?.at

    /** 最近一次滚动的时间（任何 App）。 */
    val lastScrollAt: Long? get() = scrolls.lastOrNull()?.at

    fun onForeground(pkg: String, at: Long) {
        if (foreground == pkg) return
        switches.addLast(Switch(pkg, at))
        trim(at)
    }

    fun onScroll(pkg: String, at: Long) {
        scrolls.addLast(Switch(pkg, at))
        trim(at)
    }

    /** [windowMillis] 内切到过的不同 App 数（包括当前这个）。 */
    fun distinctAppsWithin(now: Long, windowMillis: Long): Int {
        return switches.filter { it.at >= now - windowMillis }.map { it.pkg }.toSet().size
    }

    /**
     * 当前在桌面时，连续来回滑动了多久。
     *
     * 从最近一次桌面滚动往回数，相邻两次滚动间隔不超过 [maxGapMillis] 就算连续；
     * 最近一次滚动也必须在 [maxGapMillis] 以内，否则视为已经停下。
     */
    fun launcherSwipeMillis(now: Long, launchers: Set<String>, maxGapMillis: Long = 5_000L): Long {
        val fg = foreground ?: return 0
        if (fg !in launchers) return 0
        val since = foregroundSince ?: return 0
        val inLauncher = scrolls.filter { it.pkg == fg && it.at >= since }
        val latest = inLauncher.lastOrNull() ?: return 0
        if (now - latest.at > maxGapMillis) return 0
        var start = latest.at
        for (i in inLauncher.indices.reversed()) {
            val s = inLauncher[i]
            if (start - s.at > maxGapMillis) break
            start = s.at
        }
        return now - start
    }

    /** 每个 App 在最近 [windowMillis] 内的停留时长，按时长降序。 */
    fun dwellWithin(now: Long, windowMillis: Long): List<Pair<String, Long>> {
        val from = now - windowMillis
        val result = LinkedHashMap<String, Long>()
        val list = switches.toList()
        for (i in list.indices) {
            val start = maxOf(list[i].at, from)
            val end = if (i + 1 < list.size) list[i + 1].at else now
            if (end > start) result[list[i].pkg] = (result[list[i].pkg] ?: 0L) + (end - start)
        }
        return result.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    fun switchCountWithin(now: Long, windowMillis: Long): Int =
        switches.count { it.at >= now - windowMillis }

    /**
     * 一两句话的轨迹摘要，给探针和 Gemini 桥接用。只含 App 名和时长。
     */
    fun summary(now: Long, label: (String) -> String, windowMillis: Long = 30 * 60_000L): String {
        val count = switchCountWithin(now, windowMillis)
        val dwell = dwellWithin(now, windowMillis).take(3)
        if (dwell.isEmpty()) return "没有记录"
        val top = dwell.joinToString("、") { "${label(it.first)} ${formatMinutes(it.second)}" }
        val fg = foreground
        val since = foregroundSince
        val current = if (fg != null && since != null) "；现在在${label(fg)}，已经 ${formatMinutes(now - since)}" else ""
        return "过去 30 分钟切换了 $count 次，停留较久的是 $top$current"
    }

    private fun trim(now: Long) {
        // 保留窗口外的最后一条切换，这样停留时长从窗口起点开始算得出来
        while (switches.size > 1 && switches[1].at < now - keepMillis) switches.removeFirst()
        while (scrolls.isNotEmpty() && scrolls.first().at < now - keepMillis) scrolls.removeFirst()
    }

    companion object {
        fun formatMinutes(millis: Long): String {
            val sec = millis / 1000
            return if (sec < 60) "${sec} 秒" else "${sec / 60} 分钟"
        }
    }
}
