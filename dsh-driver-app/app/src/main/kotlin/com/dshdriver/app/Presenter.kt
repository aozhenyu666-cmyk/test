package com.dshdriver.app

import android.content.Context
import com.dshdriver.core.Card
import com.dshdriver.core.Directive

/**
 * 把驾驶员的指令画到屏幕上（主线程）：
 *   level 0 → 顶部悬浮小卡（不抢焦点，Live 照常听）；没有悬浮窗权限就退到全屏卡
 *   level ≥ 1、拉回、强提醒 → 全屏断点卡
 * "显示了"只由卡片自己在真正可见时回报，这里发出去不算。
 */
object Presenter {
    fun show(context: Context, d: Directive) {
        when (d.type) {
            Directive.SHOW -> {
                val c = d.card ?: return
                if (d.level == 0 && Overlay.canShow(context)) {
                    CardActivity.withdraw()
                    Overlay.show(context, c)
                } else {
                    Overlay.hide()
                    CardActivity.start(context, c, d.level, reflex = false)
                }
            }
            Directive.PULLBACK, Directive.ESCALATE -> {
                val c = d.card ?: return
                Overlay.hide()
                CardActivity.start(context, c, if (d.type == Directive.ESCALATE) 2 else maxOf(1, d.level), reflex = false)
            }
            Directive.RELEASE, Directive.END -> {
                Overlay.hide()
                CardActivity.withdraw()
            }
        }
    }

    /** 切屏反射：不等状态机，直接用缓存的当前卡盖住。 */
    fun reflex(context: Context, card: Card?) {
        Overlay.hide()
        if (card != null && !card.answered) CardActivity.start(context, card, maxOf(1, card.level), reflex = true)
        else CardActivity.startBare(context)
    }

    /** 这张卡已经结了（别的途径答掉、过期、被替换）：撤下。 */
    fun resolved(cardId: String) {
        Overlay.hideIf(cardId)
        CardActivity.withdrawIf(cardId)
    }
}
