package com.zongkong.app.window

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.zongkong.app.director.DirectorHost
import com.zongkong.core.director.Actor
import com.zongkong.core.director.Card
import com.zongkong.core.director.SessionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 接力（施工队 A 的 A3）：拿开场白 → 交给演员 → 记在场时间 → 把结论接回来。
 *
 * 交开场白先做最稳的一档：复制到剪贴板，再打开演员 App，小窗提示“粘贴后进 Live，或者照着念”。
 * 分享和直达 Live 要等 P3 / P4 在真机上验过再改默认。
 *
 * 读不到演员说了什么就不假装读到：C 档（字幕）默认关，等 P2。
 */
object Relay {
    private const val TAG = "Relay"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private class Live(
        val app: Context,
        val actor: Actor,
        val sessionId: String,
        val startedAt: Long,
        val minutes: Int,
        val handoff: String,
    ) {
        var presentMs = 0L
        var leaves = 0
        var inActor = false
        var lastTick = 0L
        var awayFrom = 0L
        var conclusion = ""
        var cards: List<Card> = emptyList()
        var index = 0
    }

    private var live: Live? = null

    /** 这张卡建议去演员那里聊：拿开场白、复制、打开 App、把小窗换成开场白。 */
    fun begin(context: Context, card: Card) {
        if (card.actor == Actor.NONE) return
        val app = context.applicationContext
        scope.launch {
            val opening = DirectorHost.opening(app, card.actor, card.id) ?: return@launch
            val ok = handoff(app, card.actor, opening.text)
            val now = System.currentTimeMillis()
            val s = Live(app, card.actor, opening.sessionId, now, opening.minutes, if (ok) "复制" else "没打开")
            s.cards = opening.cards
            s.lastTick = now
            live = s
            MailuoWindow.showOpening(opening) {
                // 用户说“进 App 了”：把准备好的第一张卡推出去
                val next = s.cards.getOrNull(0)
                if (next != null) MailuoWindow.show(next) else MailuoWindow.hide()
            }
        }
    }

    /** 前台切换时算在场时间。 */
    fun onForeground(pkg: String, now: Long) {
        val s = live ?: return
        val here = pkg == s.actor.pkg
        if (here) {
            if (!s.inActor) {
                s.inActor = true
                if (s.awayFrom > 0 && now - s.awayFrom > 2 * 60_000L) s.leaves += 1
                s.awayFrom = 0
            } else {
                s.presentMs += (now - s.lastTick).coerceAtLeast(0)
            }
            s.lastTick = now
        } else if (s.inActor) {
            s.presentMs += (now - s.lastTick).coerceAtLeast(0)
            s.inActor = false
            s.awayFrom = now
            s.lastTick = now
        }
    }

    /** 没有切前台事件时，每半分钟补一次在场时间；离开超过 5 分钟就收线。 */
    fun tick(now: Long) {
        val s = live ?: return
        if (s.inActor) {
            s.presentMs += (now - s.lastTick).coerceAtLeast(0)
            s.lastTick = now
        } else if (s.awayFrom > 0 && now - s.awayFrom > 5 * 60_000L) {
            finish("离开太久", "")
        }
    }

    /** 用户对着小窗说了结论。 */
    fun noteConclusion(text: String) {
        val s = live ?: return
        s.conclusion = text
        finish("有结论", text)
    }

    /** 到时间了 / 用户点了休息：按现有记录收线。 */
    fun finish(reason: String, conclusion: String) {
        val s = live ?: return
        live = null
        if (s.inActor) s.presentMs += (System.currentTimeMillis() - s.lastTick).coerceAtLeast(0)
        val log = SessionLog(
            sessionId = s.sessionId,
            actor = s.actor,
            startedAt = s.startedAt,
            endedAt = System.currentTimeMillis(),
            presentMs = s.presentMs,
            leaves = s.leaves,
            handoff = s.handoff,
            handoffOk = s.handoff == "复制",
            conclusion = conclusion.ifBlank { s.conclusion },
        )
        Log.i(TAG, "收线：$reason，在场 ${log.presentMs / 60_000} 分钟，离开 ${log.leaves} 次")
        DirectorHost.onSession(s.app, log)
    }

    fun active(): Boolean = live != null

    /**
     * 会话中的按钮（蓝图里的 A / B 档）：在开场白准备好的问题之间往下走。
     * 返回 true 表示已处理、不必再交给导演——这些卡不在导演的议程里，交过去只会被当成“没事了”而收起小窗。
     */
    fun consume(r: com.zongkong.core.director.Reply): Boolean {
        val s = live ?: return false
        return when (r) {
            is com.zongkong.core.director.Reply.Continue,
            is com.zongkong.core.director.Reply.Stuck -> {
                s.index = (s.index + 1).coerceAtMost(s.cards.lastIndex.coerceAtLeast(0))
                s.cards.getOrNull(s.index)?.let { MailuoWindow.show(it) }
                true
            }
            is com.zongkong.core.director.Reply.Conclusion -> { noteConclusion(r.text); true }
            // 休息：先按现有记录收线，再让导演安排休息卡
            is com.zongkong.core.director.Reply.Rest -> { finish("休息", ""); false }
            // 会话里的卡被划掉不算“无视导演”
            is com.zongkong.core.director.Reply.Dismissed -> true
            else -> false
        }
    }

    // ---------- 交给演员 ----------

    private fun handoff(c: Context, actor: Actor, text: String): Boolean {
        copy(c, text)
        val pm = c.packageManager
        val intent = pm.getLaunchIntentForPackage(actor.pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                c.startActivity(intent)
                true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "打开 ${actor.pkg} 失败", e)
                false
            } catch (e: Exception) {
                Log.w(TAG, "打开 ${actor.pkg} 失败", e)
                false
            }
        }
        val url = when (actor) {
            Actor.GEMINI_LIVE, Actor.GEMINI_TEXT -> "https://gemini.google.com/app"
            Actor.CHATGPT -> "https://chatgpt.com/"
            Actor.NONE -> return false
        }
        return try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.w(TAG, "打开网页版失败", e)
            false
        }
    }

    private fun copy(c: Context, text: String) {
        try {
            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("墨灵开场白", text))
        } catch (e: Exception) {
            Log.w(TAG, "复制开场白失败", e)
        }
    }
}
