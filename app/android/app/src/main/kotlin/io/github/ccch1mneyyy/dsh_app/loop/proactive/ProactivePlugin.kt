package io.github.ccch1mneyyy.dsh_app.loop.proactive

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.github.ccch1mneyyy.dsh_app.loop.core.BeijingClock
import io.github.ccch1mneyyy.dsh_app.loop.core.CoreCodecPublic
import io.github.ccch1mneyyy.dsh_app.loop.core.ProactiveScheduler
import io.github.ccch1mneyyy.dsh_app.loop.core.SchedulerConfig
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService

/**
 * `dsh/proactive` (methods) + `dsh/proactive/agent` (events): the App side of the agent port.
 *
 * Kotlin → Dart (event stream), one map per HTTP call Dart must make on the current host:
 *   {op: "request", requestId, path: "/dsh-go-proactive/v1/request", body}
 *   {op: "poll",    requestId, path: "/dsh-go-proactive/v1/result?requestId=…&waitMs=20000"}
 *   {op: "cancel",  requestId, path: "/dsh-go-proactive/v1/cancel", body}
 * Dart → Kotlin: `agentHttp {requestId, status, body}` with the raw answer (status 0 = no answer).
 * Dart never interprets the plugin's states: AgentWire does.
 *
 * Methods: `status`, `setConfig` (SchedulerConfig fields), `receipts {limit}`, `agentHttp`,
 * `userActivity` (the person sent a chat message), `tick`.
 */
class ProactivePlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler, AgentPort {
    private var methods: MethodChannel? = null
    private var events: EventChannel? = null
    private var sink: EventChannel.EventSink? = null
    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methods = MethodChannel(binding.binaryMessenger, "dsh/proactive").also { it.setMethodCallHandler(this) }
        events = EventChannel(binding.binaryMessenger, "dsh/proactive/agent").also { it.setStreamHandler(this) }
        // D2: the notification surface must exist before the kernel can pick it, or the gate would
        // report "notifications not allowed" on a phone that has them.
        main.post {
            ProactiveRuntime.ensure(context)
            if (ProactiveRuntime.notificationPort == null) {
                ProactiveRuntime.notificationPort = NotificationPortImpl(context)
                ProactiveRuntime.refreshGate("notification_port")
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        if (ProactiveRuntime.agentPort === this) ProactiveRuntime.agentPort = null
        methods?.setMethodCallHandler(null)
        events?.setStreamHandler(null)
        methods = null
        events = null
    }

    // Dart listens → the port exists; requests made while nobody listens fail as no_transport.
    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        sink = events
        ProactiveRuntime.agentPort = this
        // Dart is now listening: replay whatever the coordinator asked for while it was starting.
        ProactiveRuntime.onAgentPortReady()
    }

    override fun onCancel(arguments: Any?) {
        sink = null
        if (ProactiveRuntime.agentPort === this) ProactiveRuntime.agentPort = null
    }

    override fun request(requestId: String, body: Map<String, Any?>) =
        emit(mapOf("op" to "request", "requestId" to requestId, "path" to "${io.github.ccch1mneyyy.dsh_app.loop.core.AgentWire.PREFIX}/request", "body" to body))

    override fun poll(requestId: String) =
        emit(mapOf("op" to "poll", "requestId" to requestId, "path" to io.github.ccch1mneyyy.dsh_app.loop.core.AgentWire.resultPath(requestId)))

    override fun cancel(requestId: String) =
        emit(mapOf("op" to "cancel", "requestId" to requestId, "path" to "${io.github.ccch1mneyyy.dsh_app.loop.core.AgentWire.PREFIX}/cancel",
            "body" to io.github.ccch1mneyyy.dsh_app.loop.core.AgentWire.cancelBody(requestId)))

    /** A one-off plugin route: the caller supplies the path (sessions read / config write). */
    override fun requestVia(requestId: String, path: String, body: Map<String, Any?>) =
        emit(mapOf("op" to "request", "requestId" to requestId, "path" to path, "body" to body))

    override fun pollVia(requestId: String, path: String) =
        emit(mapOf("op" to "poll", "requestId" to requestId, "path" to path))

