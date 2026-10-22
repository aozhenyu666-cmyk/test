package cn.companion.pet.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 把用户送进官方 Gemini App。只在用户点击或按住说话之后调用。
 *
 * 文字深聊：桥接文本写进剪贴板 → 拉起 Gemini（先试分屏，不行就正常启动）→ 提示粘贴。
 * Live 共看：开场白写进剪贴板 → 拉起 Gemini → 提示用户自己点 Live 和共享屏幕。
 * 目前没有公开、稳定的“直接进入 Live 并共享屏幕”的入口，所以不做任何自动操作。
 */
class GeminiLauncher(private val context: Context) {

    enum class Outcome { LAUNCHED, NOT_INSTALLED, FAILED }

    fun isInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    fun copy(label: String, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun launch(pkg: String, adjacent: Boolean = true): Outcome {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return if (isInstalled(pkg)) Outcome.FAILED else Outcome.NOT_INSTALLED
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (adjacent) {
            // 只有在已经处于分屏/多窗口时才会生效；全屏时系统忽略这个标志，正常启动
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }
        return try {
            context.startActivity(intent)
            Outcome.LAUNCHED
        } catch (e: Exception) {
            if (adjacent) launch(pkg, adjacent = false) else Outcome.FAILED
        }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
