package com.zongkong.app.system

/**
 * “眼睛”现在处于哪种模式。
 *
 * 为什么单独抽出来：无障碍在 iQOO/vivo 上会“开了但没在运行”，这时候总控还剩多少视力，
 * 是权限页、AI 的自述（Chat 的 sensors）、以及取哪份使用记录，三处都要用同一个判断。
 * 放在一起才不会被三处各说一套；而且这里是纯逻辑（不 import 任何 Android 类），单测能直接喂值。
 */
object Eye {
    /**
     * 三种模式。措辞按“能不能看 / 能不能拦”分，不按权限名分——用户关心的是这个。
     */
    enum class Mode {
        /** 完整：无障碍在跑。看得到前台应用和亮灭屏，也拦得住。 */
        FULL,

        /** 只读：无障碍没跑，只剩系统的使用情况记录。看得见用了多久，拦不住。 */
        READONLY,

        /** 无：两个都没有。 */
        NONE,
    }

    /**
     * 判定。
     *
     * 只看两件事：无障碍服务真的在跑没有（心跳）、有没有“使用情况访问”授权。
     * 注意用的是“在跑”而不是“设置里开着”——设置里开着但服务没跑，正是这次要解决的问题，
     * 那种情况下总控其实已经拦不住了，不能还告诉用户“完整”。
     */
    fun mode(running: Boolean, usageGranted: Boolean): Mode = when {
        running -> Mode.FULL
        usageGranted -> Mode.READONLY
        else -> Mode.NONE
    }

    /** 短标签（权限页的 Tag、小标题）。 */
    fun label(mode: Mode): String = when (mode) {
        Mode.FULL -> "完整"
        Mode.READONLY -> "只读"
        Mode.NONE -> "没了"
    }

    /** 给用户的一句话：现在能干什么、不能干什么。 */
    fun detail(mode: Mode): String = when (mode) {
        Mode.FULL -> "无障碍在跑：看得到你在用哪个应用、屏幕亮着还是灭了，也拦得住（弹回、锁屏）。"
        Mode.READONLY -> "无障碍没在运行：还能读系统的使用情况记录，知道今天用了多久哪些应用；但拦不住，也看不见应用内的跳动。"
        Mode.NONE -> "无障碍没在运行，也没授权“使用情况访问”：这段时间对总控是空白。"
    }

    /** 给 AI 的自述。宁可说得少，也不能让它以为还拦得住。 */
    fun forAi(mode: Mode): String = when (mode) {
        Mode.FULL -> "无障碍在跑。你能看设备时间线（前台应用、亮灭屏），也拦得住。"
        Mode.READONLY -> "无障碍没在跑，只能读系统的使用情况记录：看得见用了多久、哪些应用，但拦不住，" +
            "也看不到应用内的切换。别承诺“我帮你弹回去”，先让他去权限页把无障碍打开。"
        Mode.NONE -> "无障碍没在跑，也没授权“使用情况访问”：这段时间是空白，不要猜他做了什么。" +
            "先让他去权限页打开无障碍（以及“使用情况访问”）。"
    }

    /** 这种模式下能不能真的拦住（弹回、锁屏）。 */
    fun canBlock(mode: Mode): Boolean = mode == Mode.FULL

    /**
     * 读记录时优先用系统那份吗。
     *
     * 无障碍在跑：用总控自己的记录——它按窗口切换逐条记，比系统的统计细。
     * 无障碍没跑：系统记录是唯一还看得见的东西，有授权就用它（这段本来会是空白）。
     * 都没有：只剩总控自己记的（掉线时段就是空白），照旧返回，让上层自己判断。
     */
    fun systemRecordFirst(running: Boolean, usageGranted: Boolean): Boolean = !running && usageGranted
}

/** 自动修复的战绩：诊断里最近这些 rebind_ok / rebind_fail 各有几条。 */
data class HealStats(val ok: Int, val fail: Int) {
    val total: Int get() = ok + fail
}

/**
 * 数一数最近修成功几次、失败几次（权限页要显示“它到底在替我干活没有”）。
 *
 * 只吃事件种类字符串，不碰诊断文件、不碰 Android：数错了会误导用户放弃这个功能，必须能单测。
 */
fun healStats(kinds: List<String>): HealStats = HealStats(
    ok = kinds.count { it == "rebind_ok" },
    fail = kinds.count { it == "rebind_fail" },
)

