package com.zongkong.app.data

import android.content.Context
import com.zongkong.app.system.Notifications
import com.zongkong.core.ApiResult
import com.zongkong.core.NotionClient
import com.zongkong.core.device.DeviceCmd
import com.zongkong.core.device.DeviceLink
import com.zongkong.core.device.Pairing
import com.zongkong.core.device.PhoneSide
import com.zongkong.core.plan.Aims
import com.zongkong.core.plan.Plans
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 手机 ↔ 电脑：在 Notion 的“总控·设备”库里，手机写自己那一行（主线、计划、信用、总控时间、给电脑的命令），
 * 读电脑那一行（今天用了多久、在做什么、锁没锁、被拦几次、失联、电脑发来的话）。
 */
class DeviceActions(private val context: Context, private val store: Store, private val notion: NotionClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var lastHqUntil = -1L

    val ready: Boolean get() = store.config.value.notion.token.isNotBlank() && store.config.value.notion.devicesDb.isNotBlank()

    /** 第一次：在 Notion 的父页面下建“总控·设备”库。 */
    suspend fun setup(): String {
        val n = store.config.value.notion
        if (n.token.isBlank()) return "先在 我 → Notion 里填密钥"
        if (n.parentPage.isBlank()) return "先在 我 → Notion 里一键搭建（要有父页面）"
        return when (val r = DeviceLink.ensureDb(notion, n.parentPage, n.devicesDb)) {
            is ApiResult.Err -> "建不了：${r.message}"
            is ApiResult.Ok -> {
                store.updateConfig { it.copy(notion = it.notion.copy(devicesDb = r.value)) }
                sync()
                "建好了。把配对码复制到电脑上。"
            }
        }
    }

    /** 配对码：Notion 密钥、设备库，可选带上 AI 接口。 */
    fun pairingCode(withAi: Boolean): String {
        val c = store.config.value
        return DeviceLink.pairingCode(Pairing(c.notion.token, c.notion.devicesDb, c.notion.baseUrl, if (withAi && c.ai.ready) c.ai else null))
    }

    /** 给电脑发一条命令（锁机、解锁、说一句、拦网站），马上同步一次。 */
    fun command(kind: String, minutes: Int = 0, text: String = ""): String {
        if (!ready) return "还没连电脑（我 → 电脑）"
        val now = System.currentTimeMillis()
        store.updateDevice { it.copy(cmds = (it.cmds + DeviceCmd("c${now.toString(36)}", now, kind, minutes, text)).takeLast(30)) }
        scope.launch { sync() }
        return when (kind) {
            "lock" -> "电脑会在一分钟内锁上 $minutes 分钟"
            "unlock" -> "电脑会在一分钟内解开（只解手机锁的）"
            "say" -> "已经发给电脑：$text"
            "block" -> "电脑会在一分钟内拦掉 $text"
            else -> "已发"
        }
    }

    /** 守护服务每轮调一次：两分钟同步一次；总控时间开始 / 结束时马上同步。 */
    fun maybeSync() {
        if (!ready) return
        val hq = store.hq.value.active?.takeIf { System.currentTimeMillis() >= it.suspendedUntil }?.endsAt ?: 0L
        val since = System.currentTimeMillis() - store.device.value.lastSync
        if (since > 120_000 || hq != lastHqUntil) {
            lastHqUntil = hq
            scope.launch { sync() }
        }
    }

    suspend fun sync(): String = mutex.withLock {
        if (!ready) return "还没连电脑"
        val n = store.config.value.notion
        val now = System.currentTimeMillis()
        val rows = when (val r = DeviceLink.readAll(notion, n.devicesDb)) {
            is ApiResult.Err -> { store.updateDevice { it.copy(lastError = r.message) }; return "同步失败：${r.message}" }
            is ApiResult.Ok -> r.value
        }
        val pc = rows[DeviceLink.PC]?.data?.let(DeviceLink::decodePc)
        val book0 = store.device.value
        // 电脑发来的新话：通知 + 进对话
        val fresh = pc?.msgs.orEmpty().filter { it.id !in book0.gotMsgs }
        fresh.forEach { m ->
            Notifications.advisor(context, "【电脑】${m.text}")
            com.zongkong.app.zkApp(context).chat.note("【电脑上的总控】${m.text}")
        }
        // 电脑已经执行的命令不用再发
        val done = pc?.doneCmds.orEmpty().toSet()
        val book = store.updateDevice { b ->
            b.copy(
                pc = pc ?: b.pc,
                phonePage = rows[DeviceLink.PHONE]?.pageId ?: b.phonePage,
                cmds = b.cmds.filter { it.id !in done && now - it.at < 6 * 3600_000L },
                gotMsgs = (b.gotMsgs + fresh.map { it.id }).takeLast(60),
            )
        }
        val c = store.config.value
        val book2 = store.plans.value
        val plan = Plans.active(book2, now, store.zone)
        val side = PhoneSide(
            at = now,
            main = Plans.mainAction(book2, store.work.value, now, store.zone)?.title.orEmpty(),
            goals = plan?.goals?.map { it.title }.orEmpty(),
            aims = Aims.open(book2).map { a -> a.title + (Aims.daysLeft(a, now, store.zone)?.let { "（还剩 $it 天）" } ?: "") },
            credibility = store.credibility(now),
            stance = store.stance(now).label,
            hqUntil = store.hq.value.active?.takeIf { now >= it.suspendedUntil }?.endsAt ?: 0L,
            cmds = book.cmds,
            gotMsgs = book.gotMsgs.takeLast(20),
        )
        val w = DeviceLink.write(notion, c.notion.devicesDb, DeviceLink.PHONE, DeviceLink.encodePhone(side), book.phonePage.ifBlank { null })
        return when (w) {
            is ApiResult.Err -> { store.updateDevice { it.copy(lastError = w.message) }; "写不进去：${w.message}" }
            is ApiResult.Ok -> {
                store.updateDevice { it.copy(phonePage = w.value, lastSync = now, lastError = "") }
                "已同步"
            }
        }
    }
}
