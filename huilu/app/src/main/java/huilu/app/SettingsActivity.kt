package huilu.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import huilu.core.Level

/**
 * 能力与权限：系统必须清楚自己的边界。
 * 每一项都显示"现在能不能做"，以及不能做时会发生什么。
 */
class SettingsActivity : Activity() {
    private val app get() = App.of(this)

    override fun onResume() {
        super.onResume()
        app.onDeviceChange = { render() }
        render()
    }

    override fun onPause() {
        app.onDeviceChange = null
        super.onPause()
    }

    private fun render() {
        val p = app.prefs
        page {
            text("能力边界", 22f, bold = true, top = 8)
            text("系统只会尝试当前真正可用的能力；每次干预后都会回头确认它是否真的发生。", 13f, C.MUTED)

            cap("看到前台 App", app.platform.hasUsageAccess(),
                "能发现偏离，能给出「计划 vs 实际」。", "只能依靠你的自报，检查里显示「无法观察」。") {
                startActivity(Intent(SysSettings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            val notif = getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
            cap("通知", notif, "可以主动找你、常驻显示当前行动。", "系统无法主动出现，闭环会断。") {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                else startActivity(Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, packageName))
            }
            cap("精确闹钟", app.canExactAlarm(), "进程被系统杀掉后仍能准时回来检查。", "后台被杀时，检查可能推迟几分钟。") {
                if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(SysSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
            val pm = getSystemService(PowerManager::class.java)
            cap("不受电池优化限制", pm.isIgnoringBatteryOptimizations(packageName), "后台服务不容易被杀。",
                "国产系统上还需要在系统设置里允许「自启动」「后台运行」。") {
                startActivity(Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            cap("弹出检查页（${Level.INTERRUPT.label}）", Level.INTERRUPT in app.platform.available(),
                "偏离时可以直接把检查页放到你面前。", "只能发通知；需要「显示在其他应用上层」权限。") {
                startActivity(Intent(SysSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            val shizuku = ShizukuClient.state(this@SettingsActivity)
            val device = app.device.unavailableReason()
            cap("送回桌面 / 屏蔽（${Level.HOME.label}）", GuardService.instance != null || device == null,
                if (GuardService.instance != null) "通过无障碍执行。" else "通过 Shizuku 模拟 HOME 键执行。",
                "需要无障碍或 Shizuku 其中之一。无障碍：在系统无障碍设置里打开「回路」；Android 13+ 侧载安装时，先到 应用信息 → 右上角菜单 → 允许受限制的设置。") {
                startActivity(Intent(SysSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            card {
                val ok = device == null
                text((if (ok) "✓ " else "✗ ") + "暂停娱乐 App（Shizuku）", 15f, if (ok) C.ACCENT else C.WARN, bold = true)
                text(if (ok) "在允许强干预的轮次里，第 4 次偏离后用 pm suspend 暂停娱乐 App，本轮结束、休息或静音时自动解除。" +
                    "以 ${if (ShizukuClient.uid == 0) "root" else "shell"} 身份运行。"
                    else "$device。没有它时，最强一级退回为「本轮屏蔽娱乐 App」（反复送回桌面）。", 13f, C.MUTED)
                when (shizuku) {
                    ShizukuClient.State.NOT_INSTALLED -> button("下载 Shizuku") {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                    }
                    ShizukuClient.State.NOT_RUNNING -> button("打开 Shizuku 启动服务") {
                        packageManager.getLaunchIntentForPackage(ShizukuClient.MANAGER)?.let { startActivity(it) }
                    }
                    ShizukuClient.State.NO_PERMISSION -> button("授权回路", primary = true) {
                        if (!ShizukuClient.requestPermission()) Toast.makeText(this@SettingsActivity, "请求失败，请先确认 Shizuku 正在运行", Toast.LENGTH_LONG).show()
                    }
                    ShizukuClient.State.READY -> {}
                }
                val locked = app.engine.situation.locked
                if (locked.isNotEmpty()) text("当前暂停中：" + locked.joinToString("、") { app.platform.label(it) }, 14f, C.WARN, top = 8)
                if (ok) button("立即解除所有暂停") {
                    app.releaseAll { Toast.makeText(this@SettingsActivity, it, Toast.LENGTH_LONG).show(); render() }
                }
            }

            title("娱乐 App（偏离的判断依据）")
            text(p.distractors.map { app.platform.label(it) }.sorted().joinToString("、").ifBlank { "（空）" }, 14f)
            button("编辑娱乐 App 列表") { pickApps("哪些 App 算偏离", p.distractors) { p.distractors = it; render() } }

            title("节奏")
            val drift = edit("娱乐 App 连续多少秒算偏离", p.driftSec.toString(), number = true)
            val re = edit("多少分钟没回应就再问一次", p.renotifyMin.toString(), number = true)
            val give = edit("多少分钟没回应就按未回答处理", p.giveUpMin.toString(), number = true)
            text("依次为：偏离阈值（秒）、再次提醒（分钟）、放弃等待（分钟）", 12f, C.MUTED)
            text("最高干预强度：${p.maxLevel.label}", 14f, top = 8)
            Level.values().toList().chunked(3).forEach { chunk ->
                row { chunk.forEach { l -> button(l.label, primary = l == p.maxLevel, weight = 1f) { p.maxLevel = l; render() } } }
            }
            text("没勾选「允许强干预」的轮次，最多只到「${Level.INTERRUPT.label}」。", 12f, C.MUTED)
            button("保存节奏", primary = true) {
                drift.text.toString().toIntOrNull()?.let { p.driftSec = it }
                re.text.toString().toIntOrNull()?.let { p.renotifyMin = it }
                give.text.toString().toIntOrNull()?.let { p.giveUpMin = it }
                Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                render()
            }

            title("AI 判断层（可选）")
            text("只在你在检查里写了一句话、而不是点按钮时调用，用来把这句话理解成下一步。其余判断都由本地规则完成，不联网。", 13f, C.MUTED)
            val on = CheckBox(this@SettingsActivity).apply { text = "启用"; isChecked = p.llmEnabled }
            addView(on)
            val base = edit("接口地址（OpenAI 兼容）", p.llmBase)
            val model = edit("模型", p.llmModel)
            val key = edit("API Key（只存在本机）", p.llmKey)
            row {
                button("保存", primary = true, weight = 1f) {
                    p.llmEnabled = on.isChecked; p.llmBase = base.text.toString(); p.llmModel = model.text.toString(); p.llmKey = key.text.toString()
                    Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                }
                button("测试连接", weight = 1f) {
                    Judge.test(base.text.toString().trim().trimEnd('/'), model.text.toString().trim(), key.text.toString().trim()) {
                        Toast.makeText(this@SettingsActivity, it, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun LinearLayout.cap(name: String, ok: Boolean, yes: String, no: String, fix: () -> Unit) = card {
        text((if (ok) "✓ " else "✗ ") + name, 15f, if (ok) C.ACCENT else C.WARN, bold = true)
        text(if (ok) yes else no, 13f, C.MUTED)
        if (!ok) button("去开启") {
            try { fix() } catch (e: Exception) { Toast.makeText(this@SettingsActivity, "这台设备上打不开对应设置页", Toast.LENGTH_LONG).show() }
        }
    }
}
