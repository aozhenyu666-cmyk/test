package com.yishou.app.wallpaper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.service.wallpaper.WallpaperService
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.SurfaceHolder
import android.app.WallpaperManager
import com.yishou.app.YishouApp
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 局面壁纸：桌面背景是一张淡淡的棋盘，中间写着当前任务的“下一问”。
 *
 * 两个用处：一是把局面一直摆在眼前（外置的“保持”）；二是“壁纸守护”——
 * 系统会一直保持动态壁纸所在的应用运行，清理后台时不容易被杀，入口思考页和陪练窗口更稳。
 * 只在内容变化、屏幕亮起时重画一次，没有持续动画，几乎不耗电。
 */
class BoardWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = BoardEngine()

    @OptIn(ExperimentalCoroutinesApi::class)
    private inner class BoardEngine : Engine() {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var job: Job? = null
        private var title = ""
        private var question = ""
        private var stuck = ""

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val dao = (application as YishouApp).database.dao()
            job = scope.launch {
                dao.observeCurrentTask()
                    .flatMapLatest { task ->
                        if (task == null) flowOf<Pair<Task?, Breakpoint?>>(null to null)
                        else dao.observeBreakpoint(task.id).map { task to it }
                    }
                    .collect { (task, bp) ->
                        title = task?.title.orEmpty()
                        question = bp?.nextQuestion?.takeIf { it.isNotBlank() }
                            ?: bp?.pendingCoachMove.orEmpty()
                        stuck = bp?.stuck.orEmpty()
                        draw()
                    }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) draw()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            draw()
        }

        override fun onDestroy() {
            job?.cancel()
            scope.cancel()
            super.onDestroy()
        }

        private fun draw() {
            val holder = surfaceHolder
            val canvas = try {
                holder.lockCanvas()
            } catch (e: Exception) {
                null
            } ?: return
            try {
                paint(canvas)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {
                }
            }
        }

        private fun paint(c: Canvas) {
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val bg = if (night) 0xFF0E1524.toInt() else 0xFFECEEE9.toInt()
            val ink = if (night) 0xFFE6E4DC.toInt() else 0xFF14171C.toInt()
            val gold = if (night) 0xFFD9B44A.toInt() else 0xFF9C7A14.toInt()
            val w = c.width.toFloat()
            val h = c.height.toFloat()
            val d = resources.displayMetrics.density
            c.drawColor(bg)

            // 棋盘格线和九个星位
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
                alpha = if (night) 20 else 16
                strokeWidth = 1f
            }
            val cell = w / 18.5f
            val left = (w - cell * 18) / 2
            for (i in 0..18) c.drawLine(left + i * cell, 0f, left + i * cell, h, line)
            var y = 0f
            while (y <= h) {
                c.drawLine(0f, y, w, y, line)
                y += cell
            }
            val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold; alpha = if (night) 170 else 90 }
            for (r in listOf(3, 9, 15)) for (col in listOf(3, 9, 15)) {
                c.drawCircle(left + col * cell, r * cell, cell * 0.09f, star)
            }

            if (question.isBlank()) return
            val textWidth = (w * 0.8f).toInt()
            val x = (w - textWidth) / 2
            var top = h * 0.36f

            val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = gold
                textSize = 13 * d
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }
            c.drawText("下一问", x, top, label)
            top += 14 * d

            val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
                alpha = 235
                textSize = 21 * d
                typeface = Typeface.SERIF
            }
            val layout = StaticLayout.Builder.obtain(question, 0, question.length, body, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(8 * d, 1f)
                .setMaxLines(7)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            c.save()
            c.translate(x, top)
            layout.draw(c)
            c.restore()
            top += layout.height + 22 * d

            val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
                alpha = 140
                textSize = 13 * d
            }
            if (stuck.isNotBlank()) {
                val s = StaticLayout.Builder.obtain("卡在：$stuck", 0, stuck.length + 3, small, textWidth)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
                c.save()
                c.translate(x, top)
                s.draw(c)
                c.restore()
                top += s.height + 10 * d
            }
            if (title.isNotBlank()) c.drawText(title, x, top + 13 * d, small.apply { color = gold; alpha = 200 })
        }
    }

    companion object {
        fun isActive(context: Context): Boolean =
            WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName

        /** 打开系统的动态壁纸设置，直接选中局面壁纸；不支持时打开动态壁纸列表。 */
        fun open(context: Context) {
            val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(context, BoardWallpaperService::class.java),
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(direct)
            } catch (e: Exception) {
                try {
                    context.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                }
            }
        }
    }
}

