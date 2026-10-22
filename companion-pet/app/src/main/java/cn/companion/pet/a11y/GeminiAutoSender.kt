package cn.companion.pet.a11y

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 实验功能“自动发送”：在 Gemini 界面里找输入框，写入文字并点发送。
 *
 * 只在用户刚点了“去 Gemini”之后调用一次；3 秒内找不到就放弃，由调用方退回手动粘贴。
 * 只尝试一次，不重试，所以不会重复发送。Gemini 改版导致找不到控件时只是失败，不影响别的功能。
 */
object GeminiAutoSender {
    private val sendWords = listOf("发送", "send", "提交", "submit")

    suspend fun send(geminiPackage: String, text: String, timeoutMillis: Long = 3_000): Boolean {
        val service = PerceptionHub.service ?: return false
        val input = withTimeoutOrNull(timeoutMillis) {
            var found: AccessibilityNodeInfo? = null
            while (found == null) {
                val root = service.rootInActiveWindow
                if (root != null && root.packageName?.toString() == geminiPackage) found = findEditable(root)
                if (found == null) delay(250)
            }
            found
        } ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        delay(300)
        val root = service.rootInActiveWindow ?: return false
        val button = findSendButton(root) ?: return false
        return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        walk(root).firstOrNull { it.isEditable && it.isEnabled }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        walk(root).firstOrNull { n ->
            val label = (n.contentDescription ?: n.text)?.toString()?.lowercase() ?: return@firstOrNull false
            n.isClickable && n.isEnabled && sendWords.any { label.contains(it) }
        }

    private fun walk(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var count = 0
        while (stack.isNotEmpty() && count < 600) {
            val n = stack.removeLast()
            count++
            yield(n)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.addLast(it) }
        }
    }
}
