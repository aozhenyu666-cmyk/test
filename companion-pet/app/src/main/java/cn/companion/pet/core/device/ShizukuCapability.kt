package cn.companion.pet.core.device

enum class CapabilityStatus { READY, UNAVAILABLE, UNSUPPORTED, DENIED, FAILED }

data class CapabilityResult(
    val status: CapabilityStatus,
    val value: String = "",
    val reason: String = "",
    val elapsedMs: Long = 0,
)

/** 固定白名单能力的执行端口；生产只用固定命令，不接受模型输入。 */
interface ShizukuCommandPort {
    fun readSdkLevel(): CapabilityResult
}

/**
 * 唯一新增的 Shizuku 能力：只读系统 SDK 级别（固定 getprop 命令，无参数拼接）。
 * READY 只表示 Binder/授权通过；UNAVAILABLE/NOT_RUNNING 等不冒充成功。
 */
class ShizukuCapabilityRunner(
    private val probe: ShizukuProbe,
    private val port: ShizukuCommandPort,
) {
    fun readSdkDiagnostic(): CapabilityResult {
        val diag = probe.diagnostic()
        if (diag.status != ShizukuStatus.READY) {
            return CapabilityResult(CapabilityStatus.UNAVAILABLE, reason = diag.detail)
        }
        return port.readSdkLevel()
    }
}
