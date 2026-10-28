package cn.companion.pet.service

import kotlinx.coroutines.*
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings as AndroidSettings
import cn.companion.pet.MainActivity
import cn.companion.pet.PetApp
import cn.companion.pet.R

/**
 * 常驻前台服务：串起悬浮角色、感知、探针和交互。
 *
 * 类型用 specialUse（常驻桌宠没有更贴切的标准类型）。有麦克风权限且从前台启动时，
 * 再加上 microphone 类型，后台按住说话才能录音；从开机或后台拉起时系统不允许加，
 * 这时语音可能失败，界面会引导改用打字。
 */
class PetService : Service() {

    private var brain: PetBrain? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> brain?.onScreenOff()
                Intent.ACTION_USER_PRESENT -> brain?.onUserPresent()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        goForeground()
        if (foregroundFailed) {
            running = false
            stopSelf()
            return
        }
        if (!AndroidSettings.canDrawOverlays(this)) {
            // 没有悬浮窗权限就没法显示角色：不硬撑，停掉，等用户在 App 里授权
            stopSelf()
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        brain = PetBrain(this).also { it.start() }
        serviceScope.launch { (application as PetApp).settings.changes.collect { goForeground() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                (application as PetApp).settings.userStopped = true
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_RIDING -> {
                val s = (application as PetApp).settings
                s.partnerRiding = !s.partnerRiding
                goForeground()
            }
            ACTION_QUIET -> {
                val app = application as PetApp
                app.settings.partnerQuietUntil = System.currentTimeMillis() + 30 * 60_000L
                app.stopSpeech()
                goForeground()
            }
            ACTION_TOGGLE_PAUSE -> {
                val s = (application as PetApp).settings
                s.petPaused = !s.petPaused
                goForeground()
            }
        }
        goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        serviceScope.cancel()
        brain?.stop()
        brain = null
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        val paused = (application as PetApp).settings.petPaused
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, PetService::class.java).setAction(ACTION_TOGGLE_PAUSE), PendingIntent.FLAG_IMMUTABLE)
        val ride = PendingIntent.getService(this, 3, Intent(this, PetService::class.java).setAction(ACTION_TOGGLE_RIDING), PendingIntent.FLAG_IMMUTABLE)
        val quiet = PendingIntent.getService(this, 4, Intent(this, PetService::class.java).setAction(ACTION_QUIET), PendingIntent.FLAG_IMMUTABLE)
        val riding = (application as PetApp).settings.partnerRiding
        val stop = PendingIntent.getService(this, 2, Intent(this, PetService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (riding) "同桌语音陪伴中" else if (paused) "桌宠在安静待着" else "桌宠在你身边")
            .setContentText(if (paused) "不会主动开口" else "点这里打开设置")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (riding) "停止语音" else "语音陪伴", ride).build())
            .addAction(Notification.Action.Builder(null, "安静 30 分钟", quiet).build())
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()

        if (Build.VERSION.SDK_INT < 34) {
            startForeground(ID, notification)
            return
        }
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        try {
            if (micOk) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } catch (e: Exception) {
            // 后台启动时系统不允许 microphone 类型：退回只用 specialUse。
            // 这一步也被拒（例如 Android 15 起后台无可见悬浮窗时）就安静地停下，不让整个进程崩掉，
            // 否则同进程的无障碍服务会一起被系统标成“出现故障”。
            try {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } catch (e2: Exception) {
                foregroundFailed = true
            }
        }
    }

    /** 前台通知没能挂上：本次不启动桌宠，等用户打开 App 时再拉起。 */
    private var foregroundFailed = false

    companion object {
        const val ACTION_TOGGLE_RIDING = "cn.companion.pet.TOGGLE_RIDING"
        const val ACTION_QUIET = "cn.companion.pet.QUIET"
        const val ACTION_STOP = "cn.companion.pet.STOP"
        const val ACTION_TOGGLE_PAUSE = "cn.companion.pet.TOGGLE_PAUSE"
        private const val CHANNEL = "pet"
        private const val ID = 1

        @Volatile
        var running = false
            private set

        /** 尝试启动。用户主动停止过、或没有悬浮窗权限时不启动。 */
        fun startIfAllowed(context: Context, userAction: Boolean = false): Boolean {
            val settings = (context.applicationContext as PetApp).settings
            if (userAction) settings.userStopped = false
            if (settings.userStopped || !AndroidSettings.canDrawOverlays(context)) return false
            return runCatching { context.startForegroundService(Intent(context, PetService::class.java)) }.isSuccess
        }
    }
}
