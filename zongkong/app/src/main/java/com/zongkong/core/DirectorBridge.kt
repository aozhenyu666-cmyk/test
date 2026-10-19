package com.zongkong.core

import com.zongkong.core.director.Ask

/**
 * 把总控已有的文字模型接口 [LlmApi] 接给导演。
 * 失败、没配置、超时一律返回 null，导演会改用本地模板，不会卡住。
 *
 *   DirectorEngine(facts = ..., ask = LlmAsk(app.llm), store = FileMindStore(File(filesDir, "director/mind.json")))
 */
class LlmAsk(private val api: LlmApi) : Ask {
    override suspend fun ask(system: String, user: String): String? = when (val r = api.complete(system, user)) {
        is ApiResult.Ok -> r.value.takeIf { it.isNotBlank() }
        is ApiResult.Err -> null
    }
}
