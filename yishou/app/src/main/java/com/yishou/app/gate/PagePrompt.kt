package com.yishou.app.gate

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 页面没到前台时给出可点击的系统无障碍浮层，不依赖额外悬浮窗权限。 */
class PagePrompt(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val delivery = PageDelivery()
    private val wm = service.getSystemService(WindowManager::class.java)
    private var overlay: LinearLayout? = null
    private var poll: Runnable? = null
    private var key: String? = null
    private var onVisible: (() -> Unit)? = null
    val showingOverlay: Boolean get() = overlay != null
    val pending: Boolean get() = delivery.active

    fun open(
        targetKey: String,
        target: Intent,
        title: String,
        visible: () -> Boolean,
        relevant: () -> Boolean,
        onShown: () -> Unit = {},
        onFallback: () -> Unit = {},
    ) {
        if (key == targetKey && delivery.active) return
        dismiss()
        key = targetKey
        current = this
        onVisible = onShown
        delivery.request(SystemClock.elapsedRealtime())
        fun launch() {
            try { service.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { Log.w("PagePrompt", "处理页启动失败", e) }
        }
        launch()
        val check = object : Runnable {
            override fun run() {
                when (delivery.observe(SystemClock.elapsedRealtime(), visible(), relevant())) {
                    PageDelivery.Result.VISIBLE -> { onVisible?.invoke(); dismiss(); return }
                    PageDelivery.Result.CANCELLED -> { dismiss(); return }
                    PageDelivery.Result.FALLBACK -> {
                        onFallback()
                        showOverlay(title, ::launch)
                        showNotification(title, target)
                    }
                    PageDelivery.Result.WAITING -> Unit
                }
                main.postDelayed(this, 300)
            }
        }
        poll = check
        main.postDelayed(check, 300)
    }

    private fun showOverlay(title: String, launch: () -> Unit) {
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFFF4F1EA.toInt())
            addView(TextView(service).apply {
                text = title + "\n页面没有自动打开，请点下面继续处理。"
                textSize = 20f
                setTextColor(0xFF2A241F.toInt())
            })
            fun button(label: String, action: () -> Unit) {
                addView(Button(service).apply {
                    text = label
                    setOnClickListener { action() }
                })
            }
            button("打开处理页", launch)
            button("去无障碍设置") {
                dismiss()
                runCatching { service.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            button("回桌面") {
                dismiss()
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
        }
        try {
            wm.addView(panel, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP })
            overlay = panel
        } catch (e: Exception) {
            Log.w("PagePrompt", "无障碍浮层未能显示，保留通知入口", e)
        }
    }

    private fun showNotification(title: String, target: Intent) {
        try {
            val nm = service.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "处理页提醒", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(service, NOTIFICATION, target,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(NOTIFICATION, Notification.Builder(service, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText("点此打开处理页；自动跳转尚未确认成功")
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (e: Exception) {
            Log.w("PagePrompt", "处理页通知未能显示", e)
        }
    }

    fun dismiss() {
        if (current === this) current = null
        onVisible = null
        poll?.let(main::removeCallbacks)
        poll = null
        delivery.cancel()
        key = null
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        runCatching { service.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION) }
    }

    companion object {
        private var current: PagePrompt? = null
        /** 只由目标 Activity 的 onResume 调用，系统接受启动请求本身不算确认。 */
        fun acknowledge() {
            val prompt = current ?: return
            if (!prompt.delivery.active) return
            prompt.onVisible?.invoke()
            prompt.dismiss()
        }
        const val CHANNEL = "page_delivery"
        const val NOTIFICATION = 9471
    }
}

