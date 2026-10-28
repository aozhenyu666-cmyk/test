package com.yishou.app.healthwire

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.yishou.app.gate.GateService
import com.yishou.app.link.Partner
import android.content.Context

/**
 * 一手对哨兵开的口子（规范 §3.1/§3.2/§6）：authority `com.yishou.app.health`。
 *
 * method：
 *  - `challenge`：把挑战**投递到真正承担无障碍执行的那个线程**（GateService 的 a11y 主线程），
 *    在那里处理完再带回。Binder 线程只负责等，而且带超时。
 *  - `emergencyStop`：哨兵要求一手立刻停掉自己的自动锁屏（规范 §6）。**纯本地落盘**，
 *    不联网、不问模型、不等冷静期，幂等。
 *  - `ping`：只回答「provider 活着 + 当前是否处于暂停」，**不是健康凭据**（健康只认 challenge ack）。
 *
 * 明确不做（规范 §0 铁律 1 / §3.2 实现要点）：
 *  - 不在 Binder 线程上自己构造 challenge ack（那就是报告禁止的「后台线程代答」）；
 *  - 不读 GateService「在跑」这类布尔当健康凭据；
 *  - 不为无法取消的阻塞调用不停新建线程（在途上限在 [A11yChallengeHandler] 里）；
 *  - emergencyStop / ping **都不走 a11y 线程**：前者是本地文件写，后者只读内存事实，
 *    放到事件线程上反而会拖慢拦截（T04 的教训）。也绝不在这里发起任何跨进程调用。
 *  - ping 不返回任何屏幕内容、任务或认知数据（一手透明原则）。
 *
 * 鉴权：只接受哨兵的调用（包名 + 证书双重校验，见 [Partner.callerIsSentinel]），
 * 不接受消息体里自称的身份。
 */
class HealthProvider : ContentProvider() {

    private val handler = A11yChallengeHandler(maxInFlight = 2)

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val c = context ?: return err("没有上下文")
        if (!Partner.callerIsSentinel(c)) {
            // 不泄露任何本地事实；只说一句「只接受哨兵」。
            Log.w(TAG, "拒绝了非哨兵的调用：$method")
            return err("只接受哨兵的连接")
        }
        return try {
            when (method) {
                METHOD_CHALLENGE -> challenge(c.packageName, extras ?: Bundle())
                METHOD_EMERGENCY -> emergencyStop(c, extras ?: Bundle())
                METHOD_PING -> ping(c)
                else -> err("不认识的请求：$method")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$method 出错", t)
            err(t.message ?: t.javaClass.simpleName)
        }
    }

    // ---------- emergencyStop（规范 §6，报告 §8） ----------

    /**
     * 哨兵的紧急暂停传播到一手。
     *
     * 立即把本地规则置 STOPPED 并落盘 + 写独立暂停标记，**写完读回核对**才回 ok=true。
     * 幂等：重复调用仍返回 ok=true（`alreadyStopped=true`）。
     *
     * 不联网、不请求模型、不等用户写字、不等冷静期——只用本地文件。
     * 不碰 a11y 线程，也不发起任何跨进程调用（避免把事件通道拖住）。
     */
    private fun emergencyStop(c: Context, e: Bundle): Bundle {
        val reason = e.getString(K_REASON).orEmpty().trim().take(200)
        val now = System.currentTimeMillis()
        // 生产落点是 PolicyStore（原子写 + 独立暂停标记），与本地按钮走完全同一条路径；
        // bySentinel=true 会记进标记文件，界面据此说清「这是哨兵传来的暂停」。
        val outcome = EmergencyStop.stopLocal(
            sink = YishouGuard.policies(c).asStopSink(),
            nowWall = now,
            reason = reason,
            bySentinel = true,
        )
        // 停掉之后清掉仲裁器在途记忆，避免「已锁过一次」的旧账影响恢复后的第一次判断。
        YishouGuard.resetArbitration()
        // 暂停状态进内存事实：challenge ack 与 ping 都要如实反映。
        Facts.setPaused(outcome.ok)
        Facts.onReceipt(if (outcome.ok) "PAUSED" else "PAUSE_FAILED", now)
        if (outcome.ok) {
            runCatching {
                com.yishou.app.log.RunLog.e(
                    TAG,
                    "收到哨兵的紧急暂停：${reason.ifBlank { "（未填原因）" }}；${outcome.note}",
                )
            }
        }
        return if (outcome.ok) {
            // 回包字段由 [ProviderWire] 的白名单统一构造（单测逐键钉住，防止顺手塞进屏幕文字或任务数据）。
            bundleOf(
                ProviderWire.emergencyStopPayload(
                    pkg = c.packageName,
                    policyIds = outcome.policyIds,
                    revision = outcome.revision,
                    atWall = outcome.atWall,
                    atMono = SystemClock.elapsedRealtime(),
                    alreadyStopped = outcome.alreadyStopped,
                    note = outcome.note,
                ),
            )
        } else {
            err(outcome.note.ifBlank { "未能确认停止" })
        }
    }

    // ---------- ping（在岗探测，不是健康凭据） ----------

