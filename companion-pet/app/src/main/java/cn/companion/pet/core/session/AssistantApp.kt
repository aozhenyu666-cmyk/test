package cn.companion.pet.core.session

/**
 * 能把用户送过去深聊的 AI App。
 *
 * 包名不全靠写死：千问由“通义”改名而来，不同渠道包名可能不同，
 * 所以先试已知包名，再按包名或应用名里的关键词在已安装的 App 里找，用户也可以手动填。
 */
enum class AssistantApp(
    val label: String,
    val knownPackages: List<String>,
    /** 在包名里找（小写比较）。 */
    val packageKeywords: List<String>,
    /** 在应用名里找。 */
    val labelKeywords: List<String>,
    /** 托管它对话界面的其他 App，也算“在它里面”。 */
    val hostPackages: Set<String> = emptySet(),
    /** “一起看”时的提示：怎么打开语音 / 共享屏幕。 */
    val liveHint: String,
) {
    GEMINI(
        label = "Gemini",
        knownPackages = listOf("com.google.android.apps.bard"),
        packageKeywords = listOf("bard"),
        labelKeywords = listOf("Gemini"),
        hostPackages = setOf(GOOGLE_APP_PACKAGE),
        liveHint = "点 Gemini 里的 Live，再点“共享屏幕”",
    ),
    CHATGPT(
        label = "ChatGPT",
        knownPackages = listOf("com.openai.chatgpt"),
        packageKeywords = listOf("openai.chatgpt", "chatgpt"),
        labelKeywords = listOf("ChatGPT"),
        liveHint = "点 ChatGPT 输入框旁的语音按钮，进入语音后打开屏幕共享",
    ),
    QWEN(
        label = "千问",
        knownPackages = listOf("com.aliyun.tongyi"),
        packageKeywords = listOf("qianwen", "tongyi"),
        labelKeywords = listOf("千问", "通义"),
        liveHint = "打开千问的语音或视频通话，再选共享屏幕",
    ),
    ;

    /** 会话里算作“在它里面”的包名。 */
    fun sessionPackages(launchPackage: String): Set<String> = setOf(launchPackage) + hostPackages

    /**
     * 在已安装的 App（包名 → 应用名）里找它的包名。
     * 顺序：用户填的（已安装才算）→ 已知包名 → 包名关键词 → 应用名关键词。找不到返回 null。
     */
    fun resolve(installed: Map<String, String>, override: String?): String? {
        val o = override?.trim().orEmpty()
        if (o.isNotEmpty() && o in installed) return o
        knownPackages.firstOrNull { it in installed }?.let { return it }
        installed.keys.firstOrNull { pkg -> packageKeywords.any { pkg.lowercase().contains(it) } }?.let { return it }
        return installed.entries.firstOrNull { (_, label) -> labelKeywords.any { label.contains(it, ignoreCase = true) } }?.key
    }

    companion object {
        const val GOOGLE_APP = GOOGLE_APP_PACKAGE

        fun fromName(name: String?): AssistantApp = entries.firstOrNull { it.name == name } ?: GEMINI
    }
}

/** Gemini 的对话界面有时跑在 Google App 里。 */
private const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
