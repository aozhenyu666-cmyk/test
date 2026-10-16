package com.zongkong.pc

import com.zongkong.core.AiConfig
import com.zongkong.core.boss.Dossier
import kotlinx.serialization.Serializable

/**
 * 一次会话：这次开电脑说好做什么、到几点。到点锁屏对账。
 * [byBoss] = 总控批的（可以超过自己能开的时长、可以在总时长用完 / 夜里开）。
 */
@Serializable
data class Session(val purpose: String, val start: Long, val until: Long, val byBoss: Boolean = false, val reason: String = "")

/** 放行：某个网站 / 程序 / 一整类，在 [until] 之前不拦。[backTo] = 说好用完回去做的事。 */
@Serializable
data class Pass(val key: String, val label: String, val at: Long, val until: Long, val reason: String, val backTo: String = "", val by: String = "总控")

/** 锁机：[by] 是谁锁的（总控、手机、总控时间、自己）。 */
@Serializable
data class Lock(val at: Long, val until: Long, val reason: String, val by: String)

/** 总控没在看着的一段（被结束、关机没正常退出）。 */
@Serializable
data class Gap(val from: Long, val to: Long, val why: String = "")

/** 发给手机的一句话。 */
@Serializable
data class Outgoing(val id: String, val at: Long, val text: String)

/** 等你点“执行 / 不执行”的命令。 */
@Serializable
data class Confirm(val id: String, val command: String, val why: String, val at: Long)

/**
 * 运行时的状态（存在电脑上）：会话、放行、锁、档案（陈述、约定、信用）、记住的事、失联记录、发给手机的消息。
 */
@Serializable
data class PcState(
    val session: Session? = null,
    val passes: List<Pass> = emptyList(),
    val lock: Lock? = null,
    val dossier: Dossier = Dossier(),
    val memory: List<String> = emptyList(),
    /** 用过“紧急解锁”的时刻。 */
    val emergencies: List<Long> = emptyList(),
    val gaps: List<Gap> = emptyList(),
    val outbox: List<Outgoing> = emptyList(),
    /** 已经执行过的手机命令 id。 */
    val doneCmds: List<String> = emptyList(),
    /** 每次被拦（想打开但没让开）的时刻和东西。 */
    val blocks: List<BlockHit> = emptyList(),
    /** 上次主动问“在做什么”的时刻。 */
    val lastCheckIn: Long = 0,
    /** 点名拦：在 [Ban.until] 之前一律不开（放行也不行）。 */
    val bans: List<Ban> = emptyList(),
    /** 紧急解锁到几点（一天一次，十分钟，什么锁都打开，记一笔报给手机）。 */
    val emergencyUntil: Long = 0,
) {
    fun activeBans(now: Long) = bans.filter { it.until > now }
    fun activePasses(now: Long) = passes.filter { it.until > now }
    fun activeLock(now: Long) = lock?.takeIf { it.until > now }
    fun activeSession(now: Long) = session?.takeIf { it.until > now }
}

@Serializable
data class Ban(val key: String, val label: String, val until: Long, val reason: String, val by: String)

@Serializable
data class BlockHit(val at: Long, val key: String, val label: String, val why: String)

/** 放宽的改动：到 [due] 才生效。 */
@Serializable
data class PcPending(
    val at: Long,
    /** 什么时候生效（还在审的时候是 0）。 */
    val due: Long,
    val rules: PcRules,
    val summary: String,
    /** 你写的为什么要放宽。 */
    val why: String = "",
    /** 还在等总控审。 */
    val reviewing: Boolean = false,
    /** 总控的一句话（批了 / 没接 AI）。 */
    val verdict: String = "",
)

/** 被总控驳回的放宽：几小时内不能再申请同一件事。 */
@Serializable
data class Denial(val at: Long, val summary: String, val why: String, val reason: String)

@Serializable
data class PendingAi(val at: Long, val due: Long, val ai: AiConfig)

/** 和手机连：Notion 密钥、设备库。 */
@Serializable
data class LinkConfig(val token: String = "", val baseUrl: String = "", val devicesDb: String = "") {
    val ready: Boolean get() = token.isNotBlank() && devicesDb.isNotBlank()
}

/** 电脑版的全部设置。 */
@Serializable
data class PcConfig(
    val rules: PcRules = PcRules(),
    val pending: PcPending? = null,
    val ai: AiConfig = AiConfig(),
    val pendingAi: PendingAi? = null,
    /** 最近一次被驳回的放宽。 */
    val denied: Denial? = null,
    val link: LinkConfig = LinkConfig(),
    /** 用 Windows 自带的声音念出来。 */
    val speak: Boolean = true,
    val welcomed: Boolean = false,
)
