package com.dshdriver.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import neth.iecal.curbox.api.ICurboxApi
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 和 Curbox（开源 App 拦截器）对接：Curbox 负责识别前台、拦截、放行规则；
 * 思维驾驶员只注册一个"答一道题"的解锁条件，由 [CheckActivity] 出题判卷。
 *
 * 全部按 Curbox API v3 的公开文档实现（CURBOX_API.md），不依赖它的任何内部代码。
 * Binder 调用是阻塞的：只在后台线程用 [inspect] / [register]。
 */
object Curbox {
    const val ACTION_BIND = "neth.iecal.curbox.api.BIND"
    const val ACTION_REQUEST = "neth.iecal.curbox.api.REQUEST_PERMISSION"
    const val ACTION_CHECK = "neth.iecal.curbox.api.CHECK_UNLOCK_CONDITION"
    const val CONDITION_ID = "dsh_driver_question"
    const val CONDITION_LABEL = "思维驾驶员 · 答一道题"
    const val REQUEST_CODE = 7301
    private const val NEED_VERSION = 3

    data class Status(
        val packageName: String,
        val apiVersion: Int,
        val granted: Boolean,
        val registered: Boolean,
        val error: String? = null,
    ) {
        val ready get() = error == null && apiVersion >= NEED_VERSION && granted && registered
    }

    /** 装了哪些 Curbox（正式版 neth.iecal.curbox、调试版 neth.iecal.curbox.debug 都可能在）。 */
    fun installations(context: Context): List<String> =
        context.packageManager.queryIntentServices(Intent(ACTION_BIND), 0)
            .map { it.serviceInfo.packageName }.distinct()
            .sortedBy { if (it == "neth.iecal.curbox") 0 else 1 }

    /** 用哪一个：用户选过的优先，否则正式版优先。绑定、授权、注册必须是同一个。 */
    fun selected(context: Context): String? {
        val all = installations(context)
        return Engine.store.curboxPackage()?.takeIf { it in all } ?: all.firstOrNull()
    }

    /** 拉起 Curbox 的授权页。必须 startActivityForResult，Curbox 靠它确认是谁在申请。 */
    fun requestPermission(activity: Activity, pkg: String): Boolean = runCatching {
        activity.startActivityForResult(Intent(ACTION_REQUEST).setPackage(pkg), REQUEST_CODE)
    }.isSuccess

    /** 查状态：版本、授权、我们的条件注册了没有。 */
    fun inspect(context: Context, pkg: String): Status = withApi(context, pkg) { api ->
        val version = api.apiVersion()
        val granted = api.isGranted
        val registered = granted && version >= NEED_VERSION && runCatching {
            val arr = JSONArray(api.list("UNLOCK_CONDITIONS") ?: "[]")
            (0 until arr.length()).any { i ->
                arr.optJSONObject(i)?.let { it.optString("packageName") == context.packageName && it.optString("id") == CONDITION_ID } == true
            }
        }.getOrDefault(false)
        Status(pkg, version, granted, registered)
    }.getOrElse { Status(pkg, 0, false, false, it.message ?: "连不上 Curbox") }

    /** 注册"答一道题"。重复注册同样的定义是安全的（Curbox 返回 OK）。 */
    fun register(context: Context, pkg: String): Result<String> = withApi(context, pkg) { api ->
        if (api.apiVersion() < NEED_VERSION) return@withApi "这个 Curbox 太旧（API ${api.apiVersion()}），需要 API 3"
        if (!api.isGranted) return@withApi "Curbox 还没批准思维驾驶员"
        val r = api.execute("REGISTER_UNLOCK_CONDITION", Bundle().apply {
            putString("id", CONDITION_ID)
            putString("label", CONDITION_LABEL)
            putString("activity", CheckActivity::class.java.name)
        })
        if (r == "OK") "OK" else "注册失败：$r"
    }

    /** 绑定一次、做完、解绑。等连接最多 4 秒。 */
    private fun <T> withApi(context: Context, pkg: String, block: (ICurboxApi) -> T): Result<T> {
        val latch = CountDownLatch(1)
        var api: ICurboxApi? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                api = ICurboxApi.Stub.asInterface(service)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { api = null }
            override fun onNullBinding(name: ComponentName?) { latch.countDown() }
            override fun onBindingDied(name: ComponentName?) { latch.countDown() }
        }
        val app = context.applicationContext
        val bound = runCatching { app.bindService(Intent(ACTION_BIND).setPackage(pkg), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) return Result.failure(IllegalStateException("Curbox 拒绝连接"))
        return try {
            if (!latch.await(4, TimeUnit.SECONDS)) return Result.failure(IllegalStateException("等 Curbox 超时"))
            val a = api ?: return Result.failure(IllegalStateException("Curbox 没有返回接口"))
            runCatching { block(a) }
        } finally {
            runCatching { app.unbindService(conn) }
        }
    }
}
