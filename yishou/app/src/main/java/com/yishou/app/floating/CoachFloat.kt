package com.yishou.app.floating

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.yishou.app.log.RunLog
import kotlinx.coroutines.*
import kotlin.math.abs

data class FloatAction(val label: String, val click: () -> Unit)
data class FloatPanel(val title: String, val detail: String, val actions: List<FloatAction>, val priority: Int = 0)

/** 几个会话共用一个可拖动的小窗；服务结束就撤掉自己的入口，不叠三层浮窗。 */
object CoachFloat {
    private val panels = linkedMapOf<String, FloatPanel>()
    private var context: Context? = null
    private var root: LinearLayout? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false
    private var signature = ""
    private var hides = 0
    private fun dp(n: Int): Int = ((context?.resources?.displayMetrics?.density ?: 1f) * n).toInt()
    fun update(context: Context, owner: String, panel: FloatPanel) { this.context = context.applicationContext; panels[owner] = panel; refresh(context) }
    fun remove(owner: String) { panels.remove(owner); context?.let(::refresh) }
    fun refresh(context: Context) {
        this.context = context.applicationContext
        if (panels.isEmpty() || !Settings.canDrawOverlays(context)) { detach(); return }
        val current = panels.values.maxByOrNull { it.priority } ?: return
        val key = "$expanded|${current.title}|${current.detail}|${current.actions.map { it.label }}"
        if (root == null) {
            val c = context.applicationContext
            wm = c.getSystemService(WindowManager::class.java)
            val r = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)); elevation = dp(10).toFloat(); background = GradientDrawable().apply { setColor(Color.rgb(30, 39, 42)); cornerRadius = dp(18).toFloat() } }
            val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(140) }
            try { wm!!.addView(r, p); root = r; params = p; signature = "" }
            catch (e: Exception) { RunLog.e("陪练小窗", "没能显示，请检查悬浮窗权限", e); return }
        }
        val r = root ?: return
        r.visibility = if (hides > 0) View.INVISIBLE else View.VISIBLE
        if (key == signature) return
        signature = key; r.removeAllViews()
        val header = TextView(context.applicationContext).apply {
            text = if (expanded) current.title + "  ▾" else "一手 ●"
            setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(8), dp(8), dp(8), dp(8)); contentDescription = "拖动一手小窗，点击展开或收起"
        }
        var downX = 0f; var downY = 0f; var oldX = 0; var oldY = 0; var moved = false
        header.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; oldX = p.x; oldY = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) + abs(e.rawY - downY) > dp(6)) moved = true
                    if (moved) { p.x = (oldX + e.rawX - downX).toInt(); p.y = (oldY + e.rawY - downY).toInt(); place() }
                }
                MotionEvent.ACTION_UP -> if (!moved) { expanded = !expanded; refresh(context) }
            }
            true
        }
        r.addView(header)
        if (expanded) {
            r.addView(TextView(context.applicationContext).apply { text = current.detail.takeLast(500); textSize = 14f; maxLines = 7; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Color.rgb(235, 240, 239)); setPadding(dp(8), dp(6), dp(8), dp(10)) })
            current.actions.chunked(2).forEachIndexed { row, actions ->
                val line = LinearLayout(context.applicationContext)
                actions.forEachIndexed { col, action ->
                    val index = row * 2 + col
                    line.addView(Button(context.applicationContext).apply {
                        text = action.label; textSize = 12f; isAllCaps = false
                        setOnClickListener { panels.values.maxByOrNull { it.priority }?.actions?.getOrNull(index)?.click?.invoke() }
                    }, LinearLayout.LayoutParams(0, dp(46), 1f))
                }
                r.addView(line)
            }
        }
        params?.width = if (expanded) minOf(dp(320), context.resources.displayMetrics.widthPixels - dp(16)) else WindowManager.LayoutParams.WRAP_CONTENT
        place()
    }
    private fun place() {
        val r = root ?: return; val p = params ?: return; val metrics = context?.resources?.displayMetrics ?: return
        p.x = p.x.coerceIn(0, (metrics.widthPixels - if (expanded) p.width else dp(90)).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (metrics.heightPixels - if (expanded) dp(310) else dp(65)).coerceAtLeast(0))
        try { wm?.updateViewLayout(r, p) } catch (e: Exception) { RunLog.e("陪练小窗", "位置更新失败", e); detach() }
    }
    private fun detach() { root?.let { runCatching { wm?.removeView(it) } }; root = null; params = null; signature = "" }
    /** 点看屏时先把自己的面板藏起来，避免把它当作题目。 */
    suspend fun <T> withoutPanel(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        hides++; root?.visibility = View.INVISIBLE
        try { delay(300); withContext(Dispatchers.Default) { block() } }
        finally { hides--; root?.visibility = if (hides > 0) View.INVISIBLE else View.VISIBLE }
    }
}
