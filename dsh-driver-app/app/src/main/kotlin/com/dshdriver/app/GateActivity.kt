package com.dshdriver.app

import android.content.Intent

/** Internal entry: target is selected once for this task and never taken from an exported intent. */
class GateActivity : QuestionActivity() {
    private var target: String? = null
    override fun acceptLaunch(): Boolean {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return false
        val rules = Engine.store.blocking
        if (!rules.enabled || pkg !in rules.packages || pkg in rules.exemptPackages()) return false
        target = pkg
        return true
    }
    override fun passed() {
        val pkg = target ?: return
        val rules = Engine.store.blocking
        if (rules.enabled && pkg in rules.packages) rules.grant(pkg, System.currentTimeMillis())
        packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        finishAndRemoveTask()
    }
    override fun giveUp() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finishAndRemoveTask()
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed() = giveUp()
    companion object { const val EXTRA_PACKAGE = "blocked_package" }
}
