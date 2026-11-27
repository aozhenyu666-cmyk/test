package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSignalMonitorTest {
    @Test
    fun offlineWhenNoHardwareOrUiSignalsActive() {
        val snap = LiveSignalMonitor.evaluateSignals(
            audioInComm = false,
            micRecording = false,
            virtualDisplayActive = false,
            screenShareText = false,
            uiLiveActive = false,
        )
        assertFalse(snap.liveOnline)
        assertTrue(snap.summary.contains("未检测"))
    }

    @Test
    fun ignoresStrayBackgroundMicWithoutAudioInCommOrDisplayOrUi() {
        val snap = LiveSignalMonitor.evaluateSignals(
            audioInComm = false,
            micRecording = true,
            virtualDisplayActive = false,
            screenShareText = false,
            uiLiveActive = false,
        )
        assertFalse(snap.liveOnline)
    }

    @Test
    fun detectsLiveInBackgroundWhileWatchingFullscreenVideoViaVirtualDisplayAndMic() {
        val snap = LiveSignalMonitor.evaluateSignals(
            audioInComm = true,
            micRecording = true,
            virtualDisplayActive = true,
            screenShareText = false,
            uiLiveActive = false,
        )
        assertTrue(snap.liveOnline)
        assertTrue(snap.summary.contains("虚拟共享副屏在线"))
        assertTrue(snap.summary.contains("麦克风采集激活"))
    }

    @Test
    fun detectsLiveWhenAudioInCommAndVoicePlaybackEvenIfMicHiddenOnRom() {
        val snap = LiveSignalMonitor.evaluateSignals(
            audioInComm = true,
            micRecording = false,
            virtualDisplayActive = false,
            screenShareText = false,
            uiLiveActive = false,
            voicePlayback = true,
        )
        assertTrue(snap.liveOnline)
        assertTrue(snap.summary.contains("VoIP/助手语音流在线"))
    }

    @Test
    fun ignoresVoicePlaybackAloneWithoutAudioInComm() {
        val snap = LiveSignalMonitor.evaluateSignals(
            audioInComm = false,
            micRecording = false,
            virtualDisplayActive = false,
            screenShareText = false,
            uiLiveActive = false,
            voicePlayback = true,
        )
        assertFalse(snap.liveOnline)
    }
}
