package huilu.app

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import huilu.core.ActResult
import huilu.core.AnswerKind
import huilu.core.EnvMode
import huilu.core.Expectation
import huilu.core.Level
import huilu.core.Outcome
import moe.shizuku.api.BinderContainer
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IShizukuServiceConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

class FakeDevice : DeviceControl {
    var reason: String? = null
    val suspended = mutableSetOf<String>()
    var refuse = setOf<String>()
    var homes = 0
    override fun unavailableReason() = reason
    override fun suspend(packages: Collection<String>) { suspended += packages - refuse }
    override fun unsuspend(packages: Collection<String>) { suspended -= packages.toSet() }
    override fun home(): Boolean { homes++; return true }
    override fun isSuspended(pkg: String) = pkg in suspended
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ShizukuTest {
    private lateinit var app: App
    private val fake = FakeDevice()
    private val usm get() = shadowOf(app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)

    @Before
    fun setUp() {
        LoopService.instance = null
        GuardService.instance = null
        CheckInActivity.current = null
        App.clock = { 1_790_000_000_000L + SystemClock.uptimeMillis() }
        app = RuntimeEnvironment.getApplication() as App
        app.device = fake
        app.io = java.util.concurrent.Executor { it.run() }
        for ((p, l) in listOf("com.example.course" to "网课", "tv.danmaku.bili" to "哔哩哔哩", "com.ss.android.ugc.aweme" to "抖音")) {
            val ai = ApplicationInfo().apply { packageName = p; name = l; nonLocalizedLabel = l }
            shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = p; applicationInfo = ai })
        }
    }

    private fun now() = App.now()
    private fun fg(pkg: String) = usm.addEvent(pkg, now(), UsageEvents.Event.ACTIVITY_RESUMED)
    private fun bg(pkg: String) = usm.addEvent(pkg, now(), UsageEvents.Event.ACTIVITY_PAUSED)
    private fun advance(min: Double) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis((min * 60_000).toLong()))

    private fun driftFourTimes() {
        fg("com.example.course")
        app.perform(app.engine.start("背单词", "", 60, Expectation(EnvMode.IN_APPS, setOf("com.example.course")), strict = true))
        Robolectric.buildService(LoopService::class.java).create()
        repeat(4) {
            bg("com.example.course"); fg("tv.danmaku.bili")
            advance(2.3)
            if (it < 3) {
                app.perform(app.engine.answer(app.engine.situation.pending!!.id, AnswerKind.RECOMMIT))
                bg("tv.danmaku.bili"); fg("com.example.course")
                advance(0.5)
            }
        }
    }

    @Test
    fun pausesOnlyWhatReallyGotPausedAndReleasesAtTheEnd() {
        assertTrue(Level.DEVICE in app.platform.available())
        driftFourTimes()
        val ivs = app.episodes().last().interventions
        val device = ivs.single { it.intervention.level == Level.DEVICE }
        assertEquals(ActResult.VERIFIED, device.result)
        assertEquals(setOf("tv.danmaku.bili", "com.ss.android.ugc.aweme"), fake.suspended)
        assertEquals(fake.suspended, app.engine.situation.locked)
        assertTrue("被暂停的 App 仍在前台时送回桌面", fake.homes >= 1)

        app.perform(app.engine.stop(Outcome.DONE))
        advance(0.01)
        assertTrue(fake.suspended.isEmpty())
        assertTrue(app.engine.situation.locked.isEmpty())
    }

    @Test
    fun partialPauseIsReportedAsPartialAndReleaseKeepsRetrying() {
        fake.refuse = setOf("com.ss.android.ugc.aweme")
        driftFourTimes()
        val device = app.episodes().last().interventions.single { it.intervention.level == Level.DEVICE }
        assertEquals(ActResult.NO_EFFECT, device.result)
        assertTrue(device.resultDetail!!, device.resultDetail!!.contains("未能暂停 抖音"))
        assertEquals(setOf("tv.danmaku.bili"), app.engine.situation.locked)

        // Shizuku 断开：解除做不到，状态必须保留，并在主页上可见
        fake.reason = "Shizuku 没有运行"
        app.perform(app.engine.stop(Outcome.DONE))
        advance(1.0)
        assertEquals(setOf("tv.danmaku.bili"), app.engine.situation.locked)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup().get().window.decorView
        assertTrue(texts(main).contains("仍有 App 处于暂停：哔哩哔哩"))

        // Shizuku 回来：30 秒内的下一次重试会解除
        fake.reason = null
        advance(1.0)
        assertTrue(app.engine.situation.locked.isEmpty())
        assertTrue(fake.suspended.isEmpty())
    }

    private fun texts(v: android.view.View): String =
        if (v is android.view.ViewGroup) (0 until v.childCount).joinToString("\n") { texts(v.getChildAt(it)) }
        else (v as? android.widget.TextView)?.text?.toString() ?: ""

    /** 一个进程内的假 Shizuku 服务，用来检查客户端协议。 */
    private class FakeShizuku : IShizukuService.Stub() {
        var attachedArgs: Bundle? = null
        var app: IShizukuApplication? = null
        var permission = false
        var lastCmd: List<String>? = null
        override fun attachApplication(application: IShizukuApplication, args: Bundle) { app = application; attachedArgs = args }
        override fun checkSelfPermission() = permission
        override fun requestPermission(requestCode: Int) {
            permission = true
            app!!.dispatchRequestPermissionResult(requestCode, Bundle().apply { putBoolean("shizuku:request-permission-reply-allowed", true) })
        }
        override fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): IRemoteProcess {
            lastCmd = cmd.toList()
            val out = ParcelFileDescriptor.createPipe()
            val err = ParcelFileDescriptor.createPipe()
            ParcelFileDescriptor.AutoCloseOutputStream(out[1]).use { it.write("ok\n".toByteArray()) }
            ParcelFileDescriptor.AutoCloseOutputStream(err[1]).close()
            return object : IRemoteProcess.Stub() {
                override fun getOutputStream(): ParcelFileDescriptor? = null
                override fun getInputStream() = out[0]
                override fun getErrorStream() = err[0]
                override fun waitFor() = 0
                override fun exitValue() = 0
                override fun destroy() {}
                override fun alive() = false
                override fun waitForTimeout(timeout: Long, unit: String?) = true
            }
        }
        override fun getVersion() = 13
        override fun getUid() = 2000
        override fun checkPermission(permission: String?) = 0
        override fun getSELinuxContext() = "u:r:shell:s0"
        override fun getSystemProperty(name: String?, defaultValue: String?) = defaultValue
        override fun setSystemProperty(name: String?, value: String?) {}
        override fun addUserService(conn: IShizukuServiceConnection?, args: Bundle?) = 0
        override fun removeUserService(conn: IShizukuServiceConnection?, args: Bundle?) = 0
        override fun exit() {}
        override fun attachUserService(binder: android.os.IBinder?, options: Bundle?) {}
        override fun dispatchPackageChanged(intent: android.content.Intent?) {}
        override fun isHidden(uid: Int) = false
        override fun dispatchPermissionConfirmationResult(requestUid: Int, requestPid: Int, requestCode: Int, data: Bundle?) {}
        override fun getFlagsForUid(uid: Int, mask: Int) = 0
        override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {}
        override fun shouldShowRequestPermissionRationale() = false
    }

    @Test
    fun clientSpeaksTheShizukuProtocol() {
        app.device = ShizukuDevice(app)
        assertEquals(ShizukuClient.State.NOT_INSTALLED, ShizukuClient.state(app))

        // Shizuku 服务端通过 provider 把 binder 送进来
        val server = FakeShizuku()
        val provider = Robolectric.setupContentProvider(ShizukuBinderProvider::class.java, "huilu.app.shizuku")
        provider.call("sendBinder", null, Bundle().apply { putParcelable(ShizukuClient.EXTRA_BINDER, BinderContainer(server)) })
        assertEquals("huilu.app", server.attachedArgs!!.getString("shizuku:attach-package-name"))
        assertEquals(13, server.attachedArgs!!.getInt("shizuku:attach-api-version"))
        assertEquals(ShizukuClient.State.NO_PERMISSION, ShizukuClient.state(app))
        assertEquals("还没有在 Shizuku 里授权回路", app.device.unavailableReason())
        assertTrue(Level.DEVICE !in app.platform.available())

        // 用户在 Shizuku 弹窗里允许
        assertTrue(ShizukuClient.requestPermission())
        assertEquals(ShizukuClient.State.READY, ShizukuClient.state(app))
        assertEquals(null, app.device.unavailableReason())
        assertTrue(Level.DEVICE in app.platform.available())
        assertTrue(Level.HOME in app.platform.available())

        // 命令以 sh -c 执行；包名白名单挡住注入
        val r = ShizukuClient.exec("echo ok")
        assertEquals(0, r.code)
        assertEquals("ok\n", r.out)
        app.device.suspend(listOf("tv.danmaku.bili", "x; reboot"))
        assertEquals(listOf("sh", "-c", "pm suspend --user 0 tv.danmaku.bili"), server.lastCmd)
    }
}
