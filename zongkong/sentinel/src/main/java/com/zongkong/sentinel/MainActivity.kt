package com.zongkong.sentinel

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.zongkong.sentinel.health.HealthStatus
import com.zongkong.sentinel.health.OverallReadiness
import com.zongkong.sentinel.watch.Emergency
import com.zongkong.sentinel.watch.EventProgressFile
import com.zongkong.sentinel.watch.HealthStore
import com.zongkong.sentinel.watch.PolicyStore
import com.zongkong.sentinel.watch.StatusPage
import com.zongkong.sentinel.watch.WatchService

/**
 * 哨兵的首页 = 首页四态 + 统计页（报告 §10，规范 §7）。
 *
 * 四态只从共享内核的 `Readiness` 来，界面不自己润色：
 * 「三体系已就绪」「部分监管失效」「哨兵兜底不可用」「没有生效中的监管窗口」。
 * 三客户端三行 + 哨兵自己一行，逐项显示真实字段；没观测到就写「数据缺失」。
 */
class MainActivity : Activity() {

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        render()
    }

    private fun render() {
        val ui = Ui(this)
        val nowWall = System.currentTimeMillis()
        val nowMono = SystemClock.elapsedRealtime()

        val health = HealthStore(filesDir)
        val policies = PolicyStore(filesDir).load()
        val snapshot = health.snapshot(nowWall)
        val page = StatusPage.build(
            snapshot = snapshot,
            policies = policies.doc.policies,
            nowWall = nowWall,
            nowMono = nowMono,
            selfAccessibilityEnabled = Sys.enabled(this, Sys.selfComponent(this)),
        )

        ui.text("总控哨兵", 26f, bold = true)
        ui.text(
            "独立应用 + 独立 :watch 进程。健康调度与锁屏执行都在 :watch，无障碍线程卡住也照样跑。\n" +
                "边界：整包被系统「强行停止」后，哨兵无法自我保证恢复。",
            13f, Ui.MUTED,
        )

        // ---------- 首页四态 ----------
        val (stateText, stateColor) = when (page.overall) {
            OverallReadiness.ALL_READY -> page.overallText to Ui.GREEN
            OverallReadiness.PARTIAL_FAILURE -> page.overallText to Ui.RED
            OverallReadiness.SENTINEL_UNAVAILABLE -> page.overallText to Ui.RED
            OverallReadiness.NO_ACTIVE_WINDOW -> page.overallText to Ui.MUTED
        }
        ui.text("● $stateText", 20f, stateColor, bold = true)
        ui.text("生效窗口：" + page.activeWindowsText, 13f, Ui.MUTED)

        // ---------- 紧急暂停（与「正常解除」分开） ----------
        val emergency = snapshot.emergency
        if (emergency != null) {
            ui.text("● 紧急暂停中：" + Emergency.describe(emergency, HealthStore.ROWS), 14f, Ui.RED, bold = true)
            ui.button("取消紧急暂停（按规则恢复）", primary = false) {
                WatchService.send(this, WatchService.ACTION_CANCEL_EMERGENCY)
                render()
            }
        } else {
            ui.text("紧急暂停：本地立即生效，不需要网络、不需要模型，也不写分析。用于此时此刻先停下来。", 13f, Ui.MUTED)
        }
        ui.button("紧急暂停本轮锁屏（立即生效）", primary = true) {
            // 本地即时：直接发到 :watch，不依赖网络与模型（报告 §3.6）。
            WatchBridge.emergencyStop(this, "用户在哨兵首页按下紧急暂停")
            android.os.Handler(mainLooper).postDelayed({ render() }, 600)
        }

        // ---------- 统计页：三客户端三行 + 哨兵一行 ----------
        ui.text("\n接入与健康（每行都是独立记录、独立超时）", 16f, bold = true)
        for (row in page.rows) {
            val color = when {
                row.status == HealthStatus.HEALTHY -> Ui.GREEN
                row.status == HealthStatus.UNREGISTERED -> Ui.FAINT
                row.status == HealthStatus.UNKNOWN -> Ui.MUTED
                else -> Ui.RED
            }
            ui.text("${row.title}　${row.statusText}", 16f, color, bold = true)
            ui.text(
                listOf(
                    "版本：${row.versionText}　接入：${row.registeredText}",
                    "权限：${row.permissionText}　最近真实响应：${row.lastAckText}",
                    "事件通道：${row.eventText}",
                    "窗口：${row.windowText}",
                    "最近故障 / 恢复：${row.failureText}",
                    "锁屏：${row.lockText}",
                ).joinToString("\n"),
                12f, Ui.MUTED,
            )
        }

        ui.text("\n锁屏回执", 16f, bold = true)
        ui.text(page.lockSummaryText, 13f, Ui.MUTED)
        ui.text("设备管理员：" + com.zongkong.sentinel.watch.LockExecutor(this).capabilityText(), 13f, Ui.MUTED)

        ui.text("\n统计（没观测到的写「数据缺失」，不计入分母）", 16f, bold = true)
        ui.text(page.statsText, 12f, Ui.FAINT)

        // ---------- 修复与设置 ----------
        ui.text("\n修复", 16f, bold = true)
        if (Sys.canWrite(this)) {
            ui.text("有「写安全设置」权限：确认失联时会串行发一次重绑请求（最多两次，只记「请求已发出」，要等新实例 + 真 ack 才算恢复）。", 13f, Ui.MUTED)
        } else {
            ui.text("没有「写安全设置」权限：哨兵只记录、只提醒，并引导你去无障碍设置手动修复。想让它自己发重绑请求，电脑上执行一次：\n${Sys.grantCommands()}", 13f, Ui.MUTED)
        }
        ui.button("打开无障碍设置", primary = false) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ui.button("打开设备管理员设置", primary = false) {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ui.button("启动 / 唤醒 :watch 进程", primary = false) {
            WatchService.start(this)
            render()
        }
        ui.button("现在测一次锁屏（诊断用，走同一套回执）", primary = false) {
            WatchService.send(this, WatchService.ACTION_LOCK_NOW)
            android.os.Handler(mainLooper).postDelayed({ render() }, 2_500)
        }

        // ---------- 正常解除（保留冷静期，与紧急暂停分开） ----------
        ui.text("\n正常解除（另一条路：手写分析 + 冷静期 + 二次核验）", 16f, bold = true)
        val rel = Store.release(this)
        if (rel != null) {
            val phase = Release.phase(rel, nowWall, nowMono, Store.releaseRule)
            ui.text(
                "● 解除申请（${rel.kind.label}）：" + when (phase) {
                    Release.Phase.COOLING -> "冷静期，还剩 ${Decide.ago(Release.coolingLeft(rel, nowWall, nowMono, Store.releaseRule))}"
                    Release.Phase.VERIFY -> "等二次核验"
                    Release.Phase.GRANTED -> "已解除，到 ${hm(rel.grantedUntil)} 恢复"
                    else -> "已结束"
                },
                14f, if (phase == Release.Phase.GRANTED) Ui.RED else Ui.MUTED, bold = true,
            )
        }
        ui.button(if (rel == null) "解除 / 停用…" else "解除申请 →", primary = false) {
            startActivity(Intent(this, ReleaseActivity::class.java))
        }

        // ---------- 事件通道与文件 ----------
        val ev = EventProgressFile(filesDir).read()
        ui.text(
            "\n事件进度（a11y 进程写 / :watch 读）：" +
                if (ev == null) "还没有记录" else "序号 ${ev.seq}，${((nowMono - ev.atMono).coerceAtLeast(0)) / 1000}s 前，实例 ${ev.instanceId ?: "无"}",
            12f, Ui.FAINT,
        )
        ui.text(
            "策略文件：schema v${policies.doc.schemaVersion}，revision ${policies.doc.revision}，${policies.doc.policies.size} 条规则" +
                (policies.error?.let { "（$it）" } ?: ""),
            12f, Ui.FAINT,
        )

        ui.text("\n版本 ${versionName()}　协议 v${HealthStore.PROTOCOL_VERSION}", 12f, Ui.FAINT)
        ui.text("调试签名不是抗伪造方案；这里只做「同应用边界」的一致性校验。", 12f, Ui.FAINT)

        val log = Store.recent(this, 6) + Arc.recent(this, 8)
        if (log.isNotEmpty()) {
            ui.text("\n最近（无障碍进程 / :watch 进程）", 16f, bold = true)
            ui.text(log.joinToString("\n"), 12f, Ui.FAINT)
        }
        setContentView(ui.scroll())
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun hm(t: Long) = java.time.Instant.ofEpochMilli(t)
        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
        .let { "%02d:%02d".format(it.hour, it.minute) }
}
