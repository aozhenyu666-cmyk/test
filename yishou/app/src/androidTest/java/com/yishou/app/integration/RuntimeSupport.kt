package com.yishou.app.integration

import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.yishou.app.MainActivity
import com.yishou.app.YishouApp
import com.yishou.app.card.Card
import com.yishou.app.card.Skill
import com.yishou.app.data.CardRow
import com.yishou.app.data.CardSource
import com.yishou.app.data.LibraryCategory
import com.yishou.app.library.Classification
import com.yishou.app.llm.LlmConfig
import com.yishou.app.settings.AppPrefs
import com.yishou.app.speech.SpeechEndpoint
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

object RuntimeSupport {
    val app get() = ApplicationProvider.getApplicationContext<YishouApp>()
    val device get(): UiDevice {
        androidx.test.uiautomator.Configurator.getInstance().uiAutomationFlags = android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }
    fun find(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2 = device.findObject(selector) ?: throw AssertionError("界面缺少：$selector")
    const val TARGET = "com.yishou.gatefixture"
    fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    suspend fun eventually(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        try { withTimeout(timeoutMs) { while (!condition()) delay(50) } }
        catch (e: TimeoutCancellationException) { RuntimeEvidence.capture("timeout-${System.nanoTime()}"); throw e }
    }
    suspend fun reset() {
        device.executeShellCommand("settings put secure enabled_accessibility_services ''")
        device.executeShellCommand("settings put secure accessibility_enabled 0")
        app.settings.saveLlm(LlmConfig("", "", "", false))
        app.settings.saveVision(LlmConfig("", "", "", false))
        app.settings.saveTranscriber(SpeechEndpoint())
        app.settings.saveSynthesizer(SpeechEndpoint())
        main {
            androidx.test.runner.lifecycle.Stage.values().filter { it != androidx.test.runner.lifecycle.Stage.DESTROYED }.forEach { stage ->
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).toList().forEach { it.finish() }
            }
            listOf(com.yishou.app.window.WindowService::class.java, com.yishou.app.look.ScreenLookService::class.java,
                com.yishou.app.scroll.ScrollService::class.java, com.yishou.app.partner.PartnerVoiceService::class.java).forEach { app.stopService(Intent(app, it)) }
            app.partnerVoice.stop(); app.windowOwner.viewModelStore.clear()
        }
        app.settings.updateApp { AppPrefs(watched = emptyMap(), morningEnabled = false, ttsEnabled = false, voiceMode = false, nudgeEnabled = false, summaryEnabled = false, idleHours = 0, windowStrict = false) }
        delay(200)
        app.database.clearAllTables()
        Classification.defaults.forEach { app.database.everyday().insertCategory(LibraryCategory(it.id, it.name)) }
        if (Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        device.pressHome()
    }
    fun activity(route: String? = null): ActivityScenario<MainActivity> {
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (route != null) intent.putExtra(MainActivity.EXTRA_ROUTE, route)
        return ActivityScenario.launch(intent)
    }
    fun fixture() { app.startActivity(Intent().setClassName(TARGET, "$TARGET.ReadingActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun config(url: String) = LlmConfig(url, "local-test-key", "test-model", false)
    fun completion(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", text)))).toString())
    fun streamed(text: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text)))) + "\n\ndata: [DONE]\n\n")
    val card = Card(Skill.MAIN, 1, listOf("站点开放时间是上午八点，进入前需要实名核验。", "周末仍然保持相同的核验规则。"),
        "参观者必须先完成哪项操作才能进入站点？", listOf("实名核验", "购买商品", "预约餐饮", "参加抽奖"), 0, setOf(1), emptyMap(),
        "身份核验是进入的前提，开放时间不能代替核验。", points = listOf("给出先核验身份再进入的顺序", "说明时间不能代替身份核验"))
    suspend fun seedCard(): Long = app.database.cards().insertCard(CardRow(skill = card.skill.name, difficulty = 1, json = card.toJson(), verified = true, source = CardSource.BANK, createdAt = System.currentTimeMillis()))
    val cardText = """
【材料】站点开放时间是上午八点，进入前需要实名核验。周末仍然保持相同的核验规则。
【题目】参观者必须先完成哪项操作才能进入站点？
A. 实名核验
B. 购买商品
C. 预约餐饮
D. 参加抽奖
【答案】A
【依据】第1句
【解析】身份核验是进入的前提，开放时间不能代替核验。
【要点】
1. 给出先核验身份再进入的顺序
2. 说明时间不能代替身份核验
""".trimIndent()
}

class RuntimeEvidence : TestWatcher() {
    override fun failed(e: Throwable?, d: Description) { capture(d.methodName + "-failed") }
    companion object {
        fun capture(name: String) {
            val folder = File(RuntimeSupport.app.getExternalFilesDir(null), "integration-evidence").apply { mkdirs() }
            RuntimeSupport.device.takeScreenshot(File(folder, "$name.png"))
            RuntimeSupport.device.dumpWindowHierarchy(File(folder, "$name.xml"))
        }
    }
}
