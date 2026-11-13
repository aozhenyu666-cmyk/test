package io.github.ccch1mneyyy.dsh_app.sensing

/**
 * J3 活动识别：静止 / 走路 / 骑车。只看计步，不用定位。
 *
 * 判据是步频（步/分钟）。骑车时手机不计步，所以光靠计步器分不出「骑车」和「坐着」——
 * 骑车这一条要么等系统活动识别给提示（[ActivityClassifier.classify] 的 cyclingHint），
 * 要么就一直报「不确定」。宁可不猜，也不把坐着报成在骑车。
 */
enum class ActivityKind(val label: String) {
    STILL("静止"),
    WALKING("走路"),
    CYCLING("骑车"),
    UNKNOWN("不确定"),
}

/**
 * 纯函数，不碰 Android 任何东西：给一段窗口里的步数和时长，说这段时间在干什么。
 * 逻辑全在这里，所以能在 JVM 单测里跑完；[StepActivitySource] 只负责读传感器再调它。
 */
object ActivityClassifier {

    /** 窗口短于 10 秒，步频算不准（一步的抖动就能让结果翻倍）。 */
    const val MIN_WINDOW_MS = 10_000L

    /** 走路 90~120 步/分；60 是「确实在迈步」的下限。 */
    const val WALK_MIN_CADENCE = 60.0

    /** 10 步/分以下算没动：手抖、公交颠簸都在这条线下面。 */
    const val STILL_MAX_CADENCE = 10.0

    fun classify(
        steps: Int,
        windowMs: Long,
        cyclingHint: Boolean = false,
        walkingHint: Boolean = false,
    ): ActivityKind {
        // 系统活动识别说在骑车就信它：计步器本来就看不出骑车。
        if (cyclingHint) return ActivityKind.CYCLING
        if (windowMs < MIN_WINDOW_MS || steps < 0) return ActivityKind.UNKNOWN
        if (walkingHint) return ActivityKind.WALKING
        val cadence = steps * 60_000.0 / windowMs
        return when {
            cadence >= WALK_MIN_CADENCE -> ActivityKind.WALKING
            cadence <= STILL_MAX_CADENCE -> ActivityKind.STILL
            // 10~60 步/分是灰区（原地挪、上下楼、挤地铁），拿不准就报不确定。
            else -> ActivityKind.UNKNOWN
        }
    }
}
