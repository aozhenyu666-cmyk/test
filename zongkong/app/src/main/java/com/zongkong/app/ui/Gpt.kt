package com.zongkong.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * 去 ChatGPT。ChatGPT App 没有公开的“带一段话打开新对话”的接口，所以：
 *  - 复制 + 打开 App：一定可用，进去长按输入框粘贴
 *  - 分享给 ChatGPT：系统分享，文字会出现在 ChatGPT 的输入框里（取决于 ChatGPT 版本是否接收分享）
 *  - 网页版：chatgpt.com/?q= 会把文字填进输入框（太长的话截断）
 * 都不会替你自动发送。
 */
object Gpt {
    const val PKG = "com.openai.chatgpt"
    const val NOTION_PKG = "notion.id"

    fun installed(context: Context): Boolean = context.packageManager.getLaunchIntentForPackage(PKG) != null

    fun copy(context: Context, text: String, label: String = "总控") {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun readClipboard(context: Context): String =
        context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()

    fun openApp(context: Context): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(PKG) ?: return false
        return try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) {
            false
        }
    }

    private fun sendIntent(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    /** ChatGPT 是否接收文字分享。 */
    fun canShareTo(context: Context): Boolean =
        context.packageManager.queryIntentActivities(sendIntent("x").setPackage(PKG), 0).isNotEmpty()

    fun shareTo(context: Context, text: String): Boolean = try {
        context.startActivity(sendIntent(text).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) {
        false
    }

    fun shareAny(context: Context, text: String) {
        context.startActivity(Intent.createChooser(sendIntent(text), "发给…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openWeb(context: Context, text: String) {
        val q = URLEncoder.encode(text.take(1800), "UTF-8")
        safeStart(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/?q=$q")))
    }

    fun openNotion(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) {
            false
        }
    }
}
