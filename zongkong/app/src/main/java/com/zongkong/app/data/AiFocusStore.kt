package com.zongkong.app.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.zongkong.core.AiFocusSession
import com.zongkong.core.ZkJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Not part of Config: delayed rule edits, AI tools and ordinary emergency credits cannot erase it. */
class AiFocusStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai_focus", Context.MODE_PRIVATE)
    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private val restored = runCatching {
        prefs.getString("session", null)?.let {
            ZkJson.decodeFromString(AiFocusSession.serializer(), it)
        }?.restore(SystemClock.elapsedRealtime(), boot)
    }
    val restoreFailed: Boolean = restored.isFailure
    private val _session = MutableStateFlow(restored.getOrNull())
    val session = _session.asStateFlow()

    @Synchronized
    fun active(): AiFocusSession? = _session.value?.takeIf { it.remaining(SystemClock.elapsedRealtime()) > 0 }

    @Synchronized
    fun start(minutes: Int, wechat: Boolean, pickers: Set<String>, dialer: String): Boolean {
        if (active() != null) return false
        val duration = minutes.coerceIn(5, 120) * 60_000L
        val next = AiFocusSession(SystemClock.elapsedRealtime() + duration, boot, duration, minutes.coerceIn(5, 120), wechat, pickers, dialer)
        // Publish only after the start survives process death.
        if (!prefs.edit().putString("session", ZkJson.encodeToString(AiFocusSession.serializer(), next)).commit()) return false
        _session.value = next
        return true
    }

    @Synchronized
    fun checkpoint() {
        val s = _session.value ?: return
        val left = s.remaining(SystemClock.elapsedRealtime())
        if (left == 0L) {
            prefs.edit().remove("session").apply()
            _session.value = null
        } else {
            prefs.edit().putString("session", ZkJson.encodeToString(AiFocusSession.serializer(), s.copy(remainingAtSave = left))).apply()
        }
    }
}
