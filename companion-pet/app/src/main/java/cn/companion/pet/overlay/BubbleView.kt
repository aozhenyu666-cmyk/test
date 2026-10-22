package cn.companion.pet.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

data class BubbleSpec(
    val text: String,
    val showMic: Boolean = true,
    val showType: Boolean = true,
    val showGemini: Boolean = true,
    val geminiHighlighted: Boolean = false,
    val showLive: Boolean = false,
    /** 只显示文字，不显示入口（例如桌宠的收尾句）。 */
    val textOnly: Boolean = false,
)

interface BubbleListener {
    fun onMicDown()
    fun onMicUp()
    fun onTypingStarted()
    fun onTextSubmitted(text: String)
    fun onGemini()
    fun onLive()
    fun onClose()
    fun onBubbleTouched()
}

/**
 * 漫画式气泡：白底深色描边、带一个指向角色的小尾巴；文字逐字出现。
 * 没有快捷回复按钮，只有入口：按住说话、打字、去 Gemini（学习场景多一个“一起看”）。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility", "SetTextI18n")
class BubbleView(context: Context, private val listener: BubbleListener) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val textView = TextView(context)
    private val actions = LinearLayout(context)
    private val inputRow = LinearLayout(context)
    private val input = EditText(context)
    private val bubbleBg = ComicBubble(dp(2f), dp(14f), dp(10f))
    private var fullText = ""
    private var typed = 0
    private var onTyped: (() -> Unit)? = null

    var tailOnRight: Boolean
        get() = bubbleBg.tailOnRight
        set(value) {
            bubbleBg.tailOnRight = value
            val tail = dp(10f).toInt()
            val p = dp(14f).toInt()
            setPadding(p + if (value) 0 else tail, p, p + if (value) tail else 0, p)
            invalidate()
        }

    init {
        orientation = VERTICAL
        setBackground(bubbleBg)
        tailOnRight = true

        textView.setTextColor(INK)
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        textView.setLineSpacing(0f, 1.15f)
        addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        actions.orientation = HORIZONTAL
        actions.gravity = Gravity.START
        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f).toInt() })

        inputRow.orientation = HORIZONTAL
        inputRow.visibility = GONE
        input.setTextColor(INK)
        input.setHintTextColor(Color.GRAY)
        input.hint = "想说什么"
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.maxLines = 3
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        inputRow.addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(chip("发送", false) { submit() })
        addView(inputRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f).toInt() })

        setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) listener.onBubbleTouched(); false }
    }

    fun show(spec: BubbleSpec, onFinishedTyping: (() -> Unit)? = null) {
        inputRow.visibility = GONE
        input.setText("")
        actions.removeAllViews()
        if (!spec.textOnly) {
            if (spec.showMic) actions.addView(micChip())
            if (spec.showType) actions.addView(chip("打字", false) { startTyping() })
            if (spec.showGemini) actions.addView(chip("去 Gemini", spec.geminiHighlighted) { listener.onGemini() })
            if (spec.showLive) actions.addView(chip("一起看", false) { listener.onLive() })
            actions.addView(chip("×", false) { listener.onClose() })
        }
        actions.visibility = if (spec.textOnly) GONE else VISIBLE
        typeOut(spec.text, onFinishedTyping)
    }

    fun setMicLabel(listening: Boolean) {
        (actions.findViewWithTag<TextView>(MIC_TAG))?.text = if (listening) "松开结束" else "按住说话"
    }

    private fun typeOut(text: String, done: (() -> Unit)?) {
        handler.removeCallbacksAndMessages(null)
        fullText = text
        typed = 0
        onTyped = done
        textView.text = ""
        step()
    }

    private fun step() {
        if (typed >= fullText.length) {
            onTyped?.invoke(); onTyped = null
            return
        }
        typed += Character.charCount(fullText.codePointAt(typed))
        textView.text = fullText.substring(0, typed)
        handler.postDelayed({ step() }, 55)
    }

    private fun startTyping() {
        inputRow.visibility = VISIBLE
        listener.onTypingStarted()
        input.requestFocus()
    }

    fun focusInput() {
        input.requestFocus()
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.showSoftInput(input, 0)
    }

    private fun submit() {
        val t = input.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        inputRow.visibility = GONE
        listener.onTextSubmitted(t)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun micChip(): TextView = chip("按住说话", false) {}.apply {
        tag = MIC_TAG
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; listener.onMicDown() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; listener.onMicUp() }
            }
            true
        }
    }

    private fun chip(label: String, highlighted: Boolean, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (highlighted) Color.WHITE else INK)
        val padH = dp(10f).toInt()
        val padV = dp(6f).toInt()
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            cornerRadius = dp(14f)
            setColor(if (highlighted) ACCENT else Color.rgb(255, 244, 230))
            setStroke(dp(1.5f).toInt(), INK)
        }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f).toInt() }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /** 白底描边圆角框 + 侧边小尾巴。 */
    private class ComicBubble(val stroke: Float, val radius: Float, val tail: Float) : Drawable() {
        var tailOnRight = true
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK; style = Paint.Style.STROKE; strokeWidth = stroke; strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        private val r = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val half = stroke / 2
            r.set(b.left + half + if (tailOnRight) 0f else tail, b.top + half, b.right - half - if (tailOnRight) tail else 0f, b.bottom - half)
            path.reset()
            path.addRoundRect(r, radius, radius, Path.Direction.CW)
            val ty = r.top + radius + tail
            val tri = Path()
            if (tailOnRight) {
                tri.moveTo(r.right - half, ty - tail * 0.7f); tri.lineTo(b.right - half, ty); tri.lineTo(r.right - half, ty + tail * 0.7f)
            } else {
                tri.moveTo(r.left + half, ty - tail * 0.7f); tri.lineTo(b.left + half, ty); tri.lineTo(r.left + half, ty + tail * 0.7f)
            }
            tri.close()
            path.op(tri, Path.Op.UNION)
            canvas.drawPath(path, fill)
            canvas.drawPath(path, line)
        }

        override fun setAlpha(alpha: Int) { fill.alpha = alpha; line.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val MIC_TAG = "mic"
        val INK = Color.rgb(70, 48, 38)
        val ACCENT = Color.rgb(108, 142, 230)
    }
}
