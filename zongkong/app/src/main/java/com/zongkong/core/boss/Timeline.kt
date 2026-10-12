package com.zongkong.core.boss

import java.time.Instant
import java.time.ZoneId

/**
 * 设备时间线：前台是哪个应用、屏幕亮没亮。总控对账用的“眼睛”。
 *
 * 来源：系统的“使用情况访问”（有授权时，覆盖总控没在运行的时段），否则用无障碍服务自己记的切换。
 * 一条事件 = 从这一刻起前台是 [pkg]；特殊值：[OFF] 屏幕关、[ON] 屏幕亮（还不知道哪个应用）、[GAP] 没有记录。
 */
data class DevEvent(val at: Long, val pkg: String)

data class Seg(val pkg: String, val from: Long, val to: Long) {
    val ms: Long get() = to - from
}

object Timeline {
    const val OFF = "-"
    const val ON = "+"
    const val GAP = "?"

    /** 事件 → [from, to) 内的连续片段。[events] 要包含 from 之前最后一条（知道开始时的状态）。 */
    fun segments(events: List<DevEvent>, from: Long, to: Long): List<Seg> {
        if (to <= from) return emptyList()
        val sorted = events.sortedBy { it.at }
        var state = sorted.lastOrNull { it.at <= from }?.pkg ?: GAP
        var cursor = from
        val out = mutableListOf<Seg>()
        for (e in sorted) {
            if (e.at <= from) continue
            if (e.at >= to) break
            if (e.pkg == state) continue
            if (e.at > cursor) out += Seg(state, cursor, e.at)
            state = e.pkg
            cursor = e.at
        }
        if (to > cursor) out += Seg(state, cursor, to)
        return out
    }

    /** 每个应用一共多久（不含屏幕关、没有记录）。 */
    fun perApp(segs: List<Seg>): List<Pair<String, Long>> = segs.filter { it.pkg !in SPECIAL }
        .groupBy { it.pkg }.mapValues { (_, v) -> v.sumOf { it.ms } }.toList().sortedByDescending { it.second }

    /** 同一个应用连着用了多久（中间被打断不到 1 分钟的也算连着）。返回 (应用, 开始, 结束)。 */
    fun runs(segs: List<Seg>): List<Seg> {
        val out = mutableListOf<Seg>()
        for (s in segs) {
            // 短暂熄屏、下拉通知不打断
            if (s.pkg in SPECIAL && s.ms < 60_000) continue
            val last = out.lastOrNull()
            if (last != null && last.pkg == s.pkg) out[out.size - 1] = last.copy(to = s.to) else out += s
        }
        return out
    }

    /**
     * 写成几行字给总控看：亮屏多久、熄屏多久、哪些应用多久、娱乐合计、最长连续的一段、没有记录的时段，
     * 时间不长时附上流水（3 分钟以上的片段）。
     */
    fun summarize(
        segs: List<Seg>,
        from: Long,
        to: Long,
        zone: ZoneId,
        label: (String) -> String,
        isPlay: (String) -> Boolean,
    ): String = buildString {
        val total = to - from
        val off = segs.filter { it.pkg == OFF }.sumOf { it.ms }
        val gap = segs.filter { it.pkg == GAP }.sumOf { it.ms }
        val on = total - off - gap
        append("${hm(from, zone)}–${hm(to, zone)}（${dur(total)}）\n")
        append("屏幕亮着 ${dur(on)}，关着 ${dur(off)}")
        segs.filter { it.pkg == OFF }.maxByOrNull { it.ms }?.takeIf { it.ms >= 10 * 60_000 }?.let { append("（最长一段 ${hm(it.from, zone)}–${hm(it.to, zone)}）") }
        append('\n')
        if (gap > 0) append("没有记录 ${dur(gap)}（总控没在看，也没有“使用情况”授权）\n")
        val apps = perApp(segs).filter { it.second >= 30_000 }
        if (apps.isEmpty()) append("这段时间没有用任何应用\n")
        else append("用得最多：").append(apps.take(6).joinToString("、") { (p, ms) -> label(p) + " " + dur(ms) + if (isPlay(p)) "〔娱乐〕" else "" }).append('\n')
        val play = apps.filter { isPlay(it.first) }.sumOf { it.second }
        if (play > 0) append("娱乐合计 ${dur(play)}\n")
        runs(segs).filter { it.pkg !in SPECIAL }.maxByOrNull { it.ms }?.takeIf { it.ms >= 10 * 60_000 }?.let {
            append("最长连续：${label(it.pkg)} ${hm(it.from, zone)}–${hm(it.to, zone)}（${dur(it.ms)}）\n")
        }
        if (total <= 6 * 3600_000L) {
            val flow = runs(segs).filter { it.ms >= 3 * 60_000 }
            if (flow.isNotEmpty()) {
                append("流水：")
                append(flow.take(20).joinToString("；") { "${hm(it.from, zone)}–${hm(it.to, zone)} ${name(it.pkg, label)}" })
                append('\n')
            }
        }
    }.trimEnd()

    private val SPECIAL = setOf(OFF, ON, GAP)

    private fun name(pkg: String, label: (String) -> String) = when (pkg) {
        OFF -> "熄屏"
        ON -> "亮屏"
        GAP -> "没有记录"
        else -> label(pkg)
    }

    fun dur(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "不到 1 分钟"
            m < 60 -> "$m 分钟"
            m % 60 == 0L -> "${m / 60} 小时"
            else -> "${m / 60} 小时 ${m % 60} 分"
        }
    }

    fun hm(t: Long, zone: ZoneId): String = Instant.ofEpochMilli(t).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
}

/** 一张抽查截图（只在手机上）。 */
data class Shot(val id: String, val at: Long, val pkg: String, val path: String)
