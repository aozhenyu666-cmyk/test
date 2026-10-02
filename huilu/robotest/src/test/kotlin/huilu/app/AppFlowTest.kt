package huilu.app

import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.os.SystemClock
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import huilu.core.AnswerKind
import huilu.core.EnvMode
import huilu.core.Expectation
import huilu.core.Mode
import huilu.core.Outcome
import huilu.core.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowLog
import java.time.Duration

/**
 * 在 Robolectric 里跑真实的 Android 组件：
 * 主页开始一个行动 → 前台服务每 15 秒推进 → UsageStats 里出现 B站 → 检查通知弹出 →
 * 点通知按钮回答 → 回到目标 App → 到点后在检查页回答"完成了" → 回顾页能看到这一轮的偏差。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AppFlowTest {
    private lateinit var app: App
    private val usm get() = shadowOf(app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)

    @Before
    fun setUp() {
        // Robolectric 在测试之间复用类加载器，App 自己的静态引用要手动清掉
        LoopService.instance = null
        GuardService.instance = null
        CheckInActivity.current = null
        ShadowLog.stream = System.out
        // App 的时间跟随 Robolectric 的受控时钟，idleFor 推进多久，App 就过去多久
        val base = 1_790_000_000_000L
        App.clock = { base + SystemClock.uptimeMillis() }
        app = RuntimeEnvironment.getApplication() as App
        shadowOf(app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_ALLOWED)
        install("com.example.course", "网课")
        install("tv.danmaku.bili", "哔哩哔哩")
    }

    private fun install(pkg: String, label: String) {
        val ai = ApplicationInfo().apply { packageName = pkg; name = label; nonLocalizedLabel = label }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ai })
    }

    private fun now() = App.now()
    private fun foreground(pkg: String) = usm.addEvent(pkg, now(), UsageEvents.Event.ACTIVITY_RESUMED)
    private fun background(pkg: String) = usm.addEvent(pkg, now(), UsageEvents.Event.ACTIVITY_PAUSED)
    private fun advance(minutes: Double) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis((minutes * 60_000).toLong()))

    private fun checkInNotifications() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        .filter { it.channelId == Notifier.CH_CHECK }

    private fun View.all(): List<View> = if (this is ViewGroup) listOf(this) + (0 until childCount).flatMap { getChildAt(it).all() } else listOf(this)
    private fun View.texts() = all().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun View.click(label: String) {
        val b = all().filterIsInstance<Button>().firstOrNull { it.text.toString() == label || (label.endsWith("…") && it.text.startsWith(label.dropLast(1))) }
            ?: error("找不到按钮「$label」：\n${texts()}")
        b.performClick()
    }

    @Test
    fun theLoopRunsOnAndroid() {
        foreground("com.example.course")
        app.prefs.lastTargets = setOf("com.example.course")
        app.prefs.lastMode = EnvMode.IN_APPS

        // 1. 主页：声明接下来十分钟做什么
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        val root = main.get().window.decorView
        assertTrue(root.texts().contains("接下来做什么？"))
        root.all().filterIsInstance<EditText>()[0].setText("学网课第 3 节")
        root.all().filterIsInstance<EditText>()[1].setText("周五要交作业")
        root.click("10")
        main.get().window.decorView.click("开始")
        val s = app.engine.situation
        assertEquals(Mode.ACTING, s.mode)
        assertEquals("学网课第 3 节", s.action!!.text)
        assertEquals(Expectation(EnvMode.IN_APPS, setOf("com.example.course")), s.action!!.expect)

        // 2. 前台服务启动，常驻通知显示当前行动
        val started = shadowOf(app).nextStartedService
        assertEquals(LoopService::class.java.name, started.component!!.className)
        Robolectric.buildService(LoopService::class.java, started).create().startCommand(0, 1)
        val ongoing = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.first { it.channelId == Notifier.CH_NOW }
        assertTrue(ongoing.extras.getString("android.title")!!.contains("学网课第 3 节"))
        assertTrue(main.get().window.decorView.texts().contains("网课"))

        // 3. 第 3 分钟切到 B站，连续两分钟后系统主动出现
        advance(3.0)
        background("com.example.course"); foreground("tv.danmaku.bili")
        advance(2.3)
        val c = app.engine.situation.pending
        assertNotNull("应该已经发出偏离检查", c)
        assertEquals(Trigger.DRIFT, c!!.trigger)
        val n = checkInNotifications().single()
        assertTrue(n.extras.getCharSequence("android.bigText").toString().contains("哔哩哔哩"))
        assertTrue(n.actions.any { it.title == AnswerKind.RECOMMIT.label })

        // 4. 在通知上点"偏了，现在回去"，然后真的回到网课
        val tap = Intent(app, AnswerReceiver::class.java).setAction(AnswerReceiver.ANSWER)
            .putExtra(AnswerReceiver.KEY_ID, c.id).putExtra(AnswerReceiver.KEY_KIND, AnswerKind.RECOMMIT.name)
        AnswerReceiver().onReceive(app, tap)
        assertEquals(null, app.engine.situation.pending)
        assertTrue(checkInNotifications().isEmpty())
        background("tv.danmaku.bili"); foreground("com.example.course")

        // 5. 通知这次干预被确认"已发出"，两分钟后评估效果：回到了目标
        advance(2.5)
        val ep = app.episodes().last()
        val iv = ep.interventions.first { it.intervention.target == "tv.danmaku.bili" }
        assertEquals(huilu.core.ActResult.VERIFIED, iv.result)
        assertEquals(huilu.core.Aftermath.RETURNED, iv.aftermath)

        // 6. 短间隔复查 → 回答在做 → 到点确认
        advance(1.0)
        app.engine.situation.pending?.let { app.perform(app.engine.answer(it.id, AnswerKind.ON_TRACK)) }
        advance(5.0)
        val end = app.engine.situation.pending
        assertEquals(Trigger.END, end?.trigger)

        // 7. 在检查页上回答"完成了"
        val check = Robolectric.buildActivity(CheckInActivity::class.java, CheckInActivity.intent(app, end!!.id)).setup()
        val cv = check.get().window.decorView
        assertTrue(cv.texts(), cv.texts().contains("系统观察到"))
        cv.click(AnswerKind.DONE.label)
        assertEquals(Mode.IDLE, app.engine.situation.mode)
        assertEquals(Outcome.DONE, app.episodes().last().outcome)

        // 8. 回顾页和设置页都能正常渲染
        val history = Robolectric.buildActivity(HistoryActivity::class.java).setup().get().window.decorView
        assertTrue(history.texts(), history.texts().contains("1 轮行动，1 轮出现偏离"))
        assertTrue(history.texts(), history.texts().contains("从开始偏离到系统重新出现：中位 2分"))
        history.click("展开检查与干预…")
        val expanded = Robolectric.buildActivity(HistoryActivity::class.java).setup().get().window.decorView
        assertTrue(expanded.texts().contains("偏差"))
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get().window.decorView
        assertTrue(settings.texts().contains("✓ 看到前台 App"))
        assertTrue(settings.texts().contains("✗ 送回桌面 / 屏蔽（送回桌面）"))

        // 9. 进程重启：日志重放出完全相同的局面
        val replayed = huilu.core.Situation.replay(app.log.readAll())
        assertEquals(app.engine.situation, replayed)
    }

    @Test
    fun withoutUsageAccessTheAppSaysWhatItCannotSee() {
        shadowOf(app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_IGNORED)
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertTrue(main.get().window.decorView.texts().contains("系统现在只能靠你自报"))
        app.perform(app.engine.start("读书", "", 10, Expectation(EnvMode.OFF_PHONE), strict = true))
        Robolectric.buildService(LoopService::class.java).create()
        advance(5.5)
        val c = app.engine.situation.pending!!
        assertTrue(c.deviation.reality, c.deviation.reality.contains("无法观察"))
        val check = Robolectric.buildActivity(CheckInActivity::class.java, CheckInActivity.intent(app, c.id)).setup()
        assertTrue(check.get().window.decorView.texts().contains("无法观察：只能依靠你的回答"))
    }

    @Test
    fun strictRoundWithoutAccessibilityReportsHomeAsUnavailable() {
        foreground("com.example.course")
        app.perform(app.engine.start("背单词", "", 60, Expectation(EnvMode.IN_APPS, setOf("com.example.course")), strict = true))
        Robolectric.buildService(LoopService::class.java).create()
        repeat(3) {
            background("com.example.course"); foreground("tv.danmaku.bili")
            advance(2.3)
            val c = app.engine.situation.pending!!
            app.perform(app.engine.answer(c.id, AnswerKind.RECOMMIT))
            background("tv.danmaku.bili"); foreground("com.example.course")
            advance(0.5)
        }
        val ivs = app.episodes().last().interventions
        // 没有无障碍服务：引擎知道 HOME 不可用，从不请求它，而是降级并写明理由
        assertTrue(ivs.none { it.intervention.level == huilu.core.Level.HOME })
        assertTrue(ivs.any { it.intervention.reason.contains("不可用") })
    }

    @Test
    fun notificationReplyIsRecordedAndTheCheckInStaysOpen() {
        foreground("com.example.course")
        app.perform(app.engine.start("学网课", "", 10, Expectation(EnvMode.IN_APPS, setOf("com.example.course")), strict = false))
        Robolectric.buildService(LoopService::class.java).create()
        advance(5.3)
        val c = app.engine.situation.pending!!
        val reply = Intent(app, AnswerReceiver::class.java).setAction(AnswerReceiver.TEXT).putExtra(AnswerReceiver.KEY_ID, c.id)
        val results = android.os.Bundle().apply { putCharSequence(AnswerReceiver.KEY_TEXT, "在纸上做题") }
        android.app.RemoteInput.addResultsToIntent(arrayOf(android.app.RemoteInput.Builder(AnswerReceiver.KEY_TEXT).build()), reply, results)
        AnswerReceiver().onReceive(app, reply)
        val still = app.engine.situation.pending!!
        assertEquals(listOf("在纸上做题"), still.notes)
        assertTrue(checkInNotifications().single().extras.getCharSequence("android.text").toString().contains("已记下「在纸上做题」"))
    }

    @Test
    fun interruptIsVerifiedOnlyWhenTheCheckInScreenReallyAppears() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        foreground("com.example.course")
        app.perform(app.engine.start("写报告", "", 60, Expectation(EnvMode.IN_APPS, setOf("com.example.course")), strict = false))
        Robolectric.buildService(LoopService::class.java).create()
        repeat(2) {
            background("com.example.course"); foreground("tv.danmaku.bili")
            advance(2.3)
            if (it == 0) {
                app.perform(app.engine.answer(app.engine.situation.pending!!.id, AnswerKind.RECOMMIT))
                background("tv.danmaku.bili"); foreground("com.example.course")
                advance(0.5)
            }
        }
        val c = app.engine.situation.pending!!
        assertEquals(huilu.core.Level.INTERRUPT, c.level)
        val launched = shadowOf(app).nextStartedActivity
        assertEquals(CheckInActivity::class.java.name, launched.component!!.className)
        // 页面没有真正显示出来（这里没人去 resume 它），2 秒后应如实记为没生效
        advance(0.05)
        val iv = app.episodes().last().interventions.last { it.intervention.level == huilu.core.Level.INTERRUPT }
        assertEquals(huilu.core.ActResult.NO_EFFECT, iv.result)
    }

    @Test
    fun goingHomeThatDoesNotChangeTheForegroundIsReportedAsNoEffect() {
        val guard = Robolectric.setupService(GuardService::class.java)
        GuardService.instance = guard
        foreground("com.example.course")
        app.perform(app.engine.start("背单词", "", 60, Expectation(EnvMode.IN_APPS, setOf("com.example.course")), strict = true))
        Robolectric.buildService(LoopService::class.java).create()
        repeat(3) { i ->
            background("com.example.course"); foreground("tv.danmaku.bili")
            advance(2.3)
            // 第 3 次：模拟的手机不会因为"回桌面"真的切走，B站 继续留在前台几秒
            if (i == 2) advance(0.05)
            app.perform(app.engine.answer(app.engine.situation.pending!!.id, AnswerKind.RECOMMIT))
            background("tv.danmaku.bili"); foreground("com.example.course")
            advance(0.5)
        }
        val homes = app.episodes().last().interventions.filter { it.intervention.level == huilu.core.Level.HOME }
        assertTrue(shadowOf(guard).globalActionsPerformed.contains(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        // 请求了"回桌面"但 B站 仍在前台：如实记为没生效，而不是假定成功
        assertTrue(homes.map { it.result }.toString(), homes.any { it.result == huilu.core.ActResult.NO_EFFECT })
        // 用户回答后自己离开了 B站：结果上目标已离开前台，记为已确认（只看结果，不归因）
        assertTrue(homes.any { it.result == huilu.core.ActResult.VERIFIED })
    }
}
