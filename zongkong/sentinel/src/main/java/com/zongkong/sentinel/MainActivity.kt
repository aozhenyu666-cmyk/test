package com.zongkong.sentinel

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** 哨兵自己的页面：哨兵开没开、总控在不在、能不能自己修、最近发生了什么。 */
class MainActivity : Activity() {
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        render()
    }

    private fun render() {
        val ui = Ui(this)
        val now = System.currentTimeMillis()
        val selfOn = Sys.enabled(this, Sys.selfComponent(this))
        val running = SentinelService.running
        val installed = Sys.mainInstalled(this)
        val st = Store.state(this)
        val gone = Decide.downFor(st, now)

        ui.text("总控哨兵", 26f, bold = true)
        ui.text("独立于总控的另一个应用。总控崩了、被系统停掉、被清理，哨兵都不受影响：它接着拦娱乐应用，并把总控叫回来。", 14f, Ui.MUTED)

        val (selfText, selfColor) = when {
            running -> "● 哨兵在岗" to Ui.GREEN
            selfOn -> "● 哨兵开着，但系统没让它跑起来——去无障碍设置里关掉再打开" to Ui.RED
            else -> "● 哨兵没开——去无障碍设置里打开「总控哨兵」" to Ui.RED
        }
        ui.text(selfText, 16f, selfColor, bold = true)

        val (mainText, mainColor) = when {
            !installed -> "● 没装总控：哨兵没东西可守" to Ui.FAINT
            st == null -> "● 总控还没连上哨兵：打开一次总控就好" to Ui.FAINT
            gone == null -> "● 总控在线（${Decide.ago(now - st!!.at)}前的心跳）" to Ui.GREEN
            else -> "● 总控不在：${if (gone == 0L) "它下线时说了一声" else "心跳停了 ${Decide.ago(gone)}"}。拦截名单里的应用由哨兵拦着" to Ui.RED
        }
        ui.text(mainText, 16f, mainColor, bold = true)
        val n = Store.takeovers(this)
        if (n > 0) ui.text("接管过 $n 次，最近一次：${Decide.ago(now - Store.lastTakeover(this))}前", 14f, Ui.MUTED)
        st?.let { ui.text("拦截名单 ${it.blocked.size} 个应用${if (now < it.freeUntil) "（休假 / 紧急放行中，先不拦）" else ""}", 14f, Ui.MUTED) }

        ui.button("打开无障碍设置", primary = !running) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (installed && gone != null && Sys.canWrite(this)) ui.button("一键修复总控", primary = true) {
            if (Sys.rebind(this, Sys.mainGuard())) Store.log(this, "在哨兵页一键修复")
            Sys.wakeMain(this)
            render()
        }
        if (installed) ui.button("打开总控", primary = false) {
            packageManager.getLaunchIntentForPackage(Proto.MAIN_PKG)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        // 自律体：解除 / 停用总控都要走闸门（0.15）
        val rel = Store.release(this)
        if (rel != null) {
            val phase = Release.phase(rel, now, android.os.SystemClock.elapsedRealtime(), Store.releaseRule)
            ui.text("● 解除申请（${rel.kind.label}）：" + when (phase) {
                Release.Phase.COOLING -> "冷静期，还剩 ${Decide.ago(Release.coolingLeft(rel, now, android.os.SystemClock.elapsedRealtime(), Store.releaseRule))}"
                Release.Phase.VERIFY -> "等二次核验"
                Release.Phase.GRANTED -> "已解除，到 ${hm(rel.grantedUntil)} 恢复"
                else -> "已结束"
            }, 15f, if (phase == Release.Phase.GRANTED) Ui.RED else Ui.MUTED, bold = true)
        }
        ui.button(if (rel == null) "解除 / 停用总控…" else "解除申请 →", primary = false) {
            startActivity(Intent(this, ReleaseActivity::class.java))
        }
        if (DeviceLock.isOwner(this)) ui.text("● 防卸载开着（设备所有者）：卸载 / 停用都得先走解除闸门。", 13f, Ui.GREEN)
        else ui.text(
            "防卸载没开：普通安装挡不住卸载。想真正锁死（卸载、停用都得走解除闸门），在一台没登录账号的手机上用电脑执行一次：\n${DeviceLock.setupOwnerCommand()}",
            13f, Ui.MUTED,
        )

        ui.text("\n自动修复", 16f, bold = true)
        ui.text(
            if (Sys.canWrite(this)) "有“写安全设置”权限：总控掉了，哨兵会自己把它挂回去（两分钟试一次）。"
            else "没有“写安全设置”权限：哨兵只拦、只提醒。想让它自己修，电脑连上手机执行一次：\n${Sys.grantCommands()}",
            14f, Ui.MUTED,
        )
        ui.text("\n厂商后台管理（vivo / OPPO / 小米等）：把「总控哨兵」设成允许后台运行、关闭电池优化、锁在最近任务里，它就更不容易被清。", 14f, Ui.MUTED)

        val log = Store.recent(this, 15)
        if (log.isNotEmpty()) {
            ui.text("\n最近", 16f, bold = true)
            ui.text(log.joinToString("\n"), 13f, Ui.FAINT)
        }
        setContentView(ui.scroll())
    }

    private fun hm(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.of("Asia/Shanghai")).let { "%02d:%02d".format(it.hour, it.minute) }
}
