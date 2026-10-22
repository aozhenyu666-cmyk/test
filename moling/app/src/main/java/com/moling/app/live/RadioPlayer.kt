package com.moling.app.live

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File

/**
 * 电台的播放底座（第十五批 B6，Media3）。
 *
 * 交给 ExoPlayer 的事：抢 / 让音频焦点（来电、导航、别的应用出声时暂停，回来接着放）、拔耳机自动暂停、
 * 暂停后从原位置接着播。通知栏和锁屏的控制由 [RadioService] 的 MediaSession 提供。
 * 播什么、播到第几段、停了以后迟到的回调认不认，仍然由 core 的 RadioEngine 说了算（[RadioHost] 做桥）。
 * 只在主线程用。
 */
object RadioPlayer {
    private var exo: ExoPlayer? = null

    /** 正在播 / 暂停在播放器里的那一段（session:segment:generation）；空 = 播放器里没有电台的东西。 */
    var currentKey: String = ""
        private set

    fun player(context: Context): ExoPlayer = exo ?: ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .also { exo = it }

    fun play(context: Context, file: File, key: String, title: String) {
        val p = player(context)
        currentKey = key
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId(key)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("墨灵电台").build())
            .build()
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
    }

    fun pause() { exo?.pause() }

    /** 播放器里还是这一段、只是停在中间：接着放，返回 true；否则 false（调用方重新合成这一段）。 */
    fun resumeIfLoaded(key: String): Boolean {
        val p = exo ?: return false
        if (key.isEmpty() || key != currentKey || p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE) return false
        p.play()
        return true
    }

    fun stop() {
        currentKey = ""
        exo?.let { it.stop(); it.clearMediaItems() }
    }

    fun isPlaying(key: String): Boolean = exo?.let { key.isNotEmpty() && key == currentKey && it.isPlaying } ?: false

    fun addListener(context: Context, l: Player.Listener) = player(context).addListener(l)
}

/**
 * 通知栏 / 锁屏 / 耳机按键的控制入口。只控制电台：播放、暂停、下一段（= 跳过）、停止。
 * 跳过和停止转给 [RadioHost]，让状态机知道；播放 / 暂停直接作用在播放器上，再由 [RadioHost] 的监听同步回状态机。
 */
class RadioService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val base = RadioPlayer.player(this)
        val forwarding = object : ForwardingPlayer(base) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_STOP)
                .build()

            override fun isCommandAvailable(command: Int): Boolean =
                command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                    command == Player.COMMAND_STOP || super.isCommandAvailable(command)

            override fun seekToNext() = RadioHost.skipCurrent()
            override fun seekToNextMediaItem() = RadioHost.skipCurrent()
            override fun stop() = RadioHost.stopCurrent("通知栏点了停止")
        }
        session = MediaSession.Builder(this, forwarding).setId("moling-radio").build().also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** 划掉 App：没在播就停掉服务（不在后台空挂）。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            RadioHost.stopCurrent("App 被划掉")
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.let { removeSession(it); it.release() }
        session = null
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, RadioService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RadioService::class.java)) }
        }
    }
}
