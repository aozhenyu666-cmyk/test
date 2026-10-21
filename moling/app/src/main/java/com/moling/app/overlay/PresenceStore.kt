package com.moling.app.overlay

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.moling.core.mind.Opening

@Serializable
data class ActiveSession(val opening: Opening, val startedAt: Long, val index: Int = 0, val presentMs: Long = 0, val leaves: Int = 0)

class PresenceStore(context: Context) {
    val prefs = context.applicationContext.getSharedPreferences("presence", Context.MODE_PRIVATE)
    fun enabled(key: String) = prefs.getBoolean(key, true)
    fun set(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    fun session(): ActiveSession? = runCatching { Json.decodeFromString<ActiveSession>(prefs.getString("session", null) ?: return null) }.getOrNull()
    fun session(value: ActiveSession?) {
        prefs.edit().apply { if (value == null) remove("session") else putString("session", Json.encodeToString(ActiveSession.serializer(), value)) }.apply()
    }
}
