package com.zongkong.app.link

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.os.bundleOf
import com.zongkong.core.Link
import com.zongkong.core.YishouStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 总控这边读一手的状态、把行动交给一手。一手没装、太旧或签名不对时如实说。 */
object YishouClient {
    private val uri: Uri = Uri.parse("content://${Link.YISHOU_AUTHORITY}")

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Err(val message: String) : Result<Nothing>
    }

    fun installed(context: Context) = Partner.installed(context)

    suspend fun status(context: Context): Result<YishouStatus> = withContext(Dispatchers.IO) {
        call(context, Link.STATUS, null) { b ->
            @Suppress("DEPRECATION")
            YishouStatus.fromMap(b.keySet().associateWith { b.get(it) })
        }
    }

    /** 一手 since 之后的每一手（刷题、刷着练、线程……）。一手 0.25 起才有；旧版一手回「不认识」。 */
    suspend fun ledger(context: Context, since: Long): Result<List<com.zongkong.core.YishouEntry>> = withContext(Dispatchers.IO) {
        call(context, Link.LEDGER, bundleOf("since" to since)) { b ->
            com.zongkong.core.YishouLedger.decode(
                b.getLongArray("at"), b.getStringArray("kind"), b.getStringArray("kindLabel"), b.getStringArray("mark"),
                b.getStringArray("what"), b.getStringArray("said"), b.getStringArray("note"),
            )
        }
    }

    /** 交给一手陪练：新建或更新一手的当前任务。返回一手的回话。 */
    suspend fun setTask(context: Context, order: Map<String, String>): Result<String> = withContext(Dispatchers.IO) {
        call(context, Link.SET_TASK, bundleOf(*order.toList().toTypedArray())) { b -> b.getString(Link.OK).orEmpty() }
    }

    /** 让一手现在开一局陪练窗口（默认长度按一手的设置），再打开一手的窗口页。 */
    suspend fun startWindow(context: Context): Result<String> = withContext(Dispatchers.IO) {
        call(context, Link.START_WINDOW, null) { b -> b.getString(Link.OK).orEmpty() }
    }

    fun openWindow(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(Link.YISHOU) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("open", "window"))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun <T> call(context: Context, method: String, extras: android.os.Bundle?, read: (android.os.Bundle) -> T): Result<T> {
        if (!Partner.installed(context)) return Result.Err("没装一手")
        if (!Partner.yishouGenuine(context)) return Result.Err("手机上的一手不是我们打包的那个（签名不对），不连接")
        return try {
            val b = com.zongkong.app.system.SafeCall.call(context.contentResolver, uri, method, null, extras)
                ?: return Result.Err("一手没有回应：把一手更新到 0.8 以上")
            b.getString(Link.ERROR)?.let { return Result.Err("一手拒绝了：$it") }
            Result.Ok(read(b))
        } catch (e: IllegalArgumentException) {
            Result.Err("一手版本太旧，还没有连接功能：更新到 0.8 以上")
        } catch (e: Exception) {
            Result.Err("连一手出错：${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun open(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(Link.YISHOU) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    const val DOWNLOAD = "https://github.com/aozhenyu666-cmyk/test/releases/download/yishou-latest/yishou.apk"
}
