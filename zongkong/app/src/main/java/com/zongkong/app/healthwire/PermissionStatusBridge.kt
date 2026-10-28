package com.zongkong.app.healthwire

import android.content.Context
import com.zongkong.app.health.HealthStatus

/**
 * 给界面/其它模块的极简桥：只回答「无障碍现在是不是真的在响应」。
 *
 * 单独放一个轻量对象，是为了让 Pet / PetWidget / Chat / HqRunner 这些老调用点
 * 不必依赖 GuardHealth 的整条初始化链，同时也方便统一口径：
 * **任何地方都不要再读 `GuardService.running` 当健康凭据。**
 */
object PermissionStatusBridge {

    fun status(context: Context): HealthStatus = try {
        GuardHealth.init(context)
        GuardHealth.verdict(GuardHealth.permissionEnabled(context)).status
    } catch (_: Throwable) {
        HealthStatus.UNKNOWN
    }

    fun healthy(context: Context): Boolean = status(context) == HealthStatus.HEALTHY

    /** 进程里还有无障碍服务实例（只表示“客户端还连着”，不代表健康）。 */
    fun attached(context: Context): Boolean = try {
        GuardHealth.init(context)
        GuardHealth.isA11yAttached()
    } catch (_: Throwable) {
        false
    }

    /** 一句话（界面用，不涂绿）。 */
    fun text(context: Context): String = HealthLabels.status(status(context))
}
