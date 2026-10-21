package com.moling.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.moling.app.BrainHost
import com.moling.app.Notify
import com.moling.app.R
import com.moling.core.Brain
import com.moling.core.flow.Flow

class NowWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = update(context)

    companion object {
        fun update(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, NowWidget::class.java))
                if (ids.isEmpty()) return
                val now = System.currentTimeMillis()
                val state = BrainHost.state.value
                val channel = state.flow?.takeIf { it.day == Brain.calendarDay(now) }?.let { Flow.current(it, Flow.minuteOf(now)) }
                val head = channel?.let { "${time(it.startMin)}–${time(it.endMin)} ${it.title}" } ?: "今天还没排水道"
                val second = BrainHost.directorBaton()?.nextAsk?.takeIf { it.isNotBlank() }
                    ?: state.lastStatus?.let { if (it.strict) "严管中" else "放行" } ?: "总控：连不上"
                ids.forEach { id ->
                    val views = RemoteViews(context.packageName, R.layout.widget_now)
                    views.setTextViewText(R.id.widget_title, head)
                    views.setTextViewText(R.id.widget_detail, second)
                    views.setViewVisibility(R.id.widget_detail, if (manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) >= 240) View.VISIBLE else View.GONE)
                    views.setOnClickPendingIntent(R.id.widget_root, Notify.todayIntent(context))
                    manager.updateAppWidget(id, views)
                }
            }
        }
        private fun time(min: Int) = "%02d:%02d".format((min / 60) % 24, min % 60)
    }
}
