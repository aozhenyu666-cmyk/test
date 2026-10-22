package cn.companion.pet.core.scene

/** 开口前判断出的场景。锁屏心跳不走这里，由服务单独处理。 */
enum class Scene(val label: String) {
    LEARNING("学习"),
    WANDERING("游荡"),
    NORMAL("普通"),

    /** 前台在排除名单里：这次不开口。 */
    EXCLUDED("排除"),
}

data class AppLists(
    val learning: Set<String> = emptySet(),
    val feed: Set<String> = emptySet(),
    val focus: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(),
)

data class SceneThresholds(
    val switchWindowMillis: Long = 60_000L,
    val switchApps: Int = 4,
    val launcherSwipeMillis: Long = 40_000L,
    /** 清洗后正文达到这么多码点，就算“屏幕有大段正文”。 */
    val longTextChars: Int = 300,
)

/**
 * 判断结果。[screenText] 只在学习场景下有值，其他场景一律为 null，
 * 这样“游荡场景不发屏幕内容”由类型保证，而不是靠调用方记得。
 */
data class SceneDecision(val scene: Scene, val screenText: String?)

object SceneClassifier {

    /**
     * 判断顺序固定：排除 → 游荡 → 学习名单 → 读屏看是否有大段正文 → 普通。
     *
     * [readScreenText] 只在前两步都没命中时才会被调用，且最多调用一次；
     * 它应返回已经清洗过的正文（读不到就返回空字符串）。
     */
    fun classify(
        now: Long,
        trail: ActivityTrail,
        lists: AppLists,
        launchers: Set<String>,
        thresholds: SceneThresholds = SceneThresholds(),
        readScreenText: () -> String,
    ): SceneDecision {
        val fg = trail.foreground
        if (fg != null && fg in lists.exclude) return SceneDecision(Scene.EXCLUDED, null)

        val wandering = trail.distinctAppsWithin(now, thresholds.switchWindowMillis) >= thresholds.switchApps ||
            trail.launcherSwipeMillis(now, launchers) >= thresholds.launcherSwipeMillis ||
            (fg != null && fg in lists.feed)
        if (wandering) return SceneDecision(Scene.WANDERING, null)

        val text = readScreenText()
        val inLearningList = fg != null && fg in lists.learning
        val longText = cn.companion.pet.core.text.UnicodeText.count(text) >= thresholds.longTextChars
        return if (inLearningList || longText) {
            SceneDecision(Scene.LEARNING, text)
        } else {
            SceneDecision(Scene.NORMAL, null)
        }
    }

    /** 前台是专注 App 且已连续停留 ≥ [thresholdMillis]。 */
    fun focusActive(now: Long, trail: ActivityTrail, lists: AppLists, thresholdMillis: Long = 15 * 60_000L): Boolean {
        val fg = trail.foreground ?: return false
        val since = trail.foregroundSince ?: return false
        return fg in lists.focus && now - since >= thresholdMillis
    }
}
