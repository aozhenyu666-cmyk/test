package com.dshdriver.app

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.dshdriver.core.BlockRules

/** SharedPreferences is used for settings read by services and UI; grants survive process death. */
class Blocking(private val context: Context) {
    private val prefs = context.getSharedPreferences("blocking", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var packages: Set<String>
        get() = prefs.getStringSet("packages", emptySet()).orEmpty().toSet()
        set(value) { prefs.edit().putStringSet("packages", value.toSet()).apply() }
    var minutes: Int
        get() = prefs.getInt("minutes", 10).coerceIn(1, 120)
        set(value) { prefs.edit().putInt("minutes", value.coerceIn(1, 120)).apply() }
    fun expiry(pkg: String) = prefs.getLong("grant:$pkg", 0L)
    fun grant(pkg: String, now: Long) { prefs.edit().putLong("grant:$pkg", now + minutes * 60_000L).apply() }
    fun exemptPackages(): Set<String> {
        val ime = context.getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }
        val home = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }
        val settings = context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
        return (ime + home + listOfNotNull(settings) + listOf(context.packageName, "android", "com.android.systemui", "com.android.settings")).toSet()
    }
    fun decide(pkg: String?, now: Long, exemptions: Set<String>): BlockRules.Decision {
        if (!enabled) return BlockRules.Decision.Allow
        return BlockRules(packages, if (pkg != null) mapOf(pkg to expiry(pkg)) else emptyMap(), context.packageName,
            systemUi = exemptions).decide(pkg, now)
    }
}
