package app.jobtracker.config

import java.time.LocalTime

/**
 * 所有业务默认值集中在这里。逻辑代码只读取这些值，不直接写数字。
 * 后续模块用到的天数、字数等也在这里，提前放好以免散落各处。
 */
data class AppConfig(
    /** 模型接口地址，可在设置里改成中转地址 */
    val defaultApiBaseUrl: String = "https://api.anthropic.com",
    /** 匹配分析用的强模型 */
    val defaultStrongModel: String = "claude-sonnet-5-5",
    /** 跟进话术用的便宜模型 */
    val defaultFastModel: String = "claude-haiku-5-5",
    /** 每日汇总通知时间 */
    val defaultReminderTime: LocalTime = LocalTime.of(9, 0),
    /** 状态改为"已投递"后，投递日期 + N 天跟进 */
    val followUpAfterAppliedDays: Long = 3,
    /** 状态改为"面试中"后，面试日期 + N 天跟进 */
    val followUpAfterInterviewDays: Long = 1,
    /** 点"已发送"后，从当天起顺延 N 天 */
    val followUpPostponeDays: Long = 3,
    /** 跟进满 N 次后不再生成话术 */
    val maxFollowUps: Int = 2,
    /** 同一条记录连续推送 N 天后不再推送，只在列表标红 */
    val maxReminderDays: Int = 3,
    /** 每个话术版本的字数上限 */
    val draftMaxChars: Int = 80,
    /** 每次生成的话术版本数 */
    val draftVersions: Int = 2,
    /** JD 最少字数 */
    val jdMinChars: Int = 50,
    /** 简历改动要点最多条数 */
    val maxResumeTips: Int = 3,
    /** 模型接口版本头 */
    val anthropicVersion: String = "2023-06-01",
    /** 单次回复的最大 token 数（含思考） */
    val maxOutputTokens: Int = 16000,
    /** 匹配分析的思考深度：low / medium / high；留空表示用模型默认值 */
    val analysisEffort: String = "medium",
    /** 话术生成的思考深度 */
    val draftEffort: String = "low",
    /** 官方接口的主机名。只有直连官方接口时才开启服务端拒答兜底 */
    val officialApiHost: String = "api.anthropic.com",
    /** 支持服务端拒答兜底（fallbacks: "default"）的模型 */
    val serverFallbackModels: Set<String> = setOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-opus-5", "claude-fable-5-1"),
    /** 模型请求超时 */
    val requestTimeoutSeconds: Long = 30,
    /** 返回内容不是合法 JSON 时的自动重试次数 */
    val invalidJsonRetries: Int = 1,
) {
    companion object {
        val Default = AppConfig()
    }
}
