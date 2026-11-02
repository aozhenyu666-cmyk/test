package com.yishou.app.focus

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FocusDefaults(val workMin: Int = 6, val restMin: Int = 1, val messaging: Set<String> = FocusRules.DEFAULT_MESSAGING)

class FocusSettings(context: Context) {
    private val prefs = context.getSharedPreferences("focus_defaults", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(FocusDefaults(prefs.getInt("work", 6).coerceIn(3, 60), prefs.getInt("rest", 1).coerceIn(0, 15),
        prefs.getStringSet("messaging", FocusRules.DEFAULT_MESSAGING).orEmpty().toSet()))
    val state = _state.asStateFlow()
    fun save(value: FocusDefaults) {
        val p = value.copy(workMin = value.workMin.coerceIn(3, 60), restMin = value.restMin.coerceIn(0, 15))
        prefs.edit().putInt("work", p.workMin).putInt("rest", p.restMin).putStringSet("messaging", p.messaging).apply()
        _state.value = p
    }
}
