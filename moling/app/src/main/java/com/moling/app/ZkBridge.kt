package com.moling.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.moling.core.ZkPort
import com.moling.core.link.ZK_AUTHORITY
import com.moling.core.link.ZK_COMMAND
import com.moling.core.link.ZK_RESULT
import com.moling.core.link.ZK_STATUS
import com.moling.core.link.ZkCommand
import com.moling.core.link.ZkReceipt
import com.moling.core.link.ZkResult
import com.moling.core.link.ZkStatus
import com.moling.core.link.ZkWire

/*
 * 墨灵 → 总控 的通道（M5）：走总控的 ContentProvider.call。
 *
 * 总控只认墨灵的包名 + 调试签名（和总控 ↔ 一手的连接同一种做法）。
 * 总控没装、被杀、版本太旧、签名不认——一律返回 null，绝不让墨灵崩：
 * 大脑会把它显示成“没送到总控”，用户可以稍后重试。
 */
object ZkBridge : ZkPort {
    private const val TAG = "ZkBridge"

    @Volatile
    private var app: Context? = null

    private val uri = Uri.parse("content://" + ZK_AUTHORITY)

    fun init(context: Context) {
        app = context.applicationContext
    }

    override fun status(): ZkStatus? {
        val ctx = app ?: return null
        val b = runCatching { ctx.contentResolver.call(uri, ZK_STATUS, null, null) }
            .onFailure { Log.w(TAG, "读总控状态失败", it) }
            .getOrNull() ?: return null
        return runCatching {
            val map = toMap(b)
            // 总控报错（例如签名不认）时只有一个 error 键：当作连不上，保留上一次的状态。
            if ("error" in map) null else ZkWire.status(map)
        }.onFailure { Log.w(TAG, "解析总控状态失败", it) }.getOrNull()
    }

    override fun send(command: ZkCommand): ZkReceipt? {
        val ctx = app ?: return null
        val b = runCatching { call(ctx, command) }
            .onFailure { Log.w(TAG, "给总控发指令失败", it) }
            .getOrNull() ?: return null
        return runCatching { ZkWire.receipt(toMap(b), System.currentTimeMillis()) }
            .onFailure { Log.w(TAG, "解析总控回执失败", it) }
            .getOrNull()
    }

    /** 交过去的一条后来怎么样了（M13）；总控没装 / 被杀 / 不认签名都返回 null。 */
    override fun result(id: String): ZkResult? {
        val ctx = app ?: return null
        val b = runCatching { ctx.contentResolver.call(uri, ZK_RESULT, id, null) }
            .onFailure { Log.w(TAG, "问总控结局失败", it) }
            .getOrNull() ?: return null
        return runCatching { ZkWire.result(toMap(b)) }
            .onFailure { Log.w(TAG, "解析总控结局失败", it) }
            .getOrNull()
    }

    /** 指令 → Bundle（约定里全是字符串）→ 总控。 */
    private fun call(ctx: Context, command: ZkCommand): Bundle? {
        val extras = Bundle()
        ZkWire.toMap(command).forEach { (k, v) -> extras.putString(k, v) }
        return ctx.contentResolver.call(uri, ZK_COMMAND, null, extras)
    }

    /** Bundle → 键值对（每个键 getString，null 当空串）。 */
    private fun toMap(b: Bundle): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        b.keySet().forEach { k -> out[k] = b.getString(k).orEmpty() }
        return out
    }
}