package com.zongkong.app.guard

/**
 * 进程死因的人话翻译（纯逻辑，不碰 Android，单元测试直接跑）。
 *
 * 为什么要有它：无障碍在设置里显示「无法运行」= 系统记下了「这个服务所在的进程死过」。
 * 进程被杀时 onUnbind / onDestroy 根本不会执行，所以以前的眼睛日志只能猜。
 * Android 11 起，系统自己会记每个进程是怎么死的（ApplicationExitInfo），
 * 下次进程起来时读一遍，就能知道上次到底是 崩了 / 卡死 / 内存 / 被清理 / 被连坐。
 *
 * 数字常量照抄 android.app.ApplicationExitInfo / ActivityManager.RunningAppProcessInfo，
 * 这样这里不依赖 Android，旧系统没有的新常量也能认。
 */
object ExitWhy {

    /** 一条死亡记录（从 ApplicationExitInfo 拷出来的字段）。 */
    data class Exit(
        val at: Long,
        val process: String,
        val reason: Int,
        val status: Int = 0,
        val importance: Int = 0,
        val description: String = "",
        val pssKb: Long = 0,
        val rssKb: Long = 0,
        /** 已存下来的 ANR / 原生崩溃现场文件名（没有就空）。 */
        val traceFile: String = "",
    )

    /** 死因归类：决定该修哪里。 */
    enum class Culprit(val label: String, val fix: String) {
        OWN_CODE("总控自己崩了", "代码问题：看同一时间的 crash 记录（哪个文件哪一行），对症修那一处"),
        STUCK("主线程卡死（ANR）", "代码问题：主线程做了重活或在等别的进程；现场已存下来，按现场把那段挪到后台"),
        MEMORY("内存不够被回收", "总控整个 App 太大：把无障碍服务挪进独立的小进程"),
        CHAINED("被连坐：依赖的进程死了", "跨进程调用要用不稳定引用（这一版已改）；如果还出现，看说明里是哪个进程"),
        FORCE_STOP("被强行停止 / 一键清理", "vivo：最近任务里把总控锁定；i管家里别对总控用一键加速；强行停止后系统会直接关掉无障碍开关"),
        SWIPED("从最近任务里划掉了", "最近任务里把总控卡片锁定（下拉或长按 → 锁定）；vivo 划掉 = 杀进程"),
        VENDOR("系统 / 厂商后台管理杀的", "vivo：i管家 → 自启动 打开、电池 → 后台耗电管理 → 允许后台高耗电；这两项系统查不到，只能手动确认"),
        POWER("后台耗电太多被杀", "后台活太重：减少常驻服务里的轮询频率"),
        FROZEN("被系统冻结后杀掉", "Android 14 的后台冻结：前台常驻通知要一直在；关掉电池优化"),
        UPDATE("更新安装 / 权限变化", "正常现象：装新版本后系统会重启进程（有的系统会关掉无障碍开关，要手动再开）"),
        NORMAL("正常退出", "不用管"),
        UNKNOWN("系统没给原因", "多记几次看规律"),
    }

    // ---- ApplicationExitInfo.REASON_* ----
    const val UNKNOWN = 0
    const val EXIT_SELF = 1
    const val SIGNALED = 2
    const val LOW_MEMORY = 3
    const val CRASH = 4
    const val CRASH_NATIVE = 5
    const val ANR = 6
    const val INITIALIZATION_FAILURE = 7
    const val PERMISSION_CHANGE = 8
    const val EXCESSIVE_RESOURCE_USAGE = 9
    const val USER_REQUESTED = 10
    const val USER_STOPPED = 11
    const val DEPENDENCY_DIED = 12
    const val OTHER = 13
    const val FREEZER = 14
    const val PACKAGE_STATE_CHANGE = 15
    const val PACKAGE_UPDATED = 16

    fun reasonName(reason: Int): String = when (reason) {
        EXIT_SELF -> "自己退出"
        SIGNALED -> "被信号杀掉"
        LOW_MEMORY -> "内存不足"
        CRASH -> "Java 崩溃"
        CRASH_NATIVE -> "原生崩溃"
        ANR -> "无响应 ANR"
        INITIALIZATION_FAILURE -> "启动失败"
        PERMISSION_CHANGE -> "权限变化"
        EXCESSIVE_RESOURCE_USAGE -> "资源用得太多"
        USER_REQUESTED -> "强行停止"
        USER_STOPPED -> "用户停止"
        DEPENDENCY_DIED -> "依赖的进程死了"
        OTHER -> "系统其它原因"
        FREEZER -> "冻结后被杀"
        PACKAGE_STATE_CHANGE -> "应用状态变化"
        PACKAGE_UPDATED -> "应用更新"
        else -> "原因未知($reason)"
    }

