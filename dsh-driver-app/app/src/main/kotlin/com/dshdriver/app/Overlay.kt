package com.dshdriver.app

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
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

        val p = Palette.dark
        val ui = Ui(context, p)

        // 浮卡本身：圆角、细边、半透明，不遮死 Live 的画面
        val cardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(16), ui.px(13), ui.px(16), ui.px(10))
            background = ui.round(0xF2262624.toInt(), 16f, p.border, 1f)
            elevation = ui.px(8).toFloat()
        }
        ui.reset(cardView)
        ui.kicker("驾驶员")
        ui.text(card.text, 15.5f, p.text, bottom = 6f)
        ui.row {
            card.options?.let { o ->
                ui.micro("A  ${o.a}") {
                    Engine.reply(card.id, null, "A", "overlay")
                    hide()
                }
                ui.micro("B  ${o.b}") {
                    Engine.reply(card.id, null, "B", "overlay")
                    hide()
                }
            }
            ui.micro("展开") {
                hide()
                CardActivity.start(context, card, 0, reflex = false)
            }
            ui.micro("收起") { hide() }
        }

        // 离屏幕边缘留 12dp，顶部再避开状态栏，看起来才像一张浮起来的卡
        val holder = FrameLayout(context).apply {
            setPadding(ui.px(12), ui.px(44), ui.px(12), 0)
            addView(cardView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }

        holder.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                main.post { if (v.isShown) Engine.surface(card.id, "displayed", "overlay") }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        if (runCatching { wm.addView(holder, lp) }.isFailure) {
            Engine.surface(card.id, "not_shown", "overlay")
            return
        }
        view = holder
        cardId = card.id
    }

    fun showCheckin(context: Context, row: org.json.JSONObject) {
        hide()
        val id=row.getString("id")
        val ui=Ui(context,Palette.dark)
        val box=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(ui.px(18),ui.px(16),ui.px(18),ui.px(12))
            background=ui.round(Palette.dark.surface,14f,Palette.dark.border)
        }
        ui.reset(box)
        ui.kicker("5分钟 · 快速察觉")
        ui.text(row.optJSONArray("questions")?.optString(0).orEmpty(),16f)
        ui.caption(ObservationActivity.appsText(row))
        ui.row {
            ui.micro("回答") {hide();ObservationActivity.start(context,id)}
            ui.micro("跳过") {AwarenessRuntime.complete(id,org.json.JSONObject(),true);hide()}
        }
        val holder=FrameLayout(context).apply {
            setPadding(ui.px(12),ui.px(44),ui.px(12),0);addView(box)
        }
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply {gravity=Gravity.TOP}
        val added=runCatching {context.getSystemService(WindowManager::class.java).addView(holder,params)}.isSuccess
        if(added) {view=holder;cardId="observation:$id"} else ObservationActivity.notifyPending(context,id)
    }
    fun hideCheckin() {if(cardId?.startsWith("observation:")==true) hide()}

    fun hide() {
        val v = view ?: return
        runCatching { v.context.getSystemService(WindowManager::class.java)?.removeView(v) }
        view = null
        cardId = null
    }

    fun hideIf(id: String) { if (cardId == id) hide() }
}
