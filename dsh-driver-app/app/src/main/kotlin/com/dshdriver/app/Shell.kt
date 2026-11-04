package com.dshdriver.app

import android.content.pm.PackageManager
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * 用 Shizuku 的 shell 身份执行命令（用来把无障碍服务写回去、加电池白名单）。
 *
 * 抄自 Curbox（GPL-3.0）`app/src/main/java/neth/iecal/curbox/utils/ShizukuRunner.kt`，
 * 提交 b06520f4dc35018c184b65341fbc4ffe31b2eb08。保留它"起进程 → 读 stdout/stderr → 回调"的结构，
 * 去掉分批回调和协程依赖。本文件同样以 GPL-3.0 发布，见仓库根 LICENSE。
 */
object Shell {

    /** Shizuku 服务在不在（用户重启后没重新启动 Shizuku 时会是 false）。 */
    fun available(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 有没有拿到 shell 权限。 */
    fun granted(): Boolean = available() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 弹 Shizuku 的授权框。只能在有界面的地方调。 */
    fun request(requestCode: Int = 1) {
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    /**
     * 命令跑在后台线程，[done] 在结束时回调一次（成功失败都回调，别让调用方一直等）。
     * 同 UID 的进程才能读到进程句柄，所以这里不能跨进程用。
     */
    fun run(command: String, done: (ok: Boolean, output: String) -> Unit = { _, _ -> }) {
        if (!granted()) {
            done(false, "没有 Shizuku 权限")
            return
        }
        Thread {
            var output = ""
            val ok = runCatching {
                val process = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                    .newProcess(arrayOf("sh", "-c", command), null, null)
                val out = BufferedReader(InputStreamReader(FileInputStream(process.inputStream.fileDescriptor)))
                val err = BufferedReader(InputStreamReader(FileInputStream(process.errorStream.fileDescriptor)))
                val sb = StringBuilder()
                out.forEachLine { sb.append(it).append('\n') }
                val errText = StringBuilder()
                err.forEachLine { errText.append(it).append('\n') }
                process.waitFor()
                output = if (errText.isNotBlank()) errText.toString() else sb.toString()
                errText.isBlank()
            }.getOrElse {
                output = it.message ?: "Shizuku 执行失败"
                false
            }
            done(ok, output.trim())
        }.start()
    }
}
