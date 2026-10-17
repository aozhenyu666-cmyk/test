package com.zongkong.app.system

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.zongkong.app.R
import com.zongkong.app.guard.Diag
import com.zongkong.app.guard.GuardService
import com.zongkong.app.ui.MainActivity
import com.zongkong.app.zk
import com.zongkong.core.agent.Agent
import com.zongkong.core.agent.ChatMsg

/**
 * 桌面小部件：宠物、信用和态度、总控最近说的一句；“说话”进对讲，“打开”进对话。
 *
 * 它也是“眼睛还在不在”的活体指示：无障碍服务没在运行的时候，墨灵会睡觉（闭眼），
 * 写一句“我看不见了”，点一下直接去权限页——不用等你从“感觉不对”里猜。
 */
class PetWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        private fun open(context: Context, route: String, code: Int) = PendingIntent.getActivity(
            context, 900 + code,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MainActivity.EXTRA_ROUTE, route),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun refresh(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(context, PetWidget::class.java))
                if (ids.isEmpty()) return
                val app = context.zk
                val stance = app.store.stance()
                val cred = app.store.credibility()
                val kind = PetView.kindOf(app.store.config.value.boss.petKind)
                val v = RemoteViews(context.packageName, R.layout.widget_pet)
                val online = GuardService.running

                if (online) {
                    val last = app.chat.log.value.messages.lastOrNull { it.role == ChatMsg.ASSISTANT && it.content.isNotBlank() && !it.error && !it.hidden }?.content
                    val text = last?.let { Agent.options(it).first }?.take(120) ?: "有事按住我说。"
                    v.setImageViewBitmap(R.id.widget_pet, PetView.render(192, PetView.Mood.IDLE, stance, kind))
                    v.setTextViewText(R.id.widget_title, "总控 · 信用 $cred · ${stance.label}")
                    v.setTextViewText(R.id.widget_text, text)
                    v.setOnClickPendingIntent(R.id.widget_root, open(context, "chat", 1))
                    v.setOnClickPendingIntent(R.id.widget_open, open(context, "chat", 2))
                } else {
                    // 眼睛不在了：墨灵睡着（闭眼），直说“我看不见了”；点它去权限页
                    val diag = runCatching { Diag.summaryToday(context) }.getOrNull()
                    val lost = diag?.lostCount ?: 0
                    val why = buildString {
                        append(Pet.offlineNote(context) ?: "我看不见了：无障碍没在运行。")
                        if (lost > 0) append("今天掉了 $lost 次。")
                        append("点我，去权限页重新打开。")
                    }
                    v.setImageViewBitmap(R.id.widget_pet, PetView.render(192, PetView.Mood.SLEEP, stance, kind))
                    v.setTextViewText(R.id.widget_title, "总控 · 眼睛闭上了")
                    v.setTextViewText(R.id.widget_text, why)
                    v.setOnClickPendingIntent(R.id.widget_root, open(context, "settings/perm", 4))
                    v.setOnClickPendingIntent(R.id.widget_open, open(context, "settings/perm", 5))
                }
                v.setOnClickPendingIntent(R.id.widget_talk, open(context, "voice", 3))
                // 记下这次画的是“醒着”还是“睡着”：进程被杀时看门狗靠这个把画错的离线图刷回来
                Watchdog.noteWidget(context, online)
                mgr.updateAppWidget(ids, v)
            } catch (e: Exception) {
                Log.w("PetWidget", "更新小部件失败", e)
            }
        }
    }
}