    private fun emit(m: Map<String, Any?>) { main.post { sink?.success(m) } }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        ProactiveRuntime.ensure(context)
        when (call.method) {
            "status" -> result.success(status())
            "setConfig" -> {
                val m = call.arguments as? Map<*, *> ?: emptyMap<String, Any?>()
                val merged = ProactiveScheduler.configMap(ProactiveRuntime.schedulerConfig()) + m.entries.associate { it.key.toString() to it.value }
                ProactiveRuntime.setSchedulerConfig(context, ProactiveScheduler.configFrom(merged))
                result.success(ProactiveScheduler.configMap(ProactiveRuntime.schedulerConfig()))
            }
            "receipts" -> result.success(ProactiveRuntime.ensure(context).receipts(call.argument<Int>("limit") ?: 50).map(CoreCodecPublic::event))
            "agentHttp" -> {
                val id = call.argument<String>("requestId")
                if (id == null) { result.success(false); return }
                ProactiveRuntime.onAgentHttp(id, call.argument<Int>("status") ?: 0, call.argument<Any?>("body"))
                result.success(true)
            }
            "userActivity" -> { ProactiveRuntime.onUserActivity(); result.success(true) }
            "tick" -> { ProactiveRuntime.tick("app"); result.success(true) }
            "tickNow" -> { ProactiveRuntime.tick("app_manual", force = true); result.success(true) }
            // P7: the new-install checklist rows open the exact system page they talk about.
            "openCapability" -> { openCapability(call.argument<String>("kind")); result.success(true) }
            // 专注监督 is a native screen reached from the same phone-control settings page.
            "focusControl" -> {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            context,
                            io.github.ccch1mneyyy.dsh_app.loop.focus.FocusControlActivity::class.java,
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                result.success(true)
            }
            // The plugin's own /status and /config, carried over the authenticated connection by
            // Dart. The answer is returned raw: this page only displays sessions, it never decides
            // that one may be used (the plugin refuses missing, archived and busy agents itself).
            "agentSessions" -> ProactiveRuntime.fetchPluginRoute(result, "GET", "/status", null)
            "agentSessionSet" -> ProactiveRuntime.fetchPluginRoute(
                object : MethodChannel.Result {
                    override fun success(value: Any?) {
                        // A new target must not wait for the old "no-session" six-hour alarm.
                        ProactiveRuntime.companionConfigChanged()
                        result.success(value)
                    }
                    override fun error(code: String, message: String?, details: Any?) = result.error(code, message, details)
                    override fun notImplemented() = result.notImplemented()
                },
                "POST", "/config", linkedMapOf("sessionId" to call.argument<String>("sessionId")),
            )
            // 设置页改大肥鱼自己的参数（模式、间隔、每小时/每天上限、安静时段）：原样转给插件 /config。
            "companionConfig" -> {
                val patch = LinkedHashMap<String, Any?>()
                (call.arguments as? Map<*, *>)?.forEach { (k, v) -> if (k is String) patch[k] = v }
                ProactiveRuntime.fetchPluginRoute(
                    object : MethodChannel.Result {
                        override fun success(value: Any?) {
                            // 新的间隔/上限要马上生效，不等旧计划到点。
                            ProactiveRuntime.companionConfigChanged()
                            result.success(value)
                        }
                        override fun error(code: String, message: String?, details: Any?) = result.error(code, message, details)
                        override fun notImplemented() = result.notImplemented()
                    },
                    "POST", "/config", patch,
                )
            }
            // 应用管理页的「Gemini 互动」：开 / 关一场连麦舱。
            "geminiSession" -> {
                ProactiveRuntime.geminiSession(
                    call.argument<String>("action") ?: "end",
                    call.argument<String>("topic"),
                    call.argument<Int>("minutes") ?: 30,
                    call.argument<String>("mode"),
                ) { m -> result.success(m) }
            }
            // 应用管理页的「墙」：只读地看一眼规则现在的执行状态（锁没锁、为什么、本周还剩几天放松额度）。
            // 墙不谈判：这里没有「放松」按钮，放松只能走晚上对账后的申请窗口。
            "wallStatus" -> ProactiveRuntime.fetchPluginRoute(
                result, "POST", "/wall",
                linkedMapOf("action" to "status", "timeZone" to BeijingClock.ZONE.id),
            )
            // 姐妹界面（R4-5）：两张角色卡 + 今日审计面板。只读，放松规则的入口不在这里。
            "sisters" -> ProactiveRuntime.fetchPluginRoute(
                result, "POST", "/sisters",
                linkedMapOf("timeZone" to BeijingClock.ZONE.id),
            )
            // 第八轮 W3：系统分享进来的文字，当成当前这一拍的答案。
            // 没有在跑的局时插件回 no-bout，手机据此退回「新对话」——分享不会被吞掉。
            "boutsShare" -> ProactiveRuntime.fetchPluginRoute(
                result, "POST", "/bouts",
                linkedMapOf("text" to call.argument<String>("text")),
            )
            // 每日四部门提交（第五轮）：信息收集 / 谋划思考 / 行为管理 / 统筹规划。
            // action=status 只读，action=submit 交一段（字数不到插件当场退回）。
            "deposit" -> {
                val body = LinkedHashMap<String, Any?>()
                body["timeZone"] = BeijingClock.ZONE.id
                (call.arguments as? Map<*, *>)?.forEach { (k, v) -> if (k is String && k != "timeZone") body[k] = v }
                ProactiveRuntime.fetchPluginRoute(result, "POST", "/deposit", body)
            }
            // A3：墙的审查记录（只读，GET）。墙不聊天，这里只是把落账的判定倒序摆出来。
            "wallThread" -> ProactiveRuntime.fetchPluginRoute(
                result, "GET", "/wall/thread?timeZone=${BeijingClock.ZONE.id}", null,
            )
            // A3：申诉。只收「一条具体判定 + 新的物理证据」，每天 2 次；判还是由插件里的墙算。
            "wallAppeal" -> {
                val body = LinkedHashMap<String, Any?>()
                body["timeZone"] = BeijingClock.ZONE.id
                (call.arguments as? Map<*, *>)?.forEach { (k, v) -> if (k is String && k != "timeZone") body[k] = v }
                ProactiveRuntime.fetchPluginRoute(result, "POST", "/wall/appeal", body)
            }
            // C1：工作记忆挂载卡。action=status 只读；写操作（open/given/step/park/resume/close/accept）原样透传，
            // 规矩全在插件里（只能是他自己的原话、同时只有一张 open 卡）。GET 只读视图也走这里。
            "mount" -> {
                if (call.argument<String>("action") == null) {
                    ProactiveRuntime.fetchPluginRoute(
                        result, "GET", "/mount?timeZone=${BeijingClock.ZONE.id}", null,
                    )
                } else {
                    val body = LinkedHashMap<String, Any?>()
                    body["timeZone"] = BeijingClock.ZONE.id
                    (call.arguments as? Map<*, *>)?.forEach { (k, v) -> if (k is String && k != "timeZone") body[k] = v }
                    ProactiveRuntime.fetchPluginRoute(result, "POST", "/mount", body)
                }
            }
            // C2：四个基本操作（精简 / 预判 / 提问 / 回想）。不带 answer 只读出什么题与通过率；
            // 带 answer 才算判卷。判的人是谁、什么算过，全在插件里，这里只透传。
            "drillOp" -> {
                if (call.argument<String>("answer") == null) {
                    ProactiveRuntime.fetchPluginRoute(
                        result, "GET", "/drill-op?timeZone=${BeijingClock.ZONE.id}", null,
                    )
                } else {
                    val body = LinkedHashMap<String, Any?>()
                    body["timeZone"] = BeijingClock.ZONE.id
                    (call.arguments as? Map<*, *>)?.forEach { (k, v) -> if (k is String && k != "timeZone") body[k] = v }
                    ProactiveRuntime.fetchPluginRoute(result, "POST", "/drill-op", body)
                }
            }
            // B5：设置页试听。绕开插件的总开关（关了也能试），但不绕过设备状态——
            // 回 null 表示真的念完了，否则是降级原因（no-tts / dnd / muted / session）。
            "voiceTry" -> ProactiveRuntime.tryVoice { why -> result.success(why) }
            else -> result.notImplemented()
        }
    }

    private fun status(): Map<String, Any?> {
        val c = ProactiveRuntime.ensure(context)
        return linkedMapOf(
            "config" to ProactiveScheduler.configMap(ProactiveRuntime.schedulerConfig()),
            "gate" to ProactiveRuntime.gate().let { g -> linkedMapOf("hold" to g.holdReason(), "surface" to g.surface()?.name) },
            "wakeMode" to io.github.ccch1mneyyy.dsh_app.loop.core.WakeMode.of(ProactiveRuntime.caps()).name,
            "alarmError" to WakeAlarm.lastError,
            "transport" to (ProactiveRuntime.agentPort != null),
            "active" to c.active()?.let(CoreCodecPublic::event),
            "queued" to c.queued().size,
            "storageError" to c.storageError,
            "recoveryIssue" to c.recoveryIssue,
            // P7: what a brand-new install still has to turn on before 大肥鱼 can speak.
            "capabilities" to capabilities(),
        )
    }

    private fun capabilities(): Map<String, Any?> {
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(android.app.AlarmManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        return linkedMapOf(
            "a11y" to PhoneA11yService.isConnected(),
            "overlay" to (PhoneA11yService.isConnected() || Settings.canDrawOverlays(context)),
            "notifications" to (nm?.areNotificationsEnabled() == true),
            "exactAlarm" to (Build.VERSION.SDK_INT < 31 || am?.canScheduleExactAlarms() == true),
            "battery" to (pm?.isIgnoringBatteryOptimizations(context.packageName) == true),
        )
    }

    private fun openCapability(kind: String?) {
        val intent = when (kind) {
            "a11y" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            "exactAlarm" -> if (Build.VERSION.SDK_INT >= 31) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
            "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else -> return
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
