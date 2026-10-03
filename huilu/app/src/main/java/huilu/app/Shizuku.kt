package huilu.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import moe.shizuku.api.BinderContainer
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import kotlin.concurrent.thread

/**
 * 设备级动作。真实实现走 Shizuku（以 shell 身份执行命令），测试里替换成假的。
 * 除 [unavailableReason] 外都是阻塞调用，只能在后台线程里用。
 */
interface DeviceControl {
    /** 现在不能用的原因；能用时返回 null。 */
    fun unavailableReason(): String?
    fun suspend(packages: Collection<String>)
    fun unsuspend(packages: Collection<String>)
    fun home(): Boolean
    /** 读取系统里的真实状态，用来确认暂停 / 解除是否真的发生。 */
    fun isSuspended(pkg: String): Boolean
}

/**
 * 最小 Shizuku 客户端，协议与 Shizuku-API 13 一致：
 * Shizuku 服务端通过 [ShizukuBinderProvider] 把 binder 送来 → attachApplication → 申请授权 → newProcess 执行命令。
 * 没有直接依赖 Shizuku-API 的 api 包，是因为它含有 invokedynamic，备用构建的 dx 无法处理；只用了它的 AIDL 接口。
 */
object ShizukuClient {
    const val MANAGER = "moe.shizuku.privileged.api"
    const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
    private const val API_VERSION = 13

    @Volatile private var binder: IBinder? = null
    @Volatile private var service: IShizukuService? = null
    @Volatile private var granted = false
    @Volatile var uid = -1
        private set
    @Volatile var version = -1
        private set

    /** 连接或授权状态变化时调用（在 binder 线程上）。 */
    @Volatile var onChange: (() -> Unit)? = null

    private val application = object : IShizukuApplication.Stub() {
        override fun bindApplication(data: Bundle) {
            uid = data.getInt("shizuku:attach-reply-uid", -1)
            version = data.getInt("shizuku:attach-reply-version", -1)
            granted = data.getBoolean("shizuku:attach-reply-permission-granted", false)
            onChange?.invoke()
        }

        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle) {
            granted = data.getBoolean("shizuku:request-permission-reply-allowed", false)
            onChange?.invoke()
        }

        override fun showPermissionConfirmation(requestUid: Int, requestPid: Int, requestPackageName: String?, requestCode: Int) {}
    }

    private val death = IBinder.DeathRecipient {
        binder = null; service = null; uid = -1; granted = false
        onChange?.invoke()
    }

    fun onBinder(b: IBinder, packageName: String) {
        if (binder?.pingBinder() == true) return
        binder = b
        val s = IShizukuService.Stub.asInterface(b)
        service = s
        try { b.linkToDeath(death, 0) } catch (e: Exception) { Log.w(App.TAG, "shizuku linkToDeath", e) }
        try {
            s.attachApplication(application, Bundle().apply {
                putInt("shizuku:attach-api-version", API_VERSION)
                putString("shizuku:attach-package-name", packageName)
            })
        } catch (e: Throwable) {
            Log.w(App.TAG, "shizuku attachApplication（需要 Shizuku v13 及以上）", e)
        }
        onChange?.invoke()
    }

    enum class State { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    fun state(ctx: Context): State {
        val s = service
        if (s == null || binder?.pingBinder() != true) {
            val installed = try { ctx.packageManager.getPackageInfo(MANAGER, 0); true } catch (_: Exception) { false }
            return if (installed) State.NOT_RUNNING else State.NOT_INSTALLED
        }
        if (!granted) granted = try { s.checkSelfPermission() } catch (_: Exception) { false }
        return if (granted) State.READY else State.NO_PERMISSION
    }

    fun requestPermission(): Boolean = try { service?.requestPermission(1); service != null } catch (_: Exception) { false }

    data class Result(val code: Int, val out: String, val err: String)

    /** 以 Shizuku 的身份（shell 或 root）执行一段 sh 脚本。阻塞。 */
    fun exec(script: String, timeoutMs: Long = 15_000): Result {
        val s = service ?: throw IllegalStateException("Shizuku 未连接")
        val p = s.newProcess(arrayOf("sh", "-c", script), null, null)
        try {
            val out = StringBuilder(); val err = StringBuilder()
            val t1 = thread { ParcelFileDescriptor.AutoCloseInputStream(p.inputStream).bufferedReader().use { out.append(it.readText()) } }
            val t2 = thread { ParcelFileDescriptor.AutoCloseInputStream(p.errorStream).bufferedReader().use { err.append(it.readText()) } }
            if (!p.waitForTimeout(timeoutMs, "MILLISECONDS")) return Result(-1, "", "超时")
            t1.join(2_000); t2.join(2_000)
            return Result(p.exitValue(), out.toString(), err.toString())
        } finally {
            try { p.destroy() } catch (_: Exception) {}
        }
    }
}

/** 接收 Shizuku 服务端送来的 binder。必须 exported，并且只允许持有跨用户权限的调用方（Shizuku 以 shell / root 运行）。 */
class ShizukuBinderProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "sendBinder" && extras != null) {
            extras.classLoader = BinderContainer::class.java.classLoader
            @Suppress("DEPRECATION")
            val c = extras.getParcelable<BinderContainer>(ShizukuClient.EXTRA_BINDER)
            c?.binder?.let { ShizukuClient.onBinder(it, context!!.packageName) }
        }
        return Bundle()
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

class ShizukuDevice(private val ctx: Context) : DeviceControl {
    private val user get() = Process.myUid() / 100_000

    override fun unavailableReason(): String? = when (ShizukuClient.state(ctx)) {
        ShizukuClient.State.NOT_INSTALLED -> "没有安装 Shizuku"
        ShizukuClient.State.NOT_RUNNING -> "Shizuku 没有运行（重启后需要在 Shizuku 里重新启动）"
        ShizukuClient.State.NO_PERMISSION -> "还没有在 Shizuku 里授权回路"
        ShizukuClient.State.READY -> if (Build.VERSION.SDK_INT < 28) "Android 9 以下不支持暂停 App" else null
    }

    override fun suspend(packages: Collection<String>) = run("suspend", packages)

    override fun unsuspend(packages: Collection<String>) = run("unsuspend", packages)

    private fun run(verb: String, packages: Collection<String>) {
        val safe = packages.filter { PKG.matches(it) }
        if (safe.isEmpty()) return
        val r = ShizukuClient.exec(safe.joinToString("; ") { "pm $verb --user $user $it" })
        if (r.code != 0) Log.w(App.TAG, "pm $verb: ${r.code} ${r.err.take(300)}")
    }

    override fun home(): Boolean = ShizukuClient.exec("input keyevent 3").code == 0

    override fun isSuspended(pkg: String): Boolean = try {
        (ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SUSPENDED) != 0
    } catch (_: Exception) {
        false
    }

    companion object {
        /** 包名白名单，拼进 shell 命令前必须通过。 */
        val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}
