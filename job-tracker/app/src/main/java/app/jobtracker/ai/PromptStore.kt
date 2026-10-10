package app.jobtracker.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 提示词放在 assets/prompts/ 下的独立文件里，改提示词不用动代码。
 * 模板里用 {{name}} 占位，一次性替换，用户文字里出现 {{...}} 也不会被二次替换。
 */
class PromptStore(private val read: (String) -> String) {
    private val cache = mutableMapOf<String, String>()

    fun text(name: String): String = synchronized(cache) { cache.getOrPut(name) { read(name) } }

    fun render(name: String, vars: Map<String, String>): String =
        PLACEHOLDER.replace(text(name)) { vars[it.groupValues[1]] ?: it.value }

    fun schema(name: String): JsonObject = Json.parseToJsonElement(text(name)).jsonObject

    private companion object {
        val PLACEHOLDER = Regex("""\{\{(\w+)\}\}""")
    }
}
