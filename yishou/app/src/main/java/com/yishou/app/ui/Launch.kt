package com.yishou.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yishou.app.outside.AiApp
import com.yishou.app.system.ExternalGrace

/**
 * 去别的 App：
 * - 出题：装了这个 AI App，就把出题要求直接“分享”进去（打开就是填好的输入框）；分享不进去就打开 App，要求放在剪贴板；没装就打开网页。
 * - 刷题：直接打开粉笔这类 App。
 * 返回值是给用户看的一句话。
 */
object Launch {

    fun installed(context: Context, pkg: String?): Boolean =
        pkg != null && context.packageManager.getLaunchIntentForPackage(pkg) != null

    fun sendPrompt(context: Context, app: AiApp, prompt: String): String {
        copy(context, prompt)
        ExternalGrace.open()
        val pkg = app.pkg
        if (pkg != null && installed(context, pkg)) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, prompt).setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (send.resolveActivity(context.packageManager) != null && start(context, send)) return "已经把出题要求发进 ${app.name}，在那边点发送"
            context.packageManager.getLaunchIntentForPackage(pkg)?.let { if (start(context, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return "打开了 ${app.name}，出题要求在剪贴板里，粘贴发送" }
        }
        val url = app.url ?: return "没装 ${app.name}，也没有网址。出题要求已复制"
        return if (start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            "没装 ${app.name} App，打开了网页版。出题要求在剪贴板里，粘贴发送"
        } else {
            "打不开 ${app.name}。出题要求已复制"
        }
    }

    fun open(context: Context, app: AiApp): Boolean {
        ExternalGrace.open()
        val pkg = app.pkg
        if (pkg != null) context.packageManager.getLaunchIntentForPackage(pkg)?.let { if (start(context, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return true }
        val url = app.url ?: return false
        return start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copy(context: Context, text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("一手", text))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
