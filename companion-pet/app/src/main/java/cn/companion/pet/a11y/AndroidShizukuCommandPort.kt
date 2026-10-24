package cn.companion.pet.a11y

import cn.companion.pet.core.device.CapabilityResult
import cn.companion.pet.core.device.CapabilityStatus
import cn.companion.pet.core.device.ShizukuCommandPort
import rikka.shizuku.Shizuku

/**
 * 固定能力：通过官方 Binder 读取 Shizuku 版本（无 shell、无参数拼接、无模型输入）。
 * 这不是系统 SDK 版本；只证明 Binder/授权链路真实可用。
 */
class AndroidShizukuCommandPort : ShizukuCommandPort {
    override fun readSdkLevel(): CapabilityResult {
        val start = System.currentTimeMillis()
        return runCatching {
            val version = Shizuku.getVersion()
            CapabilityResult(CapabilityStatus.READY, version.toString(),
                "shizuku binder version", System.currentTimeMillis() - start)
        }.getOrElse {
            CapabilityResult(CapabilityStatus.FAILED, reason = "Shizuku Binder 调用失败",
                elapsedMs = System.currentTimeMillis() - start)
        }
    }
}
