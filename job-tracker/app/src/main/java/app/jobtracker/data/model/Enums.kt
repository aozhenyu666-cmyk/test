package app.jobtracker.data.model

// 枚举按名称存入数据库，改名会让旧数据读不出来，只能新增不能改名。

/** 投递状态，只有四个 */
enum class ApplicationStatus { PENDING, APPLIED, INTERVIEWING, CLOSED }

/** 已结束时必须选的结果 */
enum class EndResult { REJECTED, NO_RESPONSE, WITHDRAWN, OFFER }

/** 匹配度，对应分析 JSON 里的 high / medium / low */
enum class MatchLevel {
    HIGH, MEDIUM, LOW;

    companion object {
        fun fromJson(value: String): MatchLevel? = entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
}
