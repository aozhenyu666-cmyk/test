package io.github.ccch1mneyyy.dsh_app.loop.proactive

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent

/**
 * M3.1 MediaSession / AudioManager bridge for companion-controlled pause/resume.
 *
 * Uses MediaSessionManager active sessions when notification listener permission is granted,
 * and gracefully falls back to AudioManager.isMusicActive + dispatchMediaKeyEvent without
 * crashing or requiring extra privileges.
 */
object PhoneMediaControl {
    fun keyCodeForAction(action: String?): Int? = when (action?.trim()?.lowercase()) {
        "pause", "stop" -> KeyEvent.KEYCODE_MEDIA_PAUSE
        "resume", "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
        else -> null
    }

    fun isPlaying(context: Context): Boolean {
        val msmPlaying = runCatching {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            msm?.getActiveSessions(null)?.any { it.playbackState?.state == PlaybackState.STATE_PLAYING } == true
        }.getOrDefault(false)
        if (msmPlaying) return true
        return runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isMusicActive == true
        }.getOrDefault(false)
    }

    fun control(context: Context, action: String?): Boolean {
        val normalized = action?.trim()?.lowercase() ?: return false
        val code = keyCodeForAction(normalized) ?: return false
        val handledBySession = runCatching {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return@runCatching false
            val controllers = msm.getActiveSessions(null)
            var hit = false
            for (c in controllers) {
                if (normalized == "pause" || normalized == "stop") {
                    c.transportControls.pause()
                    hit = true
                } else {
                    c.transportControls.play()
                    hit = true
                }
            }
            hit
        }.getOrDefault(false)
        if (handledBySession) return true
        return runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@runCatching false
            val downTime = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(downTime, downTime + 15L, KeyEvent.ACTION_UP, code, 0))
            true
        }.getOrDefault(false)
    }
}
