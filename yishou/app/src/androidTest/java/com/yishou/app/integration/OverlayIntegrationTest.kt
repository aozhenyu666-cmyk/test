package com.yishou.app.integration

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.Settings
import android.util.Base64
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.*
import com.yishou.app.window.WindowActivity
import com.yishou.app.window.WindowViewModel
import com.yishou.app.look.ScreenLookService
import com.yishou.app.floating.CoachFloat
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import com.yishou.app.integration.RuntimeSupport.app
import com.yishou.app.integration.RuntimeSupport.main
import com.yishou.app.integration.RuntimeSupport.reset
import com.yishou.app.integration.RuntimeSupport.eventually
import com.yishou.app.integration.RuntimeSupport.device
import com.yishou.app.integration.RuntimeSupport.find

@RunWith(AndroidJUnit4::class)
class OverlayIntegrationTest {
    @get:Rule val evidence = RuntimeEvidence()
    @Before fun before() = runBlocking {
        reset()
        device.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        Assert.assertTrue(Settings.canDrawOverlays(app))
        RuntimeSupport.seedCard()
        app.settings.updateApp { it.copy(manualWindowStart = System.currentTimeMillis(), windowStoppedAt = 0, warmup = false, predictFirst = false) }
        Unit
    }
    @After fun after() {
        main {
            app.stopService(Intent(app, com.yishou.app.window.WindowService::class.java))
            app.stopService(Intent(app, ScreenLookService::class.java))
            app.windowOwner.viewModelStore.clear()
        }
        device.pressHome()
    }
    private fun vm(): WindowViewModel = ViewModelProvider(app.windowOwner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[WindowViewModel::class.java]
    private suspend fun openPanel() {
        eventually { device.hasObject(By.text("一手 ●")) || device.hasObject(By.text("回一句 / 说话")) }
        device.findObject(By.text("一手 ●"))?.click()
        Assert.assertTrue(device.wait(Until.hasObject(By.text("回一句 / 说话")), 5_000))
    }
    @Test fun floatingReplyWorksOverTheOriginalAppAndCanBeDraggedAndStopped() = runBlocking {
        val activity = ActivityScenario.launch<WindowActivity>(Intent(app, WindowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            lateinit var model: WindowViewModel
            main { model = vm() }
            eventually { model.card.value.round != null }
            RuntimeSupport.fixture()
            openPanel()
            find(By.text("回一句 / 说话")).click()
            Assert.assertTrue(device.wait(Until.hasObject(By.text("回这一句")), 5_000))
            Assert.assertTrue(device.executeShellCommand("dumpsys activity activities").contains("com.yishou.gatefixture/.ReadingActivity"))
            // 对话框只把底下的页面变暗；非活动窗口不会被无障碍树返回。
            val field = find(By.pkg(app.packageName).clazz("android.widget.EditText"))
            Assert.assertNotNull(field)
            field.text = "A，把握高"
            RuntimeEvidence.capture("floating-reply-over-original-page")
            find(By.text("交这一句")).click()
            eventually { model.card.value.round?.bet == 0 }
            openPanel()
            val header = find(By.text("陪练窗口  ▾"))
            Assert.assertNotNull(header)
            header.click()
            val bubble = device.wait(Until.findObject(By.text("一手 ●")), 3_000)!!
            val before = bubble.visibleBounds
            bubble.drag(android.graphics.Point(device.displayWidth - 80, device.displayHeight - 120), 1000)
            RuntimeEvidence.capture("floating-panel-after-edge-drag")
            val after = device.wait(Until.findObject(By.text("一手 ●")), 5_000)?.visibleBounds
                ?: throw AssertionError("拖动后小窗消失")
            Assert.assertTrue(before.left != after.left || before.top != after.top)
            find(By.text("一手 ●")).click()
            find(By.text("停止陪练")).click()
            eventually { !device.hasObject(By.text("停止陪练")) && !device.hasObject(By.text("一手 ●")) }
        } finally { activity.close() }
    }
    @Test fun screenCaptureExcludesTheFloatingPanelAndStopsWhenPracticeEnds() = runBlocking {
        val text = MockWebServer().apply { start() }
        val vision = MockWebServer().apply { start() }
        val activity = ActivityScenario.launch<WindowActivity>(Intent(app, WindowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            app.settings.saveLlm(RuntimeSupport.config(text.url("/v1").toString()))
            app.settings.saveVision(RuntimeSupport.config(vision.url("/v1").toString()))
            lateinit var model: WindowViewModel
            main { model = vm() }
            eventually { model.card.value.round != null }
            device.findObject(By.text("陪练窗口  ▾"))?.click()
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("规则与看屏 ▾")
            find(By.text("规则与看屏 ▾")).click()
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("开启看屏")
            find(By.text("开启看屏")).click()
            val allow = device.wait(Until.findObject(By.res("android", "button1")), 5_000)
                ?: device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("Start now|Start|立即开始|开始录制"))), 5_000)
            Assert.assertNotNull("系统看屏授权弹窗没有出现", allow)
            // 新版系统允许选单个应用：明确选整个测试屏幕。
            device.findObject(By.text("A single app"))?.let { it.click(); device.wait(Until.findObject(By.text("Entire screen")), 2_000)?.click() }
            (device.findObject(By.res("android", "button1")) ?: allow!!).click()
            eventually { ScreenLookService.running.value }
            RuntimeSupport.fixture()
            openPanel()
            val header = find(By.text("陪练 · 可以看屏  ▾"))
            val bounds = header.visibleBounds
            vision.enqueue(RuntimeSupport.completion(RuntimeSupport.cardText))
            text.enqueue(RuntimeSupport.completion(RuntimeSupport.cardText))
            find(By.text("看一眼")).click()
            val request = withContext(Dispatchers.IO) { vision.takeRequest(15, TimeUnit.SECONDS) }
            Assert.assertNotNull("没有调用图片接口", request)
            val messages = JSONObject(request!!.body.readUtf8()).getJSONArray("messages")
            val image = messages.getJSONObject(1).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url")
            val bytes = Base64.decode(image.substringAfter(','), Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            Assert.assertEquals(720, bitmap.width)
            val x = ((bounds.left + 4) * bitmap.width.toFloat() / device.displayWidth).toInt().coerceIn(0, bitmap.width - 1)
            val y = (bounds.centerY() * bitmap.height.toFloat() / device.displayHeight).toInt().coerceIn(0, bitmap.height - 1)
            val pixel = bitmap.getPixel(x, y)
            Assert.assertTrue("截屏中仍含深色浮窗", Color.red(pixel) > 150 && Color.green(pixel) > 150 && Color.blue(pixel) > 150)
            bitmap.recycle()
            eventually { model.card.value.dealt?.row?.source == com.yishou.app.data.CardSource.PHOTO }
            RuntimeEvidence.capture("screen-capture-returned-to-practice")
            main { model.stopEarly() }
            eventually(3_000) { !ScreenLookService.running.value }
        } finally { activity.close(); text.shutdown(); vision.shutdown() }
    }
}
