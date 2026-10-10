package app.jobtracker.analysis

import app.jobtracker.data.model.MatchLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 投递建议：投 / 可投 / 不建议 */
enum class Advice(val json: String) {
    APPLY("apply"), MAYBE("maybe"), SKIP("skip");

    companion object {
        fun fromJson(value: String): Advice? = entries.firstOrNull { it.json == value.trim().lowercase() }
    }
}

/** 匹配分析结果，字段与 assets/prompts/analysis_schema.json 一一对应 */
@Serializable
data class MatchAnalysis(
    val company: String,
    val title: String,
    val hardRequirements: List<String>,
    val matchLevel: String,
    val strengths: List<String>,
    val gaps: List<String>,
    val advice: String,
    val reason: String,
    val resumeTips: List<String>,
) {
    val level: MatchLevel get() = MatchLevel.fromJson(matchLevel)!!
    val adviceValue: Advice get() = Advice.fromJson(advice)!!

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 解析模型输出。不是合法 JSON、缺字段、枚举值不对都返回 null（由调用方重试）。
         * 简历改动要点超过上限时只保留前几条。
         */
        fun parse(text: String, maxResumeTips: Int): MatchAnalysis? {
            val parsed = try {
                json.decodeFromString(serializer(), stripCodeFence(text))
            } catch (e: Exception) {
                return null
            }
            if (MatchLevel.fromJson(parsed.matchLevel) == null || Advice.fromJson(parsed.advice) == null) return null
            return parsed.copy(
                company = parsed.company.trim(),
                title = parsed.title.trim(),
                resumeTips = parsed.resumeTips.filter { it.isNotBlank() }.take(maxResumeTips),
            )
        }

        /** 有的中转接口不支持结构化输出，模型可能把 JSON 包在 ``` 里 */
        internal fun stripCodeFence(text: String): String {
            val t = text.trim()
            if (!t.startsWith("```")) return t
            return t.substringAfter('\n').substringBeforeLast("```").trim()
        }
    }
}
