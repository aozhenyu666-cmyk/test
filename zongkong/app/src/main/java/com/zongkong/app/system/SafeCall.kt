package com.zongkong.app.system

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle

/**
 * 跨进程 call()，但**不把总控和对方绑在一起死**。
 *
 * `ContentResolver.call()` 在调用期间对对方的 ContentProvider 持有「稳定引用」。
 * 安卓的规矩是：对方进程死掉时，凡是还拿着它稳定引用的进程**一起被杀**
 * （系统日志里是 "depends on provider … in dying proc"，死因 REASON_DEPENDENCY_DIED）。
 * 总控的无障碍服务就在主进程里：哨兵 / 一手正好在被 vivo 清理的那一刻被调用，
 * 总控就跟着死，设置里随即显示「无法运行」。
 *
 * 这里改用「不稳定引用」：对方死了，这边只是拿到一个 DeadObjectException（当没回包处理），自己不死。
 * 和 `ContentResolver.call` 一样，找不到对方时抛 IllegalArgumentException（调用方原有的判断不变）。
 */
object SafeCall {
    fun call(resolver: ContentResolver, uri: Uri, method: String, arg: String? = null, extras: Bundle? = null): Bundle? {
        val client = resolver.acquireUnstableContentProviderClient(uri)
            ?: throw IllegalArgumentException("Unknown authority ${uri.authority}")
        return try {
            client.call(method, arg, extras)
        } catch (e: android.os.DeadObjectException) {
            // 对方在调用中途死了：当作没回包。
            null
        } finally {
            runCatching { client.close() }
        }
    }
}
