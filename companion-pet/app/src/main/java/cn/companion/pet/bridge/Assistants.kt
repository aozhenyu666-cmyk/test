package cn.companion.pet.bridge

import android.content.Context
import android.content.Intent
import cn.companion.pet.core.session.AssistantApp
import cn.companion.pet.data.Settings

/**
 * 找到手机上 Gemini / ChatGPT / 千问 的包名。
 * 已安装 App 列表缓存一分钟，避免每秒都去查。
 */
class Assistants(private val context: Context, private val settings: Settings) {
    private var cache: Map<String, String> = emptyMap()
    private var cachedAt = 0L

    val current: AssistantApp get() = AssistantApp.fromName(settings.assistant)

    fun installed(): Map<String, String> {
        val now = System.currentTimeMillis()
        if (now - cachedAt > 60_000L || cache.isEmpty()) {
            cache = runCatching {
                val pm = context.packageManager
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            }.getOrDefault(emptyMap())
            cachedAt = now
        }
        return cache
    }

    fun override(app: AssistantApp): String = when (app) {
        AssistantApp.GEMINI -> settings.geminiPackage
        AssistantApp.CHATGPT -> settings.chatgptPackage
        AssistantApp.QWEN -> settings.qwenPackage
    }

    fun setOverride(app: AssistantApp, pkg: String) {
        when (app) {
            AssistantApp.GEMINI -> settings.geminiPackage = pkg
            AssistantApp.CHATGPT -> settings.chatgptPackage = pkg
            AssistantApp.QWEN -> settings.qwenPackage = pkg
        }
    }

    /** 找不到就退回用户填的（可能因为软件包可见性查不到，但其实装了）。 */
    fun packageOf(app: AssistantApp): String? =
        app.resolve(installed(), override(app)) ?: override(app).trim().takeIf { it.isNotEmpty() }

    fun isInstalled(app: AssistantApp): Boolean = app.resolve(installed(), override(app)) != null

    /** 前台是不是某个 AI App（不含 Google App 这类托管包）。 */
    fun detect(pkg: String?): AssistantApp? =
        if (pkg == null) null else AssistantApp.entries.firstOrNull { packageOf(it) == pkg }
}