    /** 进程当时的重要程度（RunningAppProcessInfo.IMPORTANCE_*）。越小越「不该被杀」。 */
    fun importanceName(importance: Int): String = when {
        importance <= 0 -> "-"
        importance <= 100 -> "前台"
        importance <= 125 -> "前台服务"
        importance <= 200 -> "可见"
        importance <= 230 -> "可感知"
        importance <= 300 -> "后台服务"
        importance <= 350 -> "后台"
        importance < 1000 -> "缓存"
        else -> "已消失"
    }

    fun culprit(e: Exit): Culprit {
        val d = e.description.lowercase()
        return when (e.reason) {
            CRASH, INITIALIZATION_FAILURE -> Culprit.OWN_CODE
            CRASH_NATIVE -> Culprit.OWN_CODE
            ANR -> Culprit.STUCK
            LOW_MEMORY -> Culprit.MEMORY
            DEPENDENCY_DIED -> Culprit.CHAINED
            USER_REQUESTED, USER_STOPPED -> Culprit.FORCE_STOP
            EXCESSIVE_RESOURCE_USAGE -> Culprit.POWER
            FREEZER -> Culprit.FROZEN
            PERMISSION_CHANGE, PACKAGE_STATE_CHANGE, PACKAGE_UPDATED -> Culprit.UPDATE
            EXIT_SELF -> if (e.status == 0) Culprit.NORMAL else Culprit.OWN_CODE
            SIGNALED, OTHER, UNKNOWN -> when {
                "remove task" in d || "removetask" in d -> Culprit.SWIPED
                "force" in d || "stop " in d || "clean" in d || "clear" in d || "onekey" in d -> Culprit.FORCE_STOP
                "depends on" in d || "dying proc" in d -> Culprit.CHAINED
                "lmk" in d || "low mem" in d || "lowmem" in d || "oom" in d -> Culprit.MEMORY
                "anr" in d || "not responding" in d -> Culprit.STUCK
                "cpu" in d || "excessive" in d || "wakelock" in d -> Culprit.POWER
                "freez" in d -> Culprit.FROZEN
                "install" in d || "update" in d || "replace" in d -> Culprit.UPDATE
                e.reason == UNKNOWN && d.isBlank() -> Culprit.UNKNOWN
                else -> Culprit.VENDOR
            }
            else -> Culprit.UNKNOWN
        }
    }

    /** 写进眼睛日志的一行（`exit` 事件的说明）。 */
    fun line(e: Exit): String {
        val c = culprit(e)
        return buildString {
            append(c.label)
            append("｜").append(reasonName(e.reason))
            if (e.reason == SIGNALED && e.status > 0) append("(信号 ").append(e.status).append(')')
            append("｜进程 ").append(e.process.ifBlank { "?" })
            append("｜当时 ").append(importanceName(e.importance))
            if (e.pssKb > 0) append("｜内存 ").append(e.pssKb / 1024).append("MB")
            if (e.description.isNotBlank()) append("｜系统说明：").append(e.description.take(160))
            if (e.traceFile.isNotBlank()) append("｜现场：").append(e.traceFile)
            append("｜怎么修：").append(c.fix)
        }
    }

    /**
     * 多条记录的结论：谁是主凶。只看这条服务所在的进程（[mainProcess]），
     * 正常退出、更新安装不算。没有可判的记录返回空串。
     */
    fun verdict(exits: List<Exit>, mainProcess: String): String {
        val bad = exits.filter { it.process == mainProcess || it.process.isBlank() }
            .map { culprit(it) }
            .filter { it != Culprit.NORMAL && it != Culprit.UPDATE }
        if (bad.isEmpty()) return ""
        val counts = bad.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val top = counts.first()
        val rest = counts.drop(1).joinToString("、") { "${it.key.label} ${it.value} 次" }
        return buildString {
            append("最近 ${bad.size} 次进程死亡，主要是「${top.key.label}」(${top.value} 次)")
            if (rest.isNotEmpty()) append("；其余：").append(rest)
            append("。").append(top.key.fix)
        }
    }

    /** 只要比上次读过的更新的那些，按时间从旧到新。 */
    fun fresh(exits: List<Exit>, lastSeen: Long): List<Exit> = exits.filter { it.at > lastSeen }.sortedBy { it.at }

    /** 从 ANR 现场里找主线程最上面一帧总控自己的代码。 */
    fun mainThreadHint(trace: String): String {
        val start = trace.indexOf("\"main\"")
        if (start < 0) return ""
        val block = trace.substring(start).substringBefore("\n\n")
        val frames = block.lines().map { it.trim() }.filter { it.startsWith("at ") }
        val own = frames.firstOrNull { "com.zongkong" in it }
        val top = frames.firstOrNull()
        return when {
            own != null -> "主线程卡在 ${own.removePrefix("at ")}"
            top != null -> "主线程卡在 ${top.removePrefix("at ")}"
            else -> ""
        }
    }
}
