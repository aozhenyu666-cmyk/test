package com.dshdriver.core

import com.dshdriver.core.DriverReflex
import com.dshdriver.core.DriverReflex.Verdict
import com.dshdriver.core.LiveInference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriverReflexTest {
    private val anchors = setOf("com.google.android.apps.bard")
    private fun j(pkg: String?, locked: Boolean = true) = DriverReflex.judge(pkg, locked, anchors, "io.github.ccch1mneyyy.dsh_app", setOf("com.sohu.inputmethod.sogou"))

    @Test fun lockedEscapeToBiliOrHome() {
        assertEquals(Verdict.Escape("tv.danmaku.bili"), j("tv.danmaku.bili"))
        assertEquals(Verdict.Escape("com.miui.home"), j("com.miui.home"), "回桌面就是切走")
        assertEquals(Verdict.Escape("com.android.settings"), j("com.android.settings"), "设置不放行")
    }
    @Test fun allowedApps() {
        assertEquals(Verdict.Allow("com.google.android.apps.bard", true), j("com.google.android.apps.bard"))
        assertEquals(Verdict.Allow("io.github.ccch1mneyyy.dsh_app", false), j("io.github.ccch1mneyyy.dsh_app"))
        assertEquals(Verdict.Allow("com.sohu.inputmethod.sogou", false), j("com.sohu.inputmethod.sogou"))
        assertEquals(Verdict.Allow("com.android.incallui", false), j("com.android.incallui"), "来电永远放行")
    }
    @Test fun notLockedOrUnreadable() {
        assertEquals(Verdict.Ignore, j("tv.danmaku.bili", locked = false))
        assertEquals(Verdict.Ignore, j(null))
        assertEquals(Verdict.Ignore, j(" "))
    }
    @Test fun liveInference() {
        val l = LiveInference(liveGoneMs = 8_000)
        assertEquals(listOf<LiveInference.Change>(LiveInference.Change.Live(true)), l.onAudio(true, false, 0))
        assertEquals(listOf<LiveInference.Change>(LiveInference.Change.AssistantStartedSpeaking), l.onAudio(true, true, 1_000))
        assertTrue(l.onAudio(true, true, 1_500).isEmpty(), "持续在说不重复报")
        assertTrue(l.onAudio(false, false, 2_000).isEmpty(), "录音短暂消失不算退出")
        assertTrue(l.poll(9_000).isEmpty())
        assertEquals(listOf<LiveInference.Change>(LiveInference.Change.Live(false)), l.poll(10_001))
        l.selfSpeaking = true
        assertEquals(listOf<LiveInference.Change>(LiveInference.Change.Live(true)), l.onAudio(true, true, 11_000), "自己的 TTS 不算 Gemini 在说")
    }

    @Test fun nonActivityWindowsDoNotCountAsEscape() {
        val j = { cls: String? -> DriverReflex.judge("tv.danmaku.bili", true, anchors, "io.github.ccch1mneyyy.dsh_app", emptySet(), cls) }
        assertEquals(Verdict.Ignore, j("android.widget.Toast\$TN"), "Toast 不算切走")
        assertEquals(Verdict.Ignore, j("android.app.Dialog"), "系统弹窗不算切走")
        assertEquals(Verdict.Ignore, j("com.sohu.inputmethod.sogou.SogouIME"), "输入法不算切走")
        assertEquals(Verdict.Ignore, j("androidx.appcompat.app.AlertDialog"))
        assertEquals(Verdict.Escape("tv.danmaku.bili"), j("tv.danmaku.bili.MainActivityV2"), "真的 Activity 照样拦")
        assertEquals(Verdict.Escape("tv.danmaku.bili"), j(null), "不给类名时只看包名")
    }
    @Test fun stoppedSpeakingEdgeNeedsMinimumSpeech() {
        val l = LiveInference(liveGoneMs = 8_000, minSpeechMs = 1_500)
        l.onAudio(true, false, 0)
        assertEquals(listOf<LiveInference.Change>(LiveInference.Change.AssistantStartedSpeaking), l.onAudio(true, true, 1_000))
        assertTrue(l.onAudio(true, false, 1_800).isEmpty(), "只说了 0.8 秒：不算说完一段")
        l.onAudio(true, true, 3_000)
        assertEquals(listOf<LiveInference.Change>(LiveInference.Change.AssistantStoppedSpeaking), l.onAudio(true, false, 6_000))
        assertTrue(l.onAudio(true, false, 7_000).isEmpty(), "已经停了不重复报")
    }
}
