package com.zongkong.app.guard

import android.content.Context
import com.zongkong.core.guard.IntensityLevel
import com.zongkong.core.guard.IntensityRules
import java.time.LocalDate

/**
 * 当天的强度账：漏了几次、做到几次。跨天自动清零。
 *
 * 只存两个计数，不存任何回答内容。
 */
class IntensityStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("intensity", Context.MODE_PRIVATE)

    private fun key(name: String) = "$name:${LocalDate.now()}"

    fun misses(): Int = prefs.getInt(key("misses"), 0)

    fun passes(): Int = prefs.getInt(key("passes"), 0)

    /** 现在这一档。 */
    fun level(): IntensityLevel = IntensityRules.levelFor(misses(), passes())

    fun onMiss() {
        prefs.edit().putInt(key("misses"), misses() + 1).apply()
    }

    fun onPass() {
        prefs.edit().putInt(key("passes"), passes() + 1).apply()
    }
}
