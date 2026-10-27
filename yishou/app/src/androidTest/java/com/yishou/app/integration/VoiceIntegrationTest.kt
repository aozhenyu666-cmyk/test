package com.yishou.app.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.yishou.app.partner.PartnerPlan
import com.yishou.app.partner.Material
import com.yishou.app.partner.Gear
import com.yishou.app.partner.PartnerVoiceService
import com.yishou.app.speech.SpeechEndpoint
import com.yishou.app.speech.ListenPhase
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import com.yishou.app.integration.RuntimeSupport.app
import com.yishou.app.integration.RuntimeSupport.main
import com.yishou.app.integration.RuntimeSupport.reset
import com.yishou.app.integration.RuntimeSupport.eventually
import com.yishou.app.integration.RuntimeSupport.device
import com.yishou.app.integration.RuntimeSupport.find

@RunWith(AndroidJUnit4::class)
class VoiceIntegrationTest {
    @get:Rule val evidence = RuntimeEvidence()
    @Before fun before() = runBlocking {
        reset()
        device.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
        device.executeShellCommand("pm grant ${app.packageName} android.permission.RECORD_AUDIO")
        Unit
    }
    @After fun after() {
        main { app.partnerVoice.stop() }
        app.settings.saveSynthesizer(SpeechEndpoint())
        device.pressHome()
    }
    @Test fun voiceServiceShowsControlsAcceptsTypedReplyPlaysApiAudioAndClosesItsMicrophone() = runBlocking {
        val wav = InstrumentationRegistry.getInstrumentation().context.assets.open("test-tone.wav").use { it.readBytes() }
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    return if (request.path?.contains("/audio/speech") == true)
                        MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav))
                    else RuntimeSupport.completion("收到，先指出一个可以核对的条件。")
                }
            }
            start()
        }
        val activity = RuntimeSupport.activity()
        try {
            app.settings.saveLlm(RuntimeSupport.config(server.url("/v1").toString()))
            app.settings.saveSynthesizer(SpeechEndpoint(server.url("/v1").toString(), "local-tts-key", "tts-test", voice = "test", format = "wav"))
            app.settings.updateApp { it.copy(ttsEnabled = true) }
            val error = withContext(Dispatchers.Main) { app.partnerVoice.start(PartnerPlan(Material.ARTICLE, "先检查材料中的条件", 5, Gear.ACCOMPANY)) }
            Assert.assertNull(error)
            main { Assert.assertTrue(PartnerVoiceService.start(app)) }
            eventually { app.partnerVoice.running.value != null }
            eventually { device.hasObject(By.text("一手 ●")) || device.hasObject(By.text("打字回一句")) }
            device.findObject(By.text("一手 ●"))?.click()
            eventually { device.hasObject(By.text("打字回一句")) }
            find(By.text("我来说 / 打断")).click()
            RuntimeSupport.fixture()
            find(By.text("打字回一句")).click()
            eventually { device.hasObject(By.text("写给伙伴")) }
            val typed = "我已经找到需要核对的先决条件。"
            find(By.pkg(app.packageName).clazz("android.widget.EditText")).text = typed
            find(By.text("交这一句")).click()
            eventually { app.partnerVoice.running.value?.live?.lines?.any { it.who == "我" && it.text == typed } == true }
            eventually { requests.any { it.path?.contains("/audio/speech") == true } }
            val speech = requests.first { it.path?.contains("/audio/speech") == true }
            Assert.assertEquals("Bearer local-tts-key", speech.getHeader("Authorization"))
            Assert.assertEquals("wav", JSONObject(speech.body.clone().readUtf8()).getString("response_format"))
            RuntimeEvidence.capture("voice-service-floating-controls")
            find(By.text("关闭会话")).click()
            eventually(5_000) { app.partnerVoice.running.value == null && app.voice.phase.value == ListenPhase.IDLE }
            eventually { !device.hasObject(By.text("关闭会话")) }
        } finally { main { app.partnerVoice.stop() }; activity.close(); server.shutdown() }
    }
}
