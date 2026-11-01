package com.zongkong.core.buddy

import java.net.URI

/**
 * 外接大脑的网络规则（纯逻辑，不碰 Android，单元测试直接跑）：
 *  - 地址能不能用：远程只走 https；明文 http 只放行「这台手机自己」（主机正好是 127.0.0.1 或 localhost）。
 *  - 失败了重不重试、等多久、什么时候放弃（过时的行为事件直接丢，不攒着恢复后一起补发）。
 *  - 把失败说成人话：分清「本机 DSH 没在跑」「口令不对」「DSH 收到了但在线模型暂时连不上」，不一律说成地址错。
 */
object BridgeNet {
    /** 同机 DSH 桥接的默认地址：127.0.0.1 就是这台手机自己，切 Wi-Fi、用流量、出门都不用改。 */
    const val LOCAL_DEFAULT = "http://127.0.0.1:7861/zongkong/event"

    private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost")

    sealed interface Check {
        /** 可以用。[local] = 同机明文直连。 */
        data class Ok(val scheme: String, val host: String, val local: Boolean) : Check
        data class Bad(val why: String) : Check
    }

    /**
     * 用 URI 解析器看主机，不用字符串前缀猜（`http://127.0.0.1.evil.com`、`http://127.0.0.1@evil.com`
     * 这类伪装都会被认出真正的主机）。带用户名密码段、反斜杠、空白的一律拒绝。
     */
    fun check(raw: String): Check {
        val url = raw.trim()
        if (url.isEmpty()) return Check.Bad("还没填地址")
        if (url.any { it == '\\' || it.isWhitespace() || it.isISOControl() }) return Check.Bad("地址里有不该出现的字符")
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return Check.Bad("地址格式不对")
        }
        val scheme = uri.scheme?.lowercase() ?: return Check.Bad("地址要以 https:// 开头（同机 DSH 用 http://127.0.0.1:端口/…）")
        if (uri.isOpaque) return Check.Bad("地址格式不对")
        if (uri.rawUserInfo != null) return Check.Bad("地址里不能带用户名或密码")
        val host = uri.host?.lowercase()
        if (host.isNullOrBlank()) return Check.Bad("地址里没有主机名")
        return when (scheme) {
            "https" -> Check.Ok("https", host, local = host in LOCAL_HOSTS)
            "http" ->
                if (host in LOCAL_HOSTS) Check.Ok("http", host, local = true)
                else Check.Bad("http 只能连这台手机自己（127.0.0.1 或 localhost）；连别的电脑或服务器请用 https://")
            else -> Check.Bad("只支持 https://，或同机的 http://127.0.0.1")
        }
    }

    // ---------- 重试 ----------

    /** 第 n 次失败后等多久再试（共最多 4 次重试）。 */
    val BACKOFF_MS = listOf(2_000L, 5_000L, 15_000L, 30_000L)

    /** 事件过了这么久还没送到就丢掉：说的是「刚刚」的事，晚了再推只会打扰。测试事件同理。 */
    const val MAX_AGE_MS = 2 * 60_000L

    /** 这种失败值不值得再试：网络层错误、超时、限流、服务端错误要试；口令错、路径错这类不试。 */
    fun retryable(code: Int?): Boolean = code == null || code == 408 || code == 425 || code == 429 || code in 500..599

    /**
     * 第 [attempt] 次（从 0 开始）失败之后：返回下一次之前要等的毫秒数，null = 放弃。
     * 下一次发出时事件已经超过 [MAX_AGE_MS] 的，也放弃。
     */
    fun nextDelay(attempt: Int, eventAt: Long, now: Long, code: Int?): Long? {
        if (!retryable(code)) return null
        val wait = BACKOFF_MS.getOrNull(attempt) ?: return null
        if (now + wait - eventAt > MAX_AGE_MS) return null
        return wait
    }

    // ---------- 人话 ----------

    /** 一次尝试的结果说成人话。[error] 和 [code] 二选一。 */
    fun explain(code: Int?, error: Throwable?, local: Boolean, body: String = ""): String {
        val tail = body.trim().replace(Regex("\\s+"), " ").take(80).let { if (it.isEmpty()) "" else "（DSH 说：$it）" }
        if (error != null) return when (error) {
            is java.net.ConnectException ->
                if (local) "本机 DSH 没在运行：127.0.0.1 这个端口上没有服务在听（DSH 或桥接被系统停了？打开它再试）"
                else "连不上远程地址（当前没网，或隧道 / 电脑没开）"
            is java.net.SocketTimeoutException ->
                if (local) "本机 DSH 没及时回应（可能卡住了，或正在启动）" else "远程地址超时（网络慢或刚断了）"
            is java.net.UnknownHostException -> "找不到这个域名（当前没网，或地址写错了）"
            is javax.net.ssl.SSLException -> "HTTPS 握手失败（证书不对，或隧道配置有问题）"
            else -> "没发出去：${error.message ?: error.javaClass.simpleName}"
        }
        val c = code ?: return "没有结果"
        return when {
            c in 200..299 -> "已送达（HTTP $c）：DSH 收到了这条事件。伙伴有没有回、回了什么，要在 QQ 或 DSH 里看$tail"
            c == 401 || c == 403 -> "口令不对：DSH 拒绝了（HTTP $c）"
            c == 404 -> "DSH 在运行，但没有这个路径（HTTP 404）：检查地址末尾是不是 /zongkong/event"
            c in 500..599 -> "DSH 收到了但处理失败（HTTP $c），常见是在线模型暂时连不上；会自动重试$tail"
            c == 429 -> "DSH 说太频繁了（HTTP 429），稍后重试"
            else -> "DSH 回了 HTTP $c$tail"
        }
    }
}
