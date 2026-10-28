package com.zongkong.sentinel

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** 哨兵的拦截页：总控掉线时出现。 */
class BlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
        PagePrompt.acknowledge()
    }

    override fun onPause() {
        isShowing = false
        super.onPause()
    }

    private fun render() {
        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val reason = intent.getStringExtra(EXTRA_REASON)
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
        val ui = Ui(this)
        ui.text("哨兵", 14f, Ui.RED, bold = true)
        ui.text(if (pkg.isBlank()) "总控异常，需要处理" else "总控不在，「$label」先关着", 24f, bold = true)
        if (!reason.isNullOrBlank()) ui.text(reason, 16f, Ui.RED)
        ui.text(
            "总控的无障碍服务停了（系统设置里可能显示“此服务出现故障”），或者总控整个被系统清掉了。" +
                "在它回来之前，拦截名单里的应用由哨兵拦着——掉线不再等于畅玩。\n\n" +
                "修好：在无障碍设置里把「总控」关掉再打开；或者打开一次总控。",
            15f, Ui.MUTED,
        )
        val canWrite = Sys.canWrite(this)
        ui.button(if (canWrite) "一键修复总控" else "去无障碍设置修复", primary = true) {
            if (canWrite && Sys.rebind(this, Sys.mainGuard())) {
                Store.log(this, "在拦截页一键修复")
                Sys.wakeMain(this)
                goHome()
            } else {
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
                finish()
            }
        }
        ui.button("打开总控", primary = false) {
            try { packageManager.getLaunchIntentForPackage(Proto.MAIN_PKG)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } catch (_: Throwable) {}
            finish()
        }
        ui.button("回桌面", primary = false) { goHome() }
        ui.button("紧急暂停本轮监管", primary = false) {
            WatchBridge.emergencyStop(this, "用户在故障处理页按下紧急暂停")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        if (!canWrite) ui.text("\n不想每次手动修：电脑上执行一次\n${Sys.grantCommands()}\n以后哨兵会自己把总控挂回去。", 13f, Ui.FAINT)
        setContentView(ui.scroll())
    }

    private fun goHome() {
        try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
        finish()
    }

    @Deprecated("返回键：回桌面，不回被拦的应用")
    override fun onBackPressed() = goHome()

    companion object {
        const val EXTRA_PKG = "pkg"
        private const val EXTRA_REASON = "reason"
        @Volatile var isShowing = false
            private set
        fun repairIntent(c: Context, reason: String): Intent = intent(c, "")
            .putExtra(EXTRA_REASON, reason)
        fun intent(c: Context, pkg: String): Intent = Intent(c, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_PKG, pkg)
    }
}
