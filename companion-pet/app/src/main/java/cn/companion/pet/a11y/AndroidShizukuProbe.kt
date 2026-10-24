package cn.companion.pet.a11y

import android.content.Context
import android.content.pm.PackageManager
import cn.companion.pet.core.device.ShizukuDiagnostic
import cn.companion.pet.core.device.ShizukuProbe
import cn.companion.pet.core.device.ShizukuStatus
import cn.companion.pet.core.device.ShizukuStatusResolver
import rikka.shizuku.Shizuku

/**
 * 官方 Shizuku API 的状态/授权薄层。这里不执行任意 shell，也不申请 root；
 * 具体能力由各 Adapter 在 READY 时单独检查。
 */
class AndroidShizukuProbe(private val context: Context) : ShizukuProbe {
    override fun diagnostic(): ShizukuDiagnostic {
        val installed = runCatching {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        }.isSuccess
        val binder = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val pre = runCatching { Shizuku.isPreV11() }.getOrDefault(false)
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        val status = ShizukuStatusResolver.resolve(installed, binder, pre, granted)
        return ShizukuDiagnostic(status, detail(status))
    }

    override fun requestPermission(): Boolean {
        if (diagnostic().status != ShizukuStatus.NO_PERMISSION) return false
        return runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    private fun detail(status: ShizukuStatus): String = when (status) {
        ShizukuStatus.NOT_INSTALLED -> "未安装 Shizuku"
        ShizukuStatus.NOT_RUNNING -> "Shizuku 服务未运行"
        ShizukuStatus.NO_PERMISSION -> "尚未授予 Shizuku 权限"
        ShizukuStatus.READY -> "Shizuku 就绪"
        ShizukuStatus.DISCONNECTED -> "Shizuku Binder 已断开"
        ShizukuStatus.UNSUPPORTED -> "Shizuku 版本不受支持"
    }

    companion object { const val REQUEST_CODE = 0x5A1A }
}
