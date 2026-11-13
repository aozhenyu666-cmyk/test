package io.github.ccch1mneyyy.dsh_app.loop.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B5 语音的降级判定。每种降级各一条。
 *
 * 只测纯函数 `gate`：真的出声要 TextToSpeech、要主线程，本机跑不了；能判错的就是这几行。
 * 总开关不在这里——它关着插件根本不下发 `extra.voice`，手机再存一份就会和插件对不上。
 * 安静时段也不在这里——同一道理在插件侧（`emotion.js` 的 voiceOf），那里有真断言。
 */
class VoiceOutTest {

    private fun gate(inSession: Boolean = false, dnd: Boolean = false, mediaMuted: Boolean = false) =
        VoiceOut.gate(VoiceOut.Gate(inSession = inSession, dnd = dnd, mediaMuted = mediaMuted))

    @Test fun everythingClearMeansSpeak() {
        assertNull("三样都没拦，就该念", gate())
    }

    @Test fun geminiSessionWins() {
        // 连麦舱里出声会真的打断 Gemini 那边，所以它是第一顺位——三样全占时也报它。
        assertEquals(VoiceOut.WHY_SESSION, gate(inSession = true))
        assertEquals(VoiceOut.WHY_SESSION, gate(inSession = true, dnd = true, mediaMuted = true))
    }

    @Test fun doNotDisturbDegradesToText() {
        assertEquals(VoiceOut.WHY_DND, gate(dnd = true))
        // 勿扰压过音量：两个都占时报勿扰，因为改音量也救不回来。
        assertEquals(VoiceOut.WHY_DND, gate(dnd = true, mediaMuted = true))
    }

    @Test fun zeroMediaVolumeDegradesToText() {
        assertEquals(VoiceOut.WHY_MUTED, gate(mediaMuted = true))
    }

    @Test fun reasonsAreDistinctSoTheLedgerCanBeRead() {
        val all = setOf(
            VoiceOut.WHY_SESSION, VoiceOut.WHY_DND, VoiceOut.WHY_MUTED, VoiceOut.WHY_NO_TTS,
        )
        assertEquals("四个原因不能撞，否则账本看不出是哪一道拦的", 4, all.size)
        all.forEach { assertTrue("原因要短到能进账本：$it", it.length <= 20) }
    }
}
