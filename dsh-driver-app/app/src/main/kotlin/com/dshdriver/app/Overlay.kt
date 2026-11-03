package com.dshdriver.app

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.dshdriver.core.Card

/**
 * level 0 的小卡：浮在 Gemini Live 顶部，不抢焦点，Live 照常听你说话。
 * 只放 A/B 和"展开"；要打字就展开成全屏卡。卡真的贴到窗口上才回报 displayed。
 * 只在主线程调用。
 */
object Overlay {
    private var view: View? = null
    private var cardId: String? = null
    private val main = Handler(Looper.getMainLooper())

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context, card: Card) {
        val wm = context.getSystemService(WindowManager::class.java)
        if (wm == null) { Engine.surface(card.id, "not_shown", "overlay"); return }
        hide()
        val dp = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            setBackgroundColor(0xEE1B2230.toInt())
        }
        root.addView(TextView(context).apply { text = card.text; setTextColor(0xFFFFFFFF.toInt()); textSize = 16f })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        card.options?.let { o ->
            row.addView(button(context, "A ${o.a}") { Engine.reply(card.id, null, "A", "overlay"); hide() })
            row.addView(button(context, "B ${o.b}") { Engine.reply(card.id, null, "B", "overlay"); hide() })
        }
        row.addView(button(context, "展开") { hide(); CardActivity.start(context, card, 0, reflex = false) })
        row.addView(button(context, "收起") { hide() })
        root.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                main.post { if (v.isShown) Engine.surface(card.id, "displayed", "overlay") }
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        if (runCatching { wm.addView(root, lp) }.isFailure) {
            Engine.surface(card.id, "not_shown", "overlay")
            return
        }
        view = root
        cardId = card.id
    }

    fun hide() {
        val v = view ?: return
        runCatching { v.context.getSystemService(WindowManager::class.java)?.removeView(v) }
        view = null
        cardId = null
    }

    fun hideIf(id: String) { if (cardId == id) hide() }

    private fun button(context: Context, label: String, onClick: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 13f; setOnClickListener { onClick() }
    }
}
