package app.jobtracker.ai

import kotlinx.serialization.json.JsonObject

/** 匹配分析用强模型，话术用便宜模型；具体模型名在设置里 */
enum class ModelRole { ANALYSIS, DRAFT }

/** 调用失败的原因，界面据此给出提示 */
sealed interface ModelError {
    data object NoApiKey : ModelError
    data object NoNetwork : ModelError
    data object Timeout : ModelError
    data object NetworkFailure : ModelError
    data object Refused : ModelError
    /** 返回内容不是合法 JSON、字段不全或被截断 */
    data object InvalidOutput : ModelError
    data class Http(val code: Int, val message: String?) : ModelError
}

class ModelException(val error: ModelError) : Exception(error.toString())

/**
 * 所有模型调用的唯一入口。以后换服务商只改实现。
 * [outputSchema] 不为空时要求模型按该 JSON Schema 输出。
 */
interface ModelClient {
    suspend fun callModel(role: ModelRole, system: String, user: String, outputSchema: JsonObject? = null): String
}
