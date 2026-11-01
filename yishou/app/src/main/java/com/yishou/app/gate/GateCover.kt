package com.yishou.app.gate

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 兜底遮罩：有些手机（小米、OPPO、vivo、华为等）不给“后台弹出界面”权限时，
 * 一手调用弹出入口页，系统不报错、只是悄悄不显示，娱乐应用照样能刷。
 * 这时由无障碍服务自己盖一层全屏遮罩挡住它。这种遮罩是无障碍服务专用的窗口，
 * 不需要额外权限，系统的后台限制也拦不住。
 *
 * 遮罩只有三件事：说清楚为什么挡、去做题、回桌面。回到桌面或别的应用就收起，不常驻。
 */
class GateCover(private val service: AccessibilityService) {
    private var view: View? = null
    var pkg: String? = null; private set
    private var status: TextView? = null

    val showing: Boolean get() = view != null

    fun show(pkg: String, label: String, onOpenGate: () -> Unit) {
        if (view != null && this.pkg == pkg) return
        hide()
        this.pkg = pkg
        val c = service
        val dp = c.resources.displayMetrics.density
        fun pad(v: Int) = (v * dp).toInt()
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F2161A1F"))
            setPadding(pad(28), pad(28), pad(28), pad(28))
            isClickable = true
        }
        fun text(s: String, size: Float, color: Int = Color.WHITE) = TextView(c).apply {
            text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            setPadding(0, pad(6), 0, pad(6))
        }
        root.addView(text("打开「$label」之前，先做一道题", 22f))
        val st = text("一手被系统拦着弹不出来，先用这层遮罩挡住。点“去做题”。", 15f, Color.parseColor("#CCD3DA"))
        status = st
        root.addView(st)
        fun button(s: String, onClick: () -> Unit) = Button(c).apply {
            text = s; textSize = 16f; isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad(10) }
        }
        root.addView(button("去做题") { onOpenGate() })
        root.addView(button("去开“后台弹出界面”权限") {
            GateTrace.log("遮罩：点了去开权限", pkg)
            hide()
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            runCatching {
                service.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + service.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
        root.addView(button("回桌面") {
            GateTrace.log("遮罩：点了回桌面", pkg)
            hide()
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        try {
            (c.getSystemService(WindowManager::class.java)).addView(root, lp)
            view = root
            GateTrace.log("遮罩已盖上", pkg)
        } catch (e: Exception) {
            this.pkg = null
            GateTrace.log("遮罩也没盖上：${e.javaClass.simpleName} ${e.message.orEmpty()}", pkg)
        }
    }

    fun say(text: String) { status?.text = text }

    fun hide() {
        val v = view ?: return
        view = null
        status = null
        runCatching { service.getSystemService(WindowManager::class.java).removeView(v) }
        GateTrace.log("遮罩收起", pkg)
        pkg = null
    }
}
