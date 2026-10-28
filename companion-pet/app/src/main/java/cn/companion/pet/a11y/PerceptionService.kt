package cn.companion.pet.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import cn.companion.pet.core.text.TextCandidate
import cn.companion.pet.service.PetService

/**
 * 感知：窗口切换判断前台 App，滚动事件只记时间和来源。
 * 正文只在桌宠服务准备开口时通过 [PerceptionHub.readScreen] 现读一次，这里不缓存。
 *
 * 唯一的写操作（Gemini 自动发送实验）在 [GeminiAutoSender]，与这里的读取逻辑分开。
 */
class PerceptionService : AccessibilityService() {

    private var ignored: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val imes = runCatching {
            getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        // 输入法、系统界面和我们自己的悬浮窗不算“切换了 App”
        ignored = (imes + listOf(packageName, "com.android.systemui", "android")).toSet()
        PerceptionHub.service = this
        PerceptionHub.reader = { readWindowText() }
        PerceptionHub.setConnected(true)
        // 系统会在进程被杀后重新绑定无障碍服务：借这个机会把桌宠拉回来（用户主动停止过则不拉）。
        // 任何失败都只放弃这一次拉起，不能让无障碍服务跟着出错。
        runCatching { if (!PetService.running) PetService.startIfAllowed(this) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            // 只认输入框里的文字变化：游戏计分、播放器时间、聊天列表刷新也会发这个事件，
            // 以前全当成“你在打字”，主动开口就一直闭嘴。
            val cls = event.className?.toString().orEmpty()
            if (cls.contains("EditText") || cls.contains("TextField") || cls.contains("AutoComplete")) {
                PerceptionHub.lastTypingAt = android.os.SystemClock.elapsedRealtime()
            }
        }
        event ?: return
        // 这里的异常会让系统把服务标成“此服务出现故障”，并要求用户手动关掉再打开。
        // 感知只是辅助信息，宁可丢一个事件也不能让服务崩掉。
        try {
            handle(event)
        } catch (e: Exception) {
            android.util.Log.w("PerceptionService", "event dropped", e)
        }
    }

    private fun handle(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 自己的窗口：确认真的是本应用页面到了前台（不是悬浮窗），再通知（自律锁定、回到聊天）。
            handler.removeCallbacks(confirmOwn)
            handler.postDelayed(confirmOwn, CONFIRM_DELAY_MILLIS)
            return
        }
        if (pkg in ignored) return
        val now = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                PerceptionHub.trail.onForeground(pkg, now)
                PerceptionHub.stuck.onPage("$pkg/${event.className}", now)
                // 专注守护另走一条“防抖 + 读真实活动窗口”的确认，原有轨迹/卡壳逻辑不变。
                handler.removeCallbacks(confirmForeground)
                handler.postDelayed(confirmForeground, CONFIRM_DELAY_MILLIS)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                PerceptionHub.trail.onScroll(pkg, now)
                if (pkg == PerceptionHub.trail.foreground) PerceptionHub.stuck.onScroll(now)
                PerceptionHub.onScroll?.invoke(pkg)
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val confirmOwn = Runnable {
        try {
            if (foregroundApplicationPackage() == packageName) PerceptionHub.onOwnForeground?.invoke()
        } catch (e: Exception) {
            android.util.Log.w("PerceptionService", "own confirm dropped", e)
        }
    }
    private val confirmForeground = Runnable {
        try {
            val active = foregroundApplicationPackage()
            if (!active.isNullOrBlank() && active !in ignored && active !in cn.companion.pet.core.focus.ForegroundGate.DEFAULT_SYSTEM) {
                PerceptionHub.onForegroundConfirmed?.invoke(active)
            }
        } catch (e: Exception) {
            android.util.Log.w("PerceptionService", "confirm dropped", e)
        }
    }

    /** Application windows exclude our accessibility blocker and the IME. Never infer from events. */
    fun foregroundApplicationPackage(): String? = runCatching {
        val appWindows = windows.orEmpty().filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        val window = appWindows.sortedWith(compareByDescending<android.view.accessibility.AccessibilityWindowInfo> { it.isFocused }
            .thenByDescending { it.isActive }.thenByDescending { it.layer }).firstOrNull()
        if (window != null) window.root?.packageName?.toString()
        else rootInActiveWindow?.takeUnless { it.packageName?.toString() == packageName }?.packageName?.toString()
    }.getOrNull()

    /** 每次绑定一个新实例号：回执能看出服务是否被系统重建过。 */
    private val instanceId = java.util.UUID.randomUUID().toString().take(8)

    /**
     * 执行路径健康挑战（A13/A35）：投到与前台确认**同一个** Handler，在那里做同样的活动窗口读取，
     * 做完才回执。主线程卡住或窗口读取失败时，回执会迟到或 ok=false——不能由入口直接回显。
     */
    fun runHealthChallenge(challengeId: String, nonce: String) {
        handler.post {
            val ok = try { rootInActiveWindow != null } catch (e: Exception) { false }
            val receipt = cn.companion.pet.core.training.HealthReceipt(
                challengeId = challengeId,
                nonce = nonce,
                stage = HEALTH_STAGE,
                instanceId = instanceId,
                ok = ok,
                completedAt = android.os.SystemClock.elapsedRealtime(),
            )
            try {
                PerceptionHub.onHealthReceipt?.invoke(receipt)
            } catch (e: Exception) {
                android.util.Log.w("PerceptionService", "health receipt dropped", e)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(confirmForeground)
        handler.removeCallbacks(confirmOwn)
        disconnect()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    private fun disconnect() {
        if (PerceptionHub.service === this) {
            PerceptionHub.service = null
            PerceptionHub.reader = null
            PerceptionHub.setConnected(false)
        }
    }

    /**
     * 读当前活动窗口的文字节点。跳过密码框和我们自己的窗口；
     * 节点数有上限，避免超大页面卡住主线程。
     */
    private fun readWindowText(): List<TextCandidate> {
        val root = rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() == packageName) return emptyList()
        val out = ArrayList<TextCandidate>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Boolean>>()
        stack.addLast(root to false)
        var visited = 0
        // 读节点是跨进程调用，且多半在主线程：主线程同时负责处理无障碍事件，卡久了系统会判定服务故障或 ANR。
        // 给一个时间上限，读不完就用已经读到的。
        val deadline = android.os.SystemClock.uptimeMillis() + MAX_READ_MILLIS
        while (stack.isNotEmpty() && visited < MAX_NODES && android.os.SystemClock.uptimeMillis() < deadline) {
            val (node, inScroll) = stack.removeLast()
            visited++
            if (node.isPassword) continue
            val scroll = inScroll || node.isScrollable
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) out += TextCandidate(text, scroll)
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it to scroll) }
            }
        }
        return out
    }

    companion object {
        /** 窗口事件会抖动（菜单、键盘、权限框）：等这么久再读真实活动窗口。 */
        private const val CONFIRM_DELAY_MILLIS = 300L
        private const val MAX_NODES = 600
        private const val MAX_READ_MILLIS = 150L
        /** 回执阶段：走完了前台确认所在的 Handler 并读过活动窗口。 */
        const val HEALTH_STAGE = "a11y_handler_root_read"
    }
}
