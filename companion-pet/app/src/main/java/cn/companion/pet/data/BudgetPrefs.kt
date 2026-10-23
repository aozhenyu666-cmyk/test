package cn.companion.pet.data

import android.content.Context
import cn.companion.pet.core.probe.BudgetPersistence

/** 预算计数落盘：和统计分开存，避免 UI 统计口径改动影响预算。 */
class BudgetPrefs(context: Context) : BudgetPersistence {
    private val prefs = context.applicationContext.getSharedPreferences("budget", Context.MODE_PRIVATE)

    override fun load(day: String): Int = prefs.getInt(KEY_PREFIX + day, 0)

    override fun save(day: String, used: Int): Boolean {
        // commit() 同步落盘：预留成功后进程立刻被杀也不会在重启后多出一格。
        return prefs.edit().putInt(KEY_PREFIX + day, used).commit()
    }

    companion object {
        private const val KEY_PREFIX = "used:"
    }
}
