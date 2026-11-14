package io.github.ccch1mneyyy.dsh_app.loop.guardian

import android.app.Activity
import android.os.Bundle
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.runtime.DshService

/**
 * Translucent foreground-context trampoline used by `大肥鱼哨兵` when Android 12+
 * restricts background `startForegroundService` and an explicit user tap / modal wake
 * is bringing DSH Go's engine back up.
 *
 * Verifies the paired guardian cert/token before invoking [DshService.ensureEngine].
 */
class EngineWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (GuardianPairing.authenticateWakeIntent(this, intent)) {
            val res = DshService.ensureEngine(this)
            Log.i(TAG, "guardian wake ensured engine: ${res.status}")
        } else {
            Log.w(TAG, "rejected unauthenticated wake intent")
        }
        finish()
    }

    companion object {
        private const val TAG = "FishEngineWake"
    }
}
