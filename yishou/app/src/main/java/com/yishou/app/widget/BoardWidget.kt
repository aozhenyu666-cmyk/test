package com.yishou.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.yishou.app.MainActivity
import com.yishou.app.R
import com.yishou.app.YishouApp
import com.yishou.app.window.WindowActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面小组件：显示当前任务和“下一问”，点一下进去应手，或直接进陪练窗口。
 * 内容在断点变化时由应用推送更新，另外系统每 30 分钟刷新一次。
 */
class BoardWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = (context.applicationContext as YishouApp).database.dao()
                val task = dao.getCurrentTask()
                val bp = task?.let { dao.getBreakpoint(it.id) }
                render(context, task?.title, bp?.nextQuestion?.takeIf { it.isNotBlank() } ?: bp?.pendingCoachMove)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, BoardWidget::class.java))

        /** 把当前任务和下一问画到所有小组件上。 */
        fun render(context: Context, title: String?, question: String?) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val open = PendingIntent.getActivity(
                context, 30, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val window = PendingIntent.getActivity(
                context, 31, Intent(context, WindowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val views = RemoteViews(context.packageName, R.layout.widget_board).apply {
                setTextViewText(R.id.widget_title, title?.takeIf { it.isNotBlank() } ?: "一手")
                setTextViewText(R.id.widget_question, question?.takeIf { it.isNotBlank() } ?: "还没有当前任务。点这里填一个真实的学习任务。")
                setOnClickPendingIntent(R.id.widget_root, open)
                setOnClickPendingIntent(R.id.widget_answer, open)
                setOnClickPendingIntent(R.id.widget_window, window)
            }
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }

        /** 请求把小组件放到桌面（Android 8 起支持，个别桌面不支持时返回 false）。 */
        fun requestPin(context: Context): Boolean {
            val m = AppWidgetManager.getInstance(context)
            if (!m.isRequestPinAppWidgetSupported) return false
            return m.requestPinAppWidget(ComponentName(context, BoardWidget::class.java), null, null)
        }
    }
}
