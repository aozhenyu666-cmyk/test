package io.github.ccch1mneyyy.dsh_app.fish.guardian.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EmergencyPassQuota
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.GuardianConstants
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.GuardianDecide
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.WindowDecision
import io.github.ccch1mneyyy.dsh_app.fish.guardian.ui.GuardianModalActivity
import io.github.ccch1mneyyy.dsh_app.fish.guardian.watch.ArcLog
import io.github.ccch1mneyyy.dsh_app.fish.guardian.watch.GuardianStores
import io.github.ccch1mneyyy.dsh_app.fish.guardian.watch.OfflineIncident
import io.github.ccch1mneyyy.dsh_app.fish.guardian.watch.WatchService

/**
 * Lightweight backup accessibility service enforcing mirrored DSH Go rules and guarding
 * both DSH Go & `大肥鱼哨兵` against Settings tamper or silent engine termination.
 */
class GuardianA11yService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var stores: GuardianStores
    private var lastTriggerMono = 0L
    private var homePackages: Set<String> = emptySet()
    private var imePackages: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        stores = GuardianStores(filesDir)
        refreshSystemSets()
        WatchService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return

        val nowMono = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        if (homePackages.isEmpty()) refreshSystemSets()

        // Slight debounce only inside Settings to let view tree settle before tamper scan.
        if (pkg in GuardianConstants.SETTINGS_PACKAGES) {
            main.postDelayed({ evaluatePackage(pkg, SystemClock.elapsedRealtime(), System.currentTimeMillis()) }, 400L)
            return
        }
        evaluatePackage(pkg, nowMono, nowWall)
    }

    private fun evaluatePackage(pkg: String, nowMono: Long, nowWall: Long) {
        val snap = stores.loadSnapshot()
        val mirror = stores.loadMirror()
        val settings = stores.loadSettings()
        val pass = stores.loadPass()
        val passActive = EmergencyPassQuota.isActive(pass, nowMono)
        val verdict = snap.verdict(nowMono, passActive)

        val texts = if (pkg in GuardianConstants.SETTINGS_PACKAGES) collectVisibleTexts() else emptyList()
        val decision = GuardianDecide.evaluateWindow(
            frontPkg = pkg,
            screenTexts = texts,
            homePackages = homePackages,
            imePackages = imePackages,
            mirror = mirror,
            engineVerdict = verdict,
            passActive = passActive,
            modalOnEngineDown = settings.modalOnEngineDown,
            nowWall = nowWall,
        )

        if (decision is WindowDecision.Allow) return
        if (nowMono - lastTriggerMono < 2_500L) return
        lastTriggerMono = nowMono

        when (decision) {
            is WindowDecision.BlockTamper -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                main.postDelayed({ runCatching { performGlobalAction(GLOBAL_ACTION_HOME) } }, 120L)
                stores.enqueueIncident(
                    OfflineIncident(
                        id = "tamper-$nowWall",
                        type = "tamper_blocked",
                        atWall = nowWall,
                        detail = "设置页试图关闭受保护应用（${decision.matchedKeyword}）",
                        pkg = decision.inPkg,
                    ),
                )
                ArcLog.append(this, "设置防篡改拦截：${decision.matchedKeyword} in ${decision.inPkg}")
                startActivity(
                    GuardianModalActivity.buildIntent(
                        this,
                        GuardianModalActivity.MODE_TAMPER,
                        "已拦截关闭大肥鱼守护的操作",
                        "检测到你在系统设置页中停留在「${decision.matchedKeyword}」位置。",
                        snap.targetPkg,
                        snap.enginePhase,
                    ),
                )
            }
            is WindowDecision.BlockApp -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                startActivity(
                    GuardianModalActivity.buildIntent(
                        this,
                        GuardianModalActivity.MODE_MIRROR_BLOCK,
                        "当前处于大肥鱼计划管控",
                        decision.reason,
                        snap.targetPkg,
                        snap.enginePhase,
                    ),
                )
            }
            is WindowDecision.BlockEngineDown -> {
                startActivity(
                    GuardianModalActivity.buildIntent(
                        this,
                        GuardianModalActivity.MODE_ENGINE_DOWN,
                        "大肥鱼引擎尚未恢复",
                        "${decision.reason}。请先唤醒引擎再继续使用其他应用。",
                        snap.targetPkg,
                        snap.enginePhase,
                    ),
                )
            }
            WindowDecision.Allow -> Unit
        }
    }

    private fun collectVisibleTexts(): List<String> {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(48)
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 12 || out.size >= 160) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
            for (i in 0 until minOf(node.childCount, 30)) {
                if (out.size >= 160) return
                walk(runCatching { node.getChild(i) }.getOrNull(), depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    private fun refreshSystemSets() {
        homePackages = runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())
        imePackages = runCatching {
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: GuardianA11yService? = null
            private set
    }
}
