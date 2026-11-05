package com.yishou.app.floating

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.yishou.app.log.RunLog
import kotlinx.coroutines.*
import kotlin.math.abs

data class FloatAction(val label: String, val primary: Boolean = false, val click: () -> Unit)
data class FloatPanel(
    val title: String,
    val detail: String,
    val actions: List<FloatAction>,
    val priority: Int = 0,
    /** 新内容出现时自动展开一次；用户手动收起后不强行弹回。 */
    val autoExpand: Boolean = false,
    /** 在浮窗里直接显示输入框，避免后台 startActivity 被系统拦掉。 */
    val reply: Boolean = false,
    val replyHint: String = "",
    val onSubmit: ((String) -> Unit)? = null,
)

/** 几个会话共用一个可拖动的小窗；服务结束就撤掉自己的入口，不叠三层浮窗。 */
object CoachFloat {
    private val panels = linkedMapOf<String, FloatPanel>()
    private var context: Context? = null
    private var root: LinearLayout? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false
    private var lastAutoContentKey = ""
    private var focusedKey = ""
    private var signature = ""
    private var hides = 0
    private var lastX: Int? = null
    private var lastY: Int? = null
    private fun dp(n: Int): Int = ((context?.resources?.displayMetrics?.density ?: 1f) * n).toInt()

    /** 统一的胶囊按钮：金色主操作，深灰次操作。 */
    private fun actionView(context: Context, label: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (primary) Color.rgb(20, 23, 28) else Color.rgb(230, 228, 220))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (primary) Color.rgb(217, 180, 74) else Color.rgb(42, 52, 55))
                if (!primary) setStroke(dp(1), Color.rgb(62, 74, 78))
            }
            isClickable = true
            isFocusable = true
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onClick() }
        }
    fun update(context: Context, owner: String, panel: FloatPanel) { this.context = context.applicationContext; panels[owner] = panel; refresh(context) }
    fun remove(owner: String) {
        panels.remove(owner)
        if (panels.isEmpty()) {
            expanded = false
            lastAutoContentKey = ""
        }
        context?.let(::refresh)
    }
    fun refresh(context: Context) {
        this.context = context.applicationContext
        if (panels.isEmpty() || !Settings.canDrawOverlays(context)) { detach(); return }
        val current = panels.values.maxByOrNull { it.priority } ?: return
        val contentKey = current.title + "|" + current.detail + "|" + current.actions.joinToString { it.label }
        if (current.autoExpand && contentKey != lastAutoContentKey) {
            expanded = true
            lastAutoContentKey = contentKey
        }
        val key = "$expanded|${current.title}|${current.detail}|${current.actions.map { it.label }}"
        if (root == null) {
            val c = context.applicationContext
            wm = c.getSystemService(WindowManager::class.java)
            val r = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)); elevation = dp(12).toFloat(); background = GradientDrawable().apply { setColor(Color.rgb(30, 39, 42)); cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.argb(70, 217, 180, 74)) } }
            val p = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = lastX ?: dp(12); y = lastY ?: dp(140) }
            try { wm!!.addView(r, p); root = r; params = p; signature = "" }
            catch (e: Exception) { RunLog.e("陪练小窗", "没能显示，请检查悬浮窗权限", e); return }
        }
        val r = root ?: return
        r.visibility = if (hides > 0) View.INVISIBLE else View.VISIBLE

        val wantFocus = expanded && current.reply && current.onSubmit != null
        val desiredFlags = if (wantFocus) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        params?.let { p ->
            if (p.flags != desiredFlags) {
                p.flags = desiredFlags
                runCatching { wm?.updateViewLayout(r, p) }
            }
        }

        if (key == signature) return
        signature = key; r.removeAllViews()
        val header = TextView(context.applicationContext).apply {
            text = if (expanded) current.title + "  ▾" else "一手 ●"
            setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(9), dp(10), dp(9)); contentDescription = "拖动一手小窗，点击展开或收起"
        }
        header.setOnClickListener { expanded = !expanded; refresh(context) }
        var downX = 0f; var downY = 0f; var oldX = 0; var oldY = 0; var moved = false
        header.setOnTouchListener { view, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; oldX = p.x; oldY = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) + abs(e.rawY - downY) > dp(6)) moved = true
                    if (moved) { p.x = (oldX + e.rawX - downX).toInt(); p.y = (oldY + e.rawY - downY).toInt(); place() }
                }
                MotionEvent.ACTION_UP -> if (!moved) view.performClick() else {
                    r.post {
                        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                            // Android 10 不会可靠刷新 overlay 子节点坐标；在原位置重挂窗口，
                            // 让无障碍服务拿到新窗口，普通点击和读屏都能继续使用。
                            detach()
                            refresh(context)
                        } else {
                            r.sendAccessibilityEventUnchecked(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                                contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
                            })
                        }
                    }
                }
            }
            true
        }
        r.addView(header)
        if (expanded) {
            r.addView(TextView(context.applicationContext).apply { text = current.detail.takeLast(500); textSize = 15f; setLineSpacing(dp(3).toFloat(), 1f); maxLines = 7; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Color.rgb(235, 240, 239)); setPadding(dp(10), dp(4), dp(10), dp(12)) })

            // 直接在浮窗里输入：Android 10+ 不允许后台 startActivity，
            // 从悬浮窗打开回复页会被系统静默拦掉，所以回答必须留在这个窗口里。
            val replyInput = if (current.reply && current.onSubmit != null) {
                EditText(context.applicationContext).apply {
                    hint = current.replyHint.ifBlank { "回一句" }
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.rgb(150, 163, 175))
                    textSize = 15f
                    minLines = 2
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(19, 27, 30))
                        cornerRadius = dp(14).toFloat()
                        setStroke(dp(1), Color.rgb(58, 70, 74))
                    }
                }.also { r.addView(it) }
            } else {
                null
            }

            current.actions.chunked(2).forEachIndexed { row, actions ->
                val line = LinearLayout(context.applicationContext)
                actions.forEachIndexed { col, action ->
                    val index = row * 2 + col
                    line.addView(actionView(context.applicationContext, action.label, action.primary) {
                        panels.values.maxByOrNull { it.priority }?.actions?.getOrNull(index)?.click?.invoke()
                    }, LinearLayout.LayoutParams(0, dp(46), 1f))
                }
                r.addView(line)
            }

            if (replyInput != null) {
                r.addView(actionView(context.applicationContext, "交这一句", primary = true) {
                    val text = replyInput.text.toString().trim()
                    if (text.isNotEmpty()) {
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                            ?.hideSoftInputFromWindow(replyInput.windowToken, 0)
                        current.onSubmit?.invoke(text)
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

                if (wantFocus && focusedKey != contentKey) {
                    focusedKey = contentKey
                    replyInput.requestFocus()
                    replyInput.post {
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                            ?.showSoftInput(replyInput, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
        }
        params?.width = if (expanded) minOf(dp(320), context.resources.displayMetrics.widthPixels - dp(16)) else WindowManager.LayoutParams.WRAP_CONTENT
        place()
    }
    private fun place() {
        val r = root ?: return; val p = params ?: return; val metrics = context?.resources?.displayMetrics ?: return
        p.x = p.x.coerceIn(0, (metrics.widthPixels - if (expanded) p.width else dp(90)).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (metrics.heightPixels - if (expanded) dp(310) else dp(65)).coerceAtLeast(0))
        lastX = p.x; lastY = p.y
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
