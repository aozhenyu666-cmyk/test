package com.zongkong.sentinel

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.AtomicFile
import java.io.File

/** 只追加用户选中的三个服务，不冒充它们已接入健康挑战协议。设置跨进程从文件重新读。 */
object DisabledGuardian {
    val targets = linkedMapOf(
        "com.dshdriver.app" to "思维驾驶员",
        "com.zongkong.app" to "总控",
        "com.yishou.app" to "一手",
    )
    private val components = mapOf(
        "com.dshdriver.app" to "com.dshdriver.app/com.dshdriver.app.WatchService",
        "com.zongkong.app" to "com.zongkong.app/com.zongkong.app.guard.GuardService",
        "com.yishou.app" to "com.yishou.app/com.yishou.app.gate.GateService",
    )
    private fun flag(c: Context, pkg: String) = File(c.filesDir, "restore-$pkg.enabled")
    fun enabled(c: Context, pkg: String) = pkg in targets && flag(c, pkg).exists()
    fun setEnabled(c: Context, pkg: String, on: Boolean) {
        if (pkg !in targets) return
        if (on) flag(c, pkg).createNewFile() else flag(c, pkg).delete()
    }
    fun last(c: Context, pkg: String): String = runCatching {
        File(c.filesDir, "restore-$pkg.last").readText()
    }.getOrDefault("无写回记录")

    fun tick(c: Context) {
        if (!Sys.canWrite(c)) return
        for ((pkg, _) in targets) {
            if (!enabled(c, pkg)) continue
            val component = ComponentName.unflattenFromString(components.getValue(pkg)) ?: continue
            // 未安装、组件不存在时不写进系统设置。
            if (runCatching { c.packageManager.getServiceInfo(component, 0) }.isFailure) continue
            val result = Arc.ensureEnabled(c, component) ?: continue
            val text = if (result) "${System.currentTimeMillis()}：已写回设置，服务是否响应待验证" else "${System.currentTimeMillis()}：写回失败"
            val file = AtomicFile(File(c.filesDir, "restore-$pkg.last"))
            runCatching {
                val stream = file.startWrite()
                try { stream.write(text.toByteArray()); file.finishWrite(stream) } catch (e: Throwable) { file.failWrite(stream); throw e }
            }
            Arc.log(c, "$pkg：$text")
        }
    }
}
