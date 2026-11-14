package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop

import android.os.SystemClock
import io.github.ccch1mneyyy.dsh_app.phone.A11yState
import io.github.ccch1mneyyy.dsh_app.phone.AppMatch
import io.github.ccch1mneyyy.dsh_app.phone.Args
import io.github.ccch1mneyyy.dsh_app.phone.PhoneEnv
import io.github.ccch1mneyyy.dsh_app.phone.PhoneScreen
import io.github.ccch1mneyyy.dsh_app.phone.a11y.NowFeed
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Extra Dafeiyu companion MCP tools (`now`, `usage`, `focus`) served alongside
 * upstream [io.github.ccch1mneyyy.dsh_app.phone.PhoneToolCatalog] without altering
 * upstream schema catalog or triggering PhoneToolSchemaTest assertions.
 */
object DafeiyuPhoneTools {
    const val NOW = "now"
    const val USAGE = "usage"
    const val FOCUS = "focus"

    val NAMES: Set<String> = setOf(NOW, USAGE, FOCUS)

    private const val DEFAULT_USAGE_HOURS = 24
    private const val MAX_USAGE_MINUTES = 7 * 24 * 60
    private const val MAX_USAGE_SWITCHES = 12
    private val SWITCH_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val FOCUS_MODES = setOf("on", "off")

    data class Reply(
        val text: String,
        val error: Boolean = false,
        val summary: String,
        val decision: String? = null,
    )

    private fun strDesc(description: String) =
        linkedMapOf<String, Any?>("type" to "string", "description" to description)

    private fun intDesc(description: String) =
        linkedMapOf<String, Any?>("type" to "integer", "description" to description)

    private fun enumDesc(values: List<String>, description: String) =
        linkedMapOf<String, Any?>("type" to "string", "enum" to values, "description" to description)

