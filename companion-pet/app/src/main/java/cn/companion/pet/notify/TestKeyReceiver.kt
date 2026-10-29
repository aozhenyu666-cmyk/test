package cn.companion.pet.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking

/**
 * **仅调试构建**的凭据注入入口。
 *
 * 为什么需要：API Key 走 AndroidKeyStore 加密存储，无法用 adb 直接写 pref 文件；
 * 而自动化验收（模拟器跑完整闭环）必须能配好 Key，不能靠人手点 UI。
 *
 * 安全边界（必须守住）：
 * - 只在 [BuildConfigLike.DEBUG_BUILD] 为真时生效；正式包直接忽略；
 * - 不把 Key 写进日志、不回显、不落盘到明文文件；
 * - 用完可通过 [ACTION_CLEAR] 清掉。
 *
 * 用法：
 * ```
 * adb shell am broadcast -a cn.companion.pet.action.SET_TEST_KEY \
 *     --es key "sk-..." -p cn.companion.pet
 * ```
 */
class TestKeyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != ACTION_SET && action != ACTION_CLEAR) return

        // 调试开关：只有 debug 构建才处理。BuildConfig.DEBUG 在 applicationId 模块里可用。
        val debug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debug) {
            android.util.Log.w(TAG, "ignored: not a debuggable build")
            return
        }

        val app = context.applicationContext as? cn.companion.pet.PetApp ?: return
        if (action == ACTION_CLEAR) {
            runBlocking { app.secrets.clear() }
            app.invalidateKey()
            lastResult = "cleared"
            android.util.Log.i(TAG, "test key cleared")
            return
        }

        val key = intent.getStringExtra(EXTRA_KEY)?.trim().orEmpty()
        if (key.isEmpty()) {
            lastResult = "empty"
            return
        }
        val result = runBlocking { app.secrets.save(key) }
        app.invalidateKey()
        lastResult = result.javaClass.simpleName
        // 只记结果，绝不记 Key 本身。
        android.util.Log.i(TAG, "test key saved: " + lastResult + " readable=" + (app.apiKey() != null))
    }

    companion object {
        const val ACTION_SET = "cn.companion.pet.action.SET_TEST_KEY"
        const val ACTION_CLEAR = "cn.companion.pet.action.CLEAR_TEST_KEY"
        const val EXTRA_KEY = "key"
        const val TAG = "PetTestKey"
        const val ENDPOINT = "cn.companion.pet.action.SET_TEST_ENDPOINT"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_MODEL = "model"

        @Volatile
        var lastResult: String = "not-run"
            private set
    }
}

/** 占位对象，避免直接依赖生成的 BuildConfig（不同模块命名不一致）。 */
internal object BuildConfigLike {
    val DEBUG_BUILD: Boolean get() = false
}
