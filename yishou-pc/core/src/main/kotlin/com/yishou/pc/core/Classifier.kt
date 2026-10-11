package com.yishou.pc.core

/**
 * 前台窗口：程序名（小写，例如 quark.exe）、窗口标题、上级进程的程序名（由近到远）。
 */
data class Foreground(val exe: String, val title: String, val ancestors: List<String> = emptyList())

/** 前台窗口属于哪一类要守的东西；都不是就返回 null。 */
object Classifier {

    fun categoryOf(fg: Foreground, prefs: Prefs): String? {
        val exe = fg.exe.lowercase()
        val ancestors = fg.ancestors.map { it.lowercase() }
        val isBrowser = exe in prefs.browsers
        // 先认程序，再认标题：WeGame 里内嵌的网页不会被当成浏览器
        prefs.rules.firstOrNull { it.kind == RuleKind.PROCESS && it.pattern.lowercase() == exe }?.let { return it.category }
        prefs.rules.firstOrNull { it.kind == RuleKind.CHILD_OF && it.pattern.lowercase() in ancestors }?.let { return it.category }
        if (isBrowser) {
            val title = fg.title.lowercase()
            prefs.rules.firstOrNull { it.kind == RuleKind.TITLE && title.contains(it.pattern.lowercase()) }?.let { return it.category }
        }
        return null
    }
}
