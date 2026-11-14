package io.github.ccch1mneyyy.dsh_app.loop.proactive

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 设置页自卫：管控期间（拒绝模式 / 锁赶 / 任务模式 / 防波堤）他去设置里找"强行停止、卸载、
 * 关无障碍"，就在无障碍里当场拦下来：全局返回、回桌面，再把 tamper 报给插件。
 *
 * 只在设置类 App 前台、并且同一页上同时看到 App 名字和那几个危险入口时才动手，
 * 避免在正常的设置页面里误伤。插件收到后清零信用分、开 60 分钟拒绝模式、叫鲸澜收尾。
 */
object TamperGuard {
    private const val TAG = "DshTamper"
    private const val COOLDOWN_MS = 10_000L
    private const val SCAN_DELAY_MS = 500L
    private const val MAX_NODES = 240

    /** 系统设置和各家厂商的设置 / 权限管家。 */
    private val SETTINGS = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.vivo.permissionmanager",
        "com.samsung.android.lool",
        "com.iqoo.secure",
    )

    /** 页面上出现这些字样，说明他在找关闭管控的入口。 */
    private val DANGER = listOf("强行停止", "强制停止", "卸载", "停用", "无障碍", "辅助功能")

    /** 页面要同时出现 App 自己的名字，才算"冲着 DSH Go / 哨兵来的"。 */
    private val NAMES = listOf("DSH Go", "DSH", "大肥鱼", "大肥鱼哨兵", "哨兵")

    @Volatile private var lastAt = 0L

    fun onWindow(service: AccessibilityService, pkg: String?) {
        if (pkg == null || pkg !in SETTINGS) return
        if (!ProactiveRuntime.controlActive()) return
        if (SystemClock.elapsedRealtime() - lastAt < COOLDOWN_MS) return
        val main = Handler(service.mainLooper)
        // 窗口事件到达时页面可能还没绘完；稍等半秒再读节点树。
        main.postDelayed({
            runCatching { inspect(service, pkg) }
                .onFailure { Log.w(TAG, "inspect $pkg", it) }
        }, SCAN_DELAY_MS)
    }

    private fun inspect(service: AccessibilityService, pkg: String) {
        if (!ProactiveRuntime.controlActive()) return
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return
        val texts = ArrayList<String>(32)
        collect(root, texts, 0)
        if (texts.none { t -> NAMES.any { t.contains(it, ignoreCase = true) } }) return
        val what = DANGER.firstOrNull { w -> texts.any { it.contains(w) } } ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < COOLDOWN_MS) return
        lastAt = now
        Log.i(TAG, "tamper: '$what' in $pkg")
        runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
        Handler(service.mainLooper).postDelayed({
            runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
        }, 120L)
        ProactiveRuntime.reportTamper(what, pkg)
    }

    /** 节点树有界遍历：只取文字，最多 240 条、14 层，别在设置页上卡住无障碍。 */
    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 14 || out.size >= MAX_NODES) return
        node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until minOf(node.childCount, 40)) {
            if (out.size >= MAX_NODES) return
            collect(runCatching { node.getChild(i) }.getOrNull(), out, depth + 1)
        }
    }
}
