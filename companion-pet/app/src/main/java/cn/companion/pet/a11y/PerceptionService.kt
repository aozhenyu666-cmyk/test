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
        PerceptionHub.activePackageReader = {
            runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        }
        PerceptionHub.setConnected(true)
        // 系统会在进程被杀后重新绑定无障碍服务：借这个机会把桌宠拉回来（用户主动停止过则不拉）。
        // 任何失败都只放弃这一次拉起，不能让无障碍服务跟着出错。
        runCatching { if (!PetService.running) PetService.startIfAllowed(this) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
        if (pkg in ignored) return
        val now = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 窗口事件会抖动（菜单、键盘、权限框、系统栏）。短暂防抖后再读实际活动窗口确认。
                pendingPkg = pkg
                pendingAt = now
                handler.removeCallbacks(confirmForeground)
                handler.postDelayed(confirmForeground, DEBOUNCE_MILLIS)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                PerceptionHub.trail.onScroll(pkg, now)
                if (pkg == PerceptionHub.trail.foreground) PerceptionHub.stuck.onScroll(now)
                PerceptionHub.onScroll?.invoke(pkg)
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingPkg: String? = null
    private var pendingAt = 0L
    private val confirmForeground = Runnable {
        val expected = pendingPkg ?: return@Runnable
        val now = System.currentTimeMillis()
        if (now - pendingAt + 1 < DEBOUNCE_MILLIS) return@Runnable
        pendingPkg = null
        val active = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (active.isNullOrBlank() || active in ignored) return@Runnable
        if (active != expected) return@Runnable
        PerceptionHub.trail.onForeground(active, now)
        PerceptionHub.stuck.onPage("$active", now)
        PerceptionHub.onForegroundConfirmed?.invoke(active)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(confirmForeground)
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
            PerceptionHub.activePackageReader = null
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
        private const val MAX_NODES = 600
        private const val MAX_READ_MILLIS = 150L
        /** 窗口事件抖动：250–500ms 内以实际活动窗口为准。 */
        private const val DEBOUNCE_MILLIS = 300L
    }
}
