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
import com.zongkong.app.ui.MainActivity
import com.zongkong.app.zk
import com.zongkong.core.agent.Agent
import com.zongkong.core.agent.ChatMsg

/** 桌面小部件：宠物、信用和态度、总控最近说的一句；“说话”进对讲，“打开”进对话。 */
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
                val last = app.chat.log.value.messages.lastOrNull { it.role == ChatMsg.ASSISTANT && it.content.isNotBlank() && !it.error && !it.hidden }?.content
                val text = last?.let { Agent.options(it).first }?.take(120) ?: "有事按住我说。"
                val v = RemoteViews(context.packageName, R.layout.widget_pet)
                v.setImageViewBitmap(R.id.widget_pet, PetView.render(192, PetView.Mood.IDLE, stance, PetView.kindOf(app.store.config.value.boss.petKind)))
                v.setTextViewText(R.id.widget_title, "总控 · 信用 $cred · ${stance.label}")
                v.setTextViewText(R.id.widget_text, text)
                v.setOnClickPendingIntent(R.id.widget_root, open(context, "chat", 1))
                v.setOnClickPendingIntent(R.id.widget_open, open(context, "chat", 2))
                v.setOnClickPendingIntent(R.id.widget_talk, open(context, "voice", 3))
                mgr.updateAppWidget(ids, v)
            } catch (e: Exception) {
                Log.w("PetWidget", "更新小部件失败", e)
            }
        }
    }
}
