package cn.companion.pet.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import cn.companion.pet.core.life.LifeState
import cn.companion.pet.core.probe.Mood
import cn.companion.pet.data.Settings
import kotlin.math.abs

interface PetListener {
    fun onPetTapped()
    fun onHoldStart()
    fun onHoldEnd()
}

/**
 * 两个悬浮窗：角色（始终在）和气泡（需要时出现）。
 *
 * 角色默认停在右侧边缘，可拖动，松手吸附最近的边；位置记在设置里。
 * 所有位置都限制在“安全区域”内（避开状态栏和导航栏），升级时的“靠中间”也不越界。
 */
@SuppressLint("ClickableViewAccessibility")
class PetOverlay(
    private val context: Context,
    private val settings: Settings,
    private val petListener: PetListener,
    bubbleListener: BubbleListener,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    val pet = PetView(context)
    val bubble = BubbleView(context, bubbleListener)

    private val petSize = dp(80f)
    private val petParams = overlayParams(petSize, petSize, focusable = false)
    private val bubbleParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, focusable = false)

    private var petShown = false
    private var bubbleShown = false
    private var nudged = false
    private var animator: ValueAnimator? = null

    val isBubbleShown: Boolean get() = bubbleShown

    fun show() {
        if (petShown) return
        val safe = safeArea()
        petParams.x = if (settings.petEdgeRight) safe.right - petSize else safe.left
        petParams.y = if (settings.petY >= 0) settings.petY.coerceIn(safe.top, safe.bottom - petSize) else safe.top + (safe.height() * 0.55f).toInt()
        pet.facingLeft = settings.petEdgeRight
        pet.setOnTouchListener(dragListener())
        runCatching { wm.addView(pet, petParams) }.onSuccess { petShown = true }
    }

    fun hide() {
        hideBubble()
        if (petShown) runCatching { wm.removeView(pet) }
        petShown = false
    }

    fun setMood(mood: Mood) { pet.mood = mood }
    fun setLife(state: LifeState) { pet.life = state }
    fun setTalking(v: Boolean) { pet.talking = v }
    fun setListening(v: Boolean) { pet.listening = v; bubble.setMicLabel(v) }
    fun setHoldingLetter(v: Boolean) { pet.holdingLetter = v }
    fun attention() = pet.attention()
    fun setInSession(v: Boolean) { pet.inSession = v }

    /** 气泡里打开输入框（并让窗口能弹输入法）。 */
    fun openBubbleInput() { if (bubbleShown) bubble.openInput() }

    /** 只换气泡文字（倒计时每秒更新），尺寸变化时重新摆位置。 */
    fun updateBubbleText(text: String) {
        if (!bubbleShown) return
        bubble.setTextNow(text)
        layoutBubble()
        runCatching { wm.updateViewLayout(bubble, bubbleParams) }
    }

    fun showBubble(spec: BubbleSpec, onFinishedTyping: (() -> Unit)? = null) {
        if (!petShown) return
        bubble.tailOnRight = isOnRight()
        bubble.show(spec, onFinishedTyping)
        layoutBubble()
        if (!bubbleShown) {
            runCatching { wm.addView(bubble, bubbleParams) }.onSuccess { bubbleShown = true }
        } else {
            runCatching { wm.updateViewLayout(bubble, bubbleParams) }
        }
    }

    fun hideBubble() {
        setBubbleFocusable(false)
        if (bubbleShown) runCatching { wm.removeView(bubble) }
        bubbleShown = false
    }

    /** 打字时气泡窗口需要能拿到焦点才能弹出输入法；收起后恢复不可聚焦。 */
    fun setBubbleFocusable(focusable: Boolean) {
        val flags = if (focusable) {
            bubbleParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            bubbleParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (flags == bubbleParams.flags) return
        bubbleParams.flags = flags
        bubbleParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        if (bubbleShown) runCatching { wm.updateViewLayout(bubble, bubbleParams) }
        if (focusable) handler.postDelayed({ bubble.focusInput() }, 120)
    }

    /** 升级第二级：往屏幕中间挪一段（仍在安全区内，高度不变）。 */
    fun nudgeInward() {
        if (!petShown || nudged) return
        nudged = true
        val safe = safeArea()
        val inset = (safe.width() * 0.22f).toInt()
        val target = if (isOnRight()) safe.right - petSize - inset else safe.left + inset
        animateX(target)
    }

    /** 回到边缘。 */
    fun retreat() {
        if (!nudged) return
        nudged = false
        val safe = safeArea()
        animateX(if (settings.petEdgeRight) safe.right - petSize else safe.left)
    }

    private fun animateX(target: Int) {
        animator?.cancel()
        animator = ValueAnimator.ofInt(petParams.x, target).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                petParams.x = it.animatedValue as Int
                if (petShown) runCatching { wm.updateViewLayout(pet, petParams) }
                if (bubbleShown) { layoutBubble(); runCatching { wm.updateViewLayout(bubble, bubbleParams) } }
            }
            start()
        }
    }

    private fun isOnRight(): Boolean = petParams.x + petSize / 2 > screen().width() / 2

    /** 气泡放在角色朝屏幕内侧的一边，和角色顶部对齐；宽度不超过屏幕 62%。 */
    private fun layoutBubble() {
        val safe = safeArea()
        val maxW = (screen().width() * 0.62f).toInt()
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val bw = bubble.measuredWidth
        val bh = bubble.measuredHeight
        bubbleParams.width = bw
        bubbleParams.x = if (isOnRight()) petParams.x - bw + dp(4f) else petParams.x + petSize - dp(4f)
        bubbleParams.y = (petParams.y - dp(10f)).coerceIn(safe.top, (safe.bottom - bh).coerceAtLeast(safe.top))
    }

    private fun dragListener() = object : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var holding = false
        private val holdRunnable = Runnable {
            holding = true
            petListener.onHoldStart()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = petParams.x; startY = petParams.y
                    dragging = false; holding = false
                    handler.postDelayed(holdRunnable, 400)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !holding && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        handler.removeCallbacks(holdRunnable)
                    }
                    if (dragging) {
                        petParams.x = startX + dx.toInt()
                        petParams.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(pet, petParams) }
                        if (bubbleShown) { layoutBubble(); runCatching { wm.updateViewLayout(bubble, bubbleParams) } }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(holdRunnable)
                    when {
                        holding -> petListener.onHoldEnd()
                        dragging -> snapToEdge()
                        e.actionMasked == MotionEvent.ACTION_UP -> petListener.onPetTapped()
                    }
                    dragging = false; holding = false
                }
            }
            return true
        }
    }

    private fun snapToEdge() {
        val safe = safeArea()
        val right = petParams.x + petSize / 2 > screen().width() / 2
        settings.petEdgeRight = right
        pet.facingLeft = right
        nudged = false
        petParams.y = petParams.y.coerceIn(safe.top, safe.bottom - petSize)
        settings.petY = petParams.y
        bubble.tailOnRight = right
        animateX(if (right) safe.right - petSize else safe.left)
    }

    private fun screen(): Rect = wm.currentWindowMetrics.bounds

    /** 屏幕减去状态栏、导航栏和刘海。拿不到时退回整个屏幕。 */
    private fun safeArea(): Rect {
        val m = wm.currentWindowMetrics
        val b = Rect(0, 0, m.bounds.width(), m.bounds.height())
        val ins = runCatching {
            m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        }.getOrNull() ?: return b
        return Rect(b.left + ins.left, b.top + ins.top, b.right - ins.right, b.bottom - ins.bottom)
    }

    private fun overlayParams(w: Int, h: Int, focusable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()
}
