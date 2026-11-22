package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop

import android.content.Context
import io.flutter.embedding.engine.FlutterEngine
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.intercept.InterceptPlugin
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.ProactivePlugin

/**
 * Central entry point for registering Dafeiyu Flutter plugins and lifecycle callbacks
 * from upstream [io.github.ccch1mneyyy.dsh_app.MainActivity] with minimal upstream diff.
 */
object DafeiyuEntry {
    fun registerPlugins(engine: FlutterEngine) {
        engine.plugins.add(DafeiyuVoicePlugin())
        engine.plugins.add(LoopPlugin())
        engine.plugins.add(ProactivePlugin())
        engine.plugins.add(InterceptPlugin())
    }

    fun onResume(context: Context) {
        io.github.ccch1mneyyy.dsh_app.runtime.DshService.updateNotification(context)
    }
}
