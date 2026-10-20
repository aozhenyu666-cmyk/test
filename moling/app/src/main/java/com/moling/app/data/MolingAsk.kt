package com.moling.app.data

import android.util.Log
import com.moling.core.mind.Ask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * 导演和对话页要模型时都走这里。
 * 失败、超时一律返回 null：导演有自己的本地模板兜底，界面不会因为网络停下来。
 * 成功一次就把输入加输出的字数记进当天的用量。
 */
class MolingAsk(
    private val store: SettingsStore,
    private val llm: LlmClient = LlmClient { store.load() },
) : Ask {

    override suspend fun ask(system: String, user: String): String? = try {
        withTimeout(TIMEOUT_MS) {
            when (val r = llm.complete(system, user)) {
                is LlmResult.Ok -> {
                    store.addUsage(system.length + user.length + r.value.length)
                    r.value
                }
                is LlmResult.Err -> {
                    Log.w(TAG, "模型调用失败：${r.message}")
                    null
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "模型调用超时")
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "模型调用出错", e)
        null
    }

    /** 设置页“测试连接”用：发一句最短的话，回来的是回复或中文原因。 */
    suspend fun test(): LlmResult<String> = try {
        withTimeout(TIMEOUT_MS) { llm.complete(TEST_SYSTEM, "你好") }
    } catch (e: TimeoutCancellationException) {
        LlmResult.Err("请求超时（30 秒），网络可能不通")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LlmResult.Err("请求出错：${e.message ?: e.javaClass.simpleName}")
    }

    private companion object {
        private const val TAG = "MolingAsk"

        /** 比 LlmClient 的 30 秒多留一点，让那边先把超时的原因翻成中文。 */
        private const val TIMEOUT_MS = 35_000L

        private const val TEST_SYSTEM = "你是一个测试用的助手。只回一句很短的话，不要客套。"
    }
}
