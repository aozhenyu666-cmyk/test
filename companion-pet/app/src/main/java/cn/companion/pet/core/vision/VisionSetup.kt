package cn.companion.pet.core.vision

import java.net.URI

enum class VisionSetupStatus { NO_MODEL, NO_KEY, NEEDS_OWN_KEY, READY }

/**
 * 看图配置此刻能不能用、为什么。设置页和“看一眼”都按这个说话，不显示 Key。
 * [keySource] 只是“视觉 Key / 搭话 Key”这样的说明。
 */
data class VisionSetup(
    val status: VisionSetupStatus,
    val model: String = "",
    val host: String = "",
    val keySource: String = "",
    val config: VisionModelConfig? = null,
) {
    val ready: Boolean get() = status == VisionSetupStatus.READY && config != null

    /** 一行给人看的状态。 */
    fun label(): String = when (status) {
        VisionSetupStatus.READY -> "已配置：$model @ $host（用$keySource）"
        VisionSetupStatus.NO_MODEL -> "未配置：还没填视觉模型名（点“千问看图”可一键填好）"
        VisionSetupStatus.NO_KEY -> "未配置：还没有可用的 Key"
        VisionSetupStatus.NEEDS_OWN_KEY -> "未配置：$host 需要它自己的 Key，请填“视觉 Key”"
    }
}

object VisionSetupResolver {
    /**
     * 搭话的 Key 只在看图地址和搭话地址是同一家（同一主机）时才沿用；
     * 换了服务商（比如搭话 DeepSeek、看图千问）却没填视觉 Key，就算未配置——
     * 不把 DeepSeek 的 Key 发给千问，也不发一个注定失败的请求。
     */
    fun resolve(
        visionModel: String,
        visionEndpoint: String,
        chatEndpoint: String,
        visionKey: String?,
        chatKey: String?,
    ): VisionSetup {
        val model = visionModel.trim()
        val endpoint = visionEndpoint.trim().ifBlank { chatEndpoint.trim() }
        val host = host(endpoint)
        if (model.isBlank()) return VisionSetup(VisionSetupStatus.NO_MODEL, host = host)
        val own = visionKey?.trim()?.takeIf { it.isNotEmpty() }
        if (own != null) {
            return VisionSetup(VisionSetupStatus.READY, model, host, "视觉 Key", VisionModelConfig(endpoint, model, own))
        }
        val shared = chatKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: return VisionSetup(VisionSetupStatus.NO_KEY, model, host)
        if (host.isNotEmpty() && host != host(chatEndpoint)) {
            return VisionSetup(VisionSetupStatus.NEEDS_OWN_KEY, model, host)
        }
        return VisionSetup(VisionSetupStatus.READY, model, host, "搭话 Key", VisionModelConfig(endpoint, model, shared))
    }

    internal fun host(endpoint: String): String =
        runCatching { URI(endpoint.trim()).host?.lowercase() }.getOrNull().orEmpty()
}
