package io.github.ccch1mneyyy.dsh_app.loop.intercept

import android.content.Context
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptConfig
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptGuard

/**
 * MethodChannel dsh/intercept: the App side of 娱乐 APP 拦截 (round 4, M1).
 *
 *   status        -> { config, counts, receipts, active, storageError, recoveryIssue }
 *   setConfig     -> the same shape, after writing
 *   receipts      -> the last N receipts of 行为拦截账
 *   pause / resume
 *
 * This is a *settings* channel only: the interception itself is decided by [InterceptGuard] from
 * the verified foreground package, never from a page or from Dart. The counters it reports come
 * from [io.github.ccch1mneyyy.dsh_app.loop.core.InterceptLedgerBook], which is a different file from
 * 主动交流账 — no method here can read or move the proactive numbers.
 */
class InterceptPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private var methods: MethodChannel? = null
    private lateinit var context: Context

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methods = MethodChannel(binding.binaryMessenger, "dsh/intercept").also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods?.setMethodCallHandler(null)
        methods = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "status" -> {
                InterceptRuntime.ensure(context)
                result.success(status())
            }
            "setConfig" -> {
                val m = call.arguments as? Map<*, *> ?: emptyMap<String, Any?>()
                val merged = InterceptRuntime.config().toMap() + m.entries.associate { it.key.toString() to it.value }
                InterceptRuntime.setConfig(context, InterceptConfig.fromMap(merged))
                result.success(status())
            }
            "receipts" -> {
                InterceptRuntime.ensure(context)
                result.success(InterceptRuntime.receipts(call.argument<Int>("limit") ?: 50).map { it.toMap() })
            }
            "pause" -> {
                InterceptRuntime.ensure(context)
                result.success(InterceptRuntime.onPaused(null))
            }
            "resume" -> {
                InterceptRuntime.ensure(context)
                InterceptRuntime.setConfig(context, InterceptRuntime.config().copy(pauseUntilWall = 0))
                result.success(status())
            }
            else -> result.notImplemented()
        }
    }

    private fun status(): Map<String, Any?> {
        val g: InterceptGuard? = InterceptRuntime.guardOrNull()
        val book = InterceptRuntime.ledger()
        return linkedMapOf(
            "config" to InterceptRuntime.config().toMap(),
            "counts" to linkedMapOf(
                "triggerCount" to book.triggerCount,
                "blockedCount" to book.blockedCount,
                "userReleasedCount" to book.userReleasedCount,
                "userExitedCount" to book.userExitedCount,
                "pausedCount" to book.pausedCount,
            ),
            "receipts" to InterceptRuntime.receipts(20).map { it.toMap() },
            "active" to g?.openPackage(),
            "storageError" to (g?.storageError),
            "recoveryIssue" to (g?.recoveryIssue),
        )
    }
}
