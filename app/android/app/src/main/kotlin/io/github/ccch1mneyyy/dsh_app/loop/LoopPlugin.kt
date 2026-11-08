package io.github.ccch1mneyyy.dsh_app.loop

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService
import java.util.concurrent.Executors

/**
 * 设置 → 手机操控 → 触发测试: MethodChannel `dsh/loop` (lib/loop/loop_channel.dart).
 *
 * - `status` → `{config, a11yEnabled, a11yConnected, eventsSeen, readings, lastForeground,
 *   storageError, recoveredFrom, insideTarget, triggersToday, triggerDay, diagnostics}` —
 *   `diagnostics` is the map from [LoopDiagnostics.toMap].
 * - `setConfig` `{enabled?, targetPackage?, cooldownMs?, timeoutMs?, quietStartMinute?,
 *   quietEndMinute?, dailyCap?}` → config
 * - `events` `{limit}` → receipts, newest first
 * - `clearEvents` → true
 * - `clearDiagnostics` → true
 * - `apps` → launchable apps `[{package, label}]` (DSH itself left out)
 */
class LoopPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private var methods: MethodChannel? = null
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-loop-plugin").apply { isDaemon = true } }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methods = MethodChannel(binding.binaryMessenger, "dsh/loop").also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods?.setMethodCallHandler(null)
        methods = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "status" -> async(result) { status() }
            "setConfig" -> async(result) {
                val engine = LoopRuntime.engine(context)
                val cur = engine.config()
                val args = call.arguments as? Map<*, *>
                val next = cur.copy(
                    enabled = args?.get("enabled") as? Boolean ?: cur.enabled,
                    targetPackage = if (args?.containsKey("targetPackage") == true) args["targetPackage"] as? String else cur.targetPackage,
                    cooldownMs = (args?.get("cooldownMs") as? Number)?.toLong() ?: cur.cooldownMs,
                    timeoutMs = (args?.get("timeoutMs") as? Number)?.toLong() ?: cur.timeoutMs,
                    // Quiet hours: an explicit key present with null clears that end of the window.
                    quietStartMinute = if (args?.containsKey("quietStartMinute") == true) (args["quietStartMinute"] as? Number)?.toInt() else cur.quietStartMinute,
                    quietEndMinute = if (args?.containsKey("quietEndMinute") == true) (args["quietEndMinute"] as? Number)?.toInt() else cur.quietEndMinute,
                    dailyCap = (args?.get("dailyCap") as? Number)?.toInt() ?: cur.dailyCap,
                )
                engine.setConfig(next).toMap().also { _ ->
                    if (next.enabled) runCatching { InteractionHub.ensureChannel(context) }
                    main.post { LoopRuntime.configChanged() }
                }
            }
            "events" -> async(result) {
                LoopRuntime.engine(context).events(call.argument<Int>("limit") ?: 50).map { it.toMap() }
            }
            "clearEvents" -> async(result) { LoopRuntime.engine(context).clearEvents(); true }
            "clearDiagnostics" -> async(result) { LoopRuntime.diagnostics.clear(); true }
            "apps" -> async(result) { apps() }
            else -> result.notImplemented()
        }
    }

    private fun status(): Map<String, Any?> {
        val engine = LoopRuntime.engine(context)
        val (day, spent) = engine.dailyUsage()
        return linkedMapOf(
            "config" to engine.config().toMap(),
            "a11yEnabled" to PhoneA11yService.isEnabled(context),
            "a11yConnected" to PhoneA11yService.isConnected(),
            "eventsSeen" to LoopRuntime.eventsSeen,
            "readings" to LoopRuntime.readings,
            "lastForeground" to LoopRuntime.lastForeground,
            "storageError" to engine.storageError,
            "recoveredFrom" to engine.recoveredFrom,
            "insideTarget" to engine.trackerState().insideTarget,
            "triggersToday" to spent,
            "triggerDay" to day,
            "diagnostics" to LoopRuntime.diagnostics.toMap(),
        )
    }

    private fun apps(): List<Map<String, Any?>> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .map { (pkg, label) -> linkedMapOf("package" to pkg, "label" to label) }
    }

    private fun async(result: MethodChannel.Result, block: () -> Any?) {
        io.execute {
            val value = runCatching(block)
            main.post {
                value.fold(
                    onSuccess = { result.success(it) },
                    onFailure = { result.error("loop_failed", it.message ?: it.javaClass.simpleName, null) },
                )
            }
        }
    }
}
