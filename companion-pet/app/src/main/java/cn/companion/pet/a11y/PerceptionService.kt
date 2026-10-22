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
        // 系统会在进程被杀后重新绑定无障碍服务：借这个机会把桌宠拉回来（用户主动停止过则不拉）
        if (!PetService.running) PetService.startIfAllowed(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignored) return
        val now = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                PerceptionHub.trail.onForeground(pkg, now)
                PerceptionHub.stuck.onPage("$pkg/${event.className}", now)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                PerceptionHub.trail.onScroll(pkg, now)
                if (pkg == PerceptionHub.trail.foreground) PerceptionHub.stuck.onScroll(now)
                PerceptionHub.onScroll?.invoke(pkg)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
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
        while (stack.isNotEmpty() && visited < MAX_NODES) {
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
        private const val MAX_NODES = 800
    }
}
