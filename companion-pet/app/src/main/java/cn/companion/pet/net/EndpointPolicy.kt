package cn.companion.pet.net

import java.net.URI

/**
 * 接口地址规则：只允许 HTTPS；拒绝 userinfo、query、fragment（空的 ? 或 # 也拒绝）；
 * 去掉末尾 /，路径不是以 /chat/completions 结尾就补上一次。
 */
object EndpointPolicy {
    private const val SUFFIX = "/chat/completions"

    fun normalize(value: String): String {
        val raw = value.trim()
        require(raw.isNotEmpty()) { "empty endpoint" }
        require('?' !in raw && '#' !in raw) { "query or fragment not allowed" }
        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid endpoint")
        }
        require(uri.scheme.equals("https", ignoreCase = true)) { "https only" }
        require(uri.rawUserInfo == null && '@' !in (uri.rawAuthority ?: "")) { "userinfo not allowed" }
        val host = uri.host
        require(!host.isNullOrBlank()) { "missing host" }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        var path = (uri.rawPath ?: "").trimEnd('/')
        if (!path.endsWith(SUFFIX)) path += SUFFIX
        return "https://$host$port$path"
    }

    fun isValid(value: String): Boolean = runCatching { normalize(value) }.isSuccess
}
