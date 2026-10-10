package app.jobtracker.analysis

import app.jobtracker.ai.ModelClient
import app.jobtracker.ai.ModelRole
import app.jobtracker.ai.PromptStore
import kotlinx.serialization.json.JsonObject
import java.io.File

const val VALID_ANALYSIS_JSON = """{
  "company": "示例科技",
  "title": "数据分析师",
  "hardRequirements": ["本科及以上", "熟练使用 SQL"],
  "matchLevel": "medium",
  "strengths": ["三年 SQL 经验"],
  "gaps": ["没有 Python 经验"],
  "advice": "maybe",
  "reason": "技能大体对得上，但缺少 Python。",
  "resumeTips": ["把 SQL 项目放到最前", "量化报表的业务效果", "补充数据看板经历", "第四条应被截掉"]
}"""

/** 直接读源码里的提示词文件，保证测试用的就是真实提示词 */
fun realPrompts() = PromptStore { name -> File("src/main/assets/prompts/$name").readText() }

/** 按顺序返回预设结果的假模型，并记录每次调用 */
class FakeModelClient(private vararg val responses: () -> String) : ModelClient {
    data class Call(val role: ModelRole, val system: String, val user: String, val schema: JsonObject?)

    val calls = mutableListOf<Call>()

    override suspend fun callModel(role: ModelRole, system: String, user: String, outputSchema: JsonObject?): String {
        calls += Call(role, system, user, outputSchema)
        return responses[calls.size - 1]()
    }
}
