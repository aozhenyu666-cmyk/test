package com.zongkong.pc

import com.zongkong.core.boss.Boss
import com.zongkong.core.device.DeviceCmd
import com.zongkong.core.device.GapLine
import com.zongkong.core.device.PcSide
import com.zongkong.core.device.PhoneSide
import com.zongkong.core.device.UsageLine
import java.time.ZoneId

/** 和手机互通的纯逻辑：把电脑的状况整理成一行，把手机的命令落到状态上。 */
object PcSync {
    /** 电脑这一行。 */
    fun pcSide(state: PcState, today: UsageDay, rules: PcRules, now: Long, zone: ZoneId, version: String): PcSide {
        val day = Usage.day(now, zone)
        val lock = state.activeLock(now)
        val session = state.activeSession(now)
        return PcSide(
            at = now,
            version = version,
            todayMinutes = today.totalMinutes(),
            funMinutes = listOf(Cat.FUN, Cat.SOCIAL, Cat.GAME).sumOf { today.catMinutes(it) },
            top = today.top(6).map { UsageLine(it.first, it.second, it.third.label) },
            session = session?.purpose.orEmpty(),
            sessionUntil = session?.until ?: 0,
            lockUntil = lock?.until ?: 0,
            lockReason = lock?.let { "${it.by}：${it.reason}" }.orEmpty(),
            blocks = state.blocks.count { Usage.day(it.at, zone) == day },
            gaps = state.gaps.filter { Usage.day(it.to, zone) == day }.map { GapLine(it.from, it.to, it.why) },
            credibility = Boss.credibility(state.dossier, emptyList(), now),
            stance = Boss.stance(state.dossier, emptyList(), now).label,
            msgs = state.outbox.takeLast(10).map { com.zongkong.core.device.DeviceMsg(it.id, it.at, it.text) },
            doneCmds = state.doneCmds.takeLast(30),
        )
    }

    /** 落地一条命令后要界面做的事。 */
    sealed interface Effect {
        data class Say(val text: String) : Effect
        data class Locked(val minutes: Int, val reason: String) : Effect
        data object Unlocked : Effect
        data class Blocked(val name: String) : Effect
    }

    /**
     * 执行手机发来的新命令（执行过的跳过；超过 6 小时的旧命令只记为已执行）。
     * 返回新状态、新规则（拦网站会改规则）和要提示的事。
     */
    fun apply(state: PcState, config: PcConfig, phone: PhoneSide, now: Long): Triple<PcState, PcConfig, List<Effect>> {
        var s = state
        var c = config
        val effects = mutableListOf<Effect>()
        phone.cmds.filter { it.id !in s.doneCmds }.sortedBy { it.at }.forEach { cmd ->
            s = s.copy(doneCmds = (s.doneCmds + cmd.id).takeLast(100))
            if (now - cmd.at > 6 * 3600_000L) return@forEach
            when (cmd.kind) {
                "lock" -> {
                    val m = cmd.minutes.coerceIn(1, 600)
                    val until = maxOf(now + m * 60_000L, s.activeLock(now)?.until ?: 0)
                    s = s.copy(lock = Lock(now, until, cmd.text.ifBlank { "手机上的总控锁的" }, "手机"))
                    effects += Effect.Locked(m, cmd.text)
                }
                "unlock" -> if (s.activeLock(now)?.by == "手机") {
                    s = s.copy(lock = null)
                    effects += Effect.Unlocked
                }
                "say" -> if (cmd.text.isNotBlank()) effects += Effect.Say(cmd.text)
                "block" -> {
                    val r = PcTools.desired(c)
                    val name = cmd.text.trim()
                    var site = r.sites.firstOrNull { it.label == name || it.domains.any { d -> d == name.lowercase().removePrefix("www.") } }
                    if (site == null && name.contains('.')) {
                        val d = name.lowercase().removePrefix("www.")
                        site = SiteRule(d, Cat.FUN.name, listOf(d), listOf(d.substringBefore('.')))
                        c = PcPolicy.setRules(c, r.copy(sites = r.sites + site), now).config
                    }
                    if (site != null) {
                        val m = if (cmd.minutes > 0) cmd.minutes else 24 * 60
                        s = s.copy(bans = (s.bans.filter { it.until > now } + Ban("site:${site.label}", site.label, now + m * 60_000L, "手机上的总控拦的", "手机")).takeLast(50))
                        effects += Effect.Blocked(site.label)
                    }
                }
            }
        }
        return Triple(s, c, effects)
    }

    /** 手机已经收到的消息从发件箱里拿掉。 */
    fun acked(state: PcState, phone: PhoneSide): PcState =
        if (phone.gotMsgs.isEmpty()) state else state.copy(outbox = state.outbox.filterNot { it.id in phone.gotMsgs })

    /** 总控时间（手机）开始 → 电脑锁到结束。 */
    fun hqLock(cmdIdPrefix: String, until: Long, now: Long): DeviceCmd = DeviceCmd("$cmdIdPrefix-hq-$until", now, "lock", (((until - now) / 60_000) + 1).toInt(), "总控时间")
}
