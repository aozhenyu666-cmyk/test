package io.github.ccch1mneyyy.dsh_app.loop.driver

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * `dsh/driver` MethodChannel 的 Kotlin 端。在 MainActivity.configureFlutterEngine 里调用一次：
 *
 *     DriverChannel.bind(this, flutterEngine)
 *
 * Dart → Kotlin：sessionStarted {sessionId, anchors}、directive {…插件指令…}
 * Kotlin → Dart：signal {sessionId, kind, at, …}
 */
object DriverChannel {
    fun bind(context: Context, engine: FlutterEngine) {
        val channel = MethodChannel(engine.dartExecutor.binaryMessenger, "dsh/driver")
        val main = Handler(Looper.getMainLooper())
        DriverRuntime.attach(context, object : DriverRuntime.Port {
            override fun signal(kind: String, fields: Map<String, Any?>) {
                main.post { channel.invokeMethod("signal", fields + ("kind" to kind)) }
            }
        })
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "sessionStarted" -> {
                    val id = call.argument<String>("sessionId")
                    val anchors = call.argument<List<String>>("anchors").orEmpty()
                    if (id == null) result.error("bad-args", "sessionId", null)
                    else { DriverRuntime.onSessionStarted(id, anchors); result.success(true) }
                }
                "directive" -> {
                    @Suppress("UNCHECKED_CAST")
                    val d = call.arguments as? Map<String, Any?>
                    if (d == null) result.error("bad-args", "directive", null)
                    else { DriverRuntime.onDirective(d); result.success(true) }
                }
                else -> result.notImplemented()
            }
        }
    }
}