    /**
     * 只回答「provider 活着 + 是否处于暂停」。**不返回任何屏幕内容、任务或认知数据**。
     * 明确不是健康凭据：健康只认 `challenge` 的 dispatcherAck（规范 §0 铁律 1）。
     */
    private fun ping(c: Context): Bundle {
        val facts = Facts.snapshot()
        val stopped = Facts.paused || runCatching { YishouGuard.policies(c).emergencyStopped() }
            .getOrDefault(false)
        return bundleOf(
            ProviderWire.pingPayload(
                pkg = c.packageName,
                instanceId = facts.instanceId,
                stopped = stopped,
                atMono = SystemClock.elapsedRealtime(),
            ),
        )
    }

    /** Map → Bundle（只放 String/Int/Long/Boolean，规范 §3.1：不用 Parcelable）。 */
    private fun bundleOf(payload: Map<String, Any?>): Bundle = Bundle().apply {
        payload.forEach { (k, v) ->
            when (v) {
                null -> Unit
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is Long -> putLong(k, v)
                is String -> putString(k, v)
                else -> putString(k, v.toString())
            }
        }
    }

    private fun challenge(pkg: String, b: Bundle): Bundle {
        val req = ChallengeRequest(
            v = b.getInt(K_V, 0),
            nonce = b.getString(K_NONCE).orEmpty(),
            seq = b.getLong(K_SEQ, 0),
            budgetMs = b.getLong(K_BUDGET, 5_000L),
        )
        val facts = Facts.snapshot()
        // 自报失败原因也记账（「线程冻结」和「没连上」要分得开）。
        val outcome = handler.handle(
            req = req,
            pkg = pkg,
            // 连接实例：只有无障碍真的连上过才有值。重建必须换新（onServiceConnected 里换）。
            instanceId = facts.instanceId,
            post = { block -> GateService.postOnA11yThread(block) },
            mono = { SystemClock.elapsedRealtime() },
            facts = { Facts.ackFacts() },
            // a11y 线程回了话才算真实 dispatcherAck（T02）。这里只记「发生过」。
            onHandled = { at -> YishouGuard.onAckHandled(req.seq, at) },
            onFailed = { reason -> YishouGuard.onChallengeFailed(reason) },
        )
        return when (outcome) {
            is ChallengeOutcome.Ack -> Bundle().apply {
                putBoolean(K_OK, true)
                putInt(K_V, outcome.protocolVersion)
                putString(K_PKG, outcome.pkg)
                putString(K_INSTANCE, outcome.instanceId)
                putString(K_NONCE, outcome.nonce)
                putLong(K_SEQ, outcome.seq)
                putLong(K_HANDLED_AT, outcome.handledAtMono)
                putInt(K_RULE_REVISION, outcome.facts.ruleRevision)
                putString(K_RECEIPT_STATE, outcome.facts.lastReceiptState)
                putLong(K_RECEIPT_AT, outcome.facts.lastReceiptAtMono)
                putLong(K_EVENT_SEQ, outcome.facts.eventSeq)
                putLong(K_EVENT_AT, outcome.facts.eventAtMono)
            }
            is ChallengeOutcome.Fail -> err(outcome.error)
            is ChallengeOutcome.Timeout -> err("dispatcher timeout")
        }
    }

    private fun err(msg: String) = Bundle().apply {
        putBoolean(K_OK, false)
        putString(K_ERROR, msg.ifBlank { "未知错误" })
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "YishouHealthProvider"

        /** 规范 §3.2 冻结的 authority。 */
        const val AUTHORITY = "com.yishou.app.health"

        /** 协议版本（规范 §3.1/§3.2 冻结为 2）。 */
        const val PROTOCOL_VERSION = 2

        const val METHOD_CHALLENGE = "challenge"

        /** 哨兵 → 客户端：紧急暂停（规范 §6）。与总控/同桌同名同义。 */
        const val METHOD_EMERGENCY = "emergencyStop"

        /** 哨兵 → 客户端：在岗探测。**不是健康凭据**。 */
        const val METHOD_PING = "ping"

        const val K_OK = "ok"
        const val K_V = "v"
        const val K_PKG = "pkg"
        const val K_INSTANCE = "instanceId"
        const val K_NONCE = "nonce"
        const val K_SEQ = "seq"
        const val K_BUDGET = "budgetMs"
        const val K_HANDLED_AT = "handledAtMono"
        const val K_RULE_REVISION = "ruleRevision"
        const val K_RECEIPT_STATE = "lastReceiptState"
        const val K_RECEIPT_AT = "lastReceiptAtMono"
        const val K_EVENT_SEQ = "eventSeq"
        const val K_EVENT_AT = "eventAtMono"
        const val K_ERROR = "error"

        // emergencyStop / ping 共用
        const val K_REASON = "reason"
        const val K_STOPPED = "stopped"
        const val K_ALREADY = "alreadyStopped"
        const val K_POLICY_IDS = "policyIds"
        const val K_STOPPED_COUNT = "stoppedCount"
        const val K_AT_WALL = "atWall"
        const val K_AT_MONO = "atMono"
        const val K_NOTE = "note"
        const val K_PROVIDER_ALIVE = "providerAlive"
    }
}
