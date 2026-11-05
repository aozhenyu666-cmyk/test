package com.yishou.app.settings

import android.content.Context
import com.yishou.app.BuildConfig
import com.yishou.app.llm.LlmConfig
import org.json.JSONObject
import java.io.File

/**
 * 仅 Debug 构建：从 filesDir/ai-debug.json 导入模型配置，导入后立刻删除。
 *
 * 存在的唯一目的：验收时不必把密钥写进源码、示例或提交记录。
 * Release 构建直接 return；普通安装没有这个文件也不会做任何事。
 */
object DebugAiConfig {

    fun apply(context: Context, settings: SettingsStore) {
        if (!BuildConfig.DEBUG) return
        val file = File(context.filesDir, "ai-debug.json")
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            o.optJSONObject("llm")?.let { j ->
                val cfg = LlmConfig(
                    baseUrl = j.optString("baseUrl").trim(),
                    apiKey = j.optString("apiKey").trim(),
                    model = j.optString("model").trim(),
                    jsonMode = j.optBoolean("jsonMode", true),
                )
                if (cfg.isComplete) settings.saveLlm(cfg)
            }
            o.optJSONObject("vision")?.let { j ->
                val cfg = LlmConfig(
                    baseUrl = j.optString("baseUrl").trim(),
                    apiKey = j.optString("apiKey").trim(),
                    model = j.optString("model").trim(),
                    jsonMode = false,
                )
                if (cfg.isComplete) settings.saveVision(cfg)
            }
        }
        file.delete()
    }
}
