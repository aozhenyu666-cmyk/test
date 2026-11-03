package io.github.ccch1mneyyy.dsh_app.loop.driver

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * level 0 的小卡：浮在 Gemini Live 顶部，不抢焦点，Live 照常听你说话。
 * 只放 A/B 和"展开"；要打字就展开成全屏卡。卡真的贴到窗口上才回报 displayed。
 */
object DriverOverlay {
    private var view: View? = null
    private var cardId: String? = null

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context, card: DriverRuntime.Card) {
        val wm = context.getSystemService(WindowManager::class.java) ?: return DriverRuntime.onCardNotShown(card.id, "overlay")
        hide()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0xEE1B2230.toInt())
        }
        root.addView(TextView(context).apply { text = card.text; setTextColor(0xFFFFFFFF.toInt()); textSize = 16f })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        if (card.optionA != null && card.optionB != null) {
            row.addView(btn(context, "A ${card.optionA}") { DriverRuntime.onReply(card.id, null, "A", "overlay"); hide() })
            row.addView(btn(context, "B ${card.optionB}") { DriverRuntime.onReply(card.id, null, "B", "overlay"); hide() })
        }
        row.addView(btn(context, "展开") { hide(); DriverCardActivity.start(context, card, reflex = false) })
        root.addView(row)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { v.post { if (v.isShown) DriverRuntime.onCardShown(card.id, "overlay") } }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        val ok = runCatching { wm.addView(root, lp) }.isSuccess
        if (!ok) { DriverRuntime.onCardNotShown(card.id, "overlay"); return }
        view = root
        cardId = card.id
    }

    fun hide() {
        val v = view ?: return
        runCatching { v.context.getSystemService(WindowManager::class.java)?.removeView(v) }
        view = null
        cardId = null
    }

    private fun btn(context: Context, label: String, onClick: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }
}