    private fun toolJson(
        name: String,
        description: String,
        properties: Map<String, Any?>,
        required: List<String> = emptyList(),
    ): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "description" to description,
        "inputSchema" to linkedMapOf<String, Any?>("type" to "object", "properties" to properties).apply {
            if (required.isNotEmpty()) put("required", required)
            put("additionalProperties", false)
        },
    )

    fun extraToolsJson(): List<Map<String, Any?>> = listOf(
        toolJson(
            USAGE,
            // l10n:keep agent tool catalog
            "读这台手机的使用账。minutes 往回看几分钟（问「这会儿」就用它，比 hours 准；1–10080），hours 往回看几小时" +
                "（默认 24，1–168）。给三样：现在在哪个 App 已连续多久、每个 App 的前台分钟数与打开次数、" +
                "最近的切换序列（新→旧，相邻两条的时刻差就是前一个 App 待了多久）。" +
                "判断走神看的是这个序列：先看当下在做什么，再看切换是不是在两个 App 之间来回跳、" +
                "以及在娱乐 App 上停留了多久——同一个 App 在不同任务里意义不同，" +
                "所以别只看总时长下结论。要「使用情况访问」授权，没授权会告诉用户怎么开。",
            linkedMapOf(
                "minutes" to intDesc("往回看几分钟（1–10080，优先用它）"),
                "hours" to intDesc("往回看几小时（1–168，默认 24）"),
            ),
        ),
        toolJson(
            NOW,
            // l10n:keep agent tool catalog
            "读取手机此刻的状态：前台应用包名、已停留分钟、亮屏、充电。" +
                "判断是否要主动介入前先调它，再把 app / appMinutes 交给关系内核。",
            emptyMap(),
        ),
        toolJson(
            FOCUS,
            // l10n:keep agent tool catalog
            "工作模式：说好计划之后把计划钉住——allow 里的 App 照常用，其余第三方 App 一进就被全屏锁页挡住。" +
                "mode=on 开始（要 allow + minutes）；mode=off 立刻结束；不传 mode 就是查现在还剩多久、允许哪些。" +
                "allow 写包名或应用名，先用 apps 查准。电话、短信、地图、支付、桌面、输入法永远不锁；" +
                "允许名单外的娱乐 App 到点自动解锁，用户也能用拦截页自己放行。",
            linkedMapOf(
                // l10n:keep agent tool catalog
                "mode" to enumDesc(listOf("on", "off"), "on 开始，off 结束"),
                "allow" to linkedMapOf(
                    "type" to "array",
                    "items" to linkedMapOf("type" to "string"),
                    // l10n:keep agent tool catalog
                    "description" to "on：这期间允许使用的 App（包名或应用名），至少一个",
                ),
                // l10n:keep agent tool catalog
                "minutes" to intDesc("on：持续多少分钟（5–720）"),
                // l10n:keep agent tool catalog
                "why" to strDesc("on：为什么锁（锁页上会显示这句，一句话）"),
            ),
        ),
    )

    fun dispatch(name: String, a: Args, allowRaw: Any?, env: PhoneEnv): Reply = when (name) {
        NOW -> now(env)
        USAGE -> usage(a, env)
        FOCUS -> focus(a, allowRaw, env)
        else -> Reply("没有这个手机工具：$name", error = true, summary = name)
    }

    private fun now(env: PhoneEnv): Reply {
        if (env.a11yState() != A11yState.CONNECTED) {
            // l10n:keep agent tool result
            return Reply("无障碍服务没连上，读不到前台应用。先在 设置 → 手机操控 里恢复。", error = true, summary = "读取手机状态")
        }
        runCatching { env.driver(PhoneScreen.MAIN)?.foregroundPackage() }
            .getOrNull()
            ?.let { NowFeed.onApp(it, SystemClock.elapsedRealtime()) }
        val snapshot = NowFeed.snapshot(SystemClock.elapsedRealtime())
        val text = buildString {
            append("前台应用：").append(snapshot.app ?: "未知").append('\n')
            append("已停留：").append(snapshot.appMinutes).append(" 分钟\n")
            append("亮屏：").append(if (snapshot.screenOn) "是" else "否").append('\n')
            append("充电：").append(if (snapshot.charging) "是" else "否")
        }
        return Reply(text, summary = "读取手机状态")
    }

    private fun usage(a: Args, env: PhoneEnv): Reply {
        val minutes = (a.int("minutes") ?: ((a.int("hours") ?: DEFAULT_USAGE_HOURS) * 60))
            .coerceIn(1, MAX_USAGE_MINUTES)
        val zone = env.zone()
        val now = System.currentTimeMillis()
        val report = env.usageReport(minutes, now)
            // l10n:keep agent tool result
            ?: return Reply(
                "读不到使用记录：DSH 没有「使用情况访问」权限。请用户在 系统设置 → 应用 → 特殊权限 → 使用情况访问 里允许 DSH（有的机型叫「有权查看使用情况」），然后重试。",
                error = true,
                summary = "读使用时长",
            )
        val window = if (minutes % 60 == 0) "${minutes / 60} 小时" else "$minutes 分钟"
        if (report.rows.isEmpty() && report.switches.isEmpty()) {
            // l10n:keep agent tool result
            return Reply("最近 $window 没有使用记录。要么真的没怎么用，要么系统刚清过统计。", summary = "读使用时长")
        }
        val text = buildString {
            val current = report.current
            if (current != null && report.currentSince > 0) {
                val label = report.rows.firstOrNull { it.packageName == current }?.label ?: current
                val staying = (now - report.currentSince) / 60_000L
                // l10n:keep agent tool result
                append("现在在：").append(label).append("（").append(current).append("），已连续 ")
                    .append(staying).append(" 分钟\n\n")
            }
            // l10n:keep agent tool result
            append("最近 ").append(window).append("（按前台分钟数排）：\n")
            if (report.rows.isEmpty()) append("（没有满一分钟的记录）\n")
            report.rows.forEach {
                append(it.label ?: it.packageName).append(" · ").append(it.packageName)
                    .append(" · ").append(it.minutes).append(" 分钟 · 打开 ").append(it.opens).append(" 次\n")
            }
            if (report.switches.isNotEmpty()) {
                append("\n最近的切换（新→旧，相邻两条的时刻差就是前一个 App 待了多久）：\n")
                report.switches.take(MAX_USAGE_SWITCHES).forEach {
                    append(Instant.ofEpochMilli(it.at).atZone(zone).format(SWITCH_AT)).append(' ').append(it.packageName).append('\n')
                }
            }
        }.trimEnd()
        return Reply(text, summary = "读使用时长")
    }

    private fun parseStrList(raw: Any?): List<String> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: listOf(raw)
        return list.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }

    private fun focus(a: Args, allowRaw: Any?, env: PhoneEnv): Reply {
        val mode = a.str("mode")?.lowercase()
        // l10n:keep agent tool result
        if (mode != null && mode !in FOCUS_MODES) return Reply("mode 只能是 on 或 off（不传就是查状态）。", error = true, summary = "工作模式")
        val pkgs = LinkedHashSet<String>()
        val missing = ArrayList<String>()
        for (q in parseStrList(allowRaw)) {
            when (val r = AppMatch.resolve(env.system().apps(), q)) {
                is AppMatch.Result.Found -> pkgs += r.app.packageName
                is AppMatch.Result.Ambiguous -> {
                    // l10n:keep agent tool result
                    return Reply(
                        "「$q」对应多个应用，请用包名指定：" + r.candidates.joinToString("；") { "${it.label} · ${it.packageName}" },
                        error = true,
                        summary = "工作模式",
                    )
                }
                AppMatch.Result.NotFound -> missing += q
            }
        }
        if (missing.isNotEmpty()) {
            // l10n:keep agent tool result
            return Reply("没找到应用：" + missing.joinToString("、") + "。用 apps 查一下名称或包名。", error = true, summary = "工作模式")
        }
        val text = env.workMode(mode ?: "", pkgs.toList(), a.int("minutes") ?: 0, a.str("why"))
            // l10n:keep agent tool result
            ?: return Reply("这台手机现在没有工作模式。", error = true, summary = "工作模式")
        return Reply(text, summary = "工作模式")
    }
}
