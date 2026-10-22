package cn.companion.pet.core.bridge

/**
 * 桥接到 Gemini 时需要的全部文字输入。
 *
 * 字段全部由上层填好：[timeText] 与 [trajectorySummary] 由调用方格式化好再传进来，
 * 本模块不读系统时钟、不查应用列表。[excerpt] 是调用方传进来的已清洗文字，
 * 本模块仍会强制截断到 600 码点，不依赖上游保证。
 *
 * [isLearning] 决定“我在看的内容 / 原文节选”两个字段如何填：
 * 非学习场景不再信任调用方传入的 [contextSummary] 与 [excerpt]。
 */
data class BridgeContext(
    val timeText: String,
    val trajectorySummary: String,
    val isLearning: Boolean,
    val contextSummary: String,
    val excerpt: String,
    val hookText: String,
    val userInput: String
)
