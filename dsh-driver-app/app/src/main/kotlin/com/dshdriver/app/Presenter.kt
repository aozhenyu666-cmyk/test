package com.dshdriver.app

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.dshdriver.core.Directive

/**
 * 把陪练的卡画到屏幕上（主线程）。
 *
 * 0.2.0 起**一律不抢屏**：你在和 Gemini 对话时，全屏卡会打断对话，而"你是不是在说话"在很多手机上读不准。
 * 所以所有卡都用顶部悬浮小卡（不抢焦点，Live 照常听）；没有悬浮窗权限时退到可以随时返回的普通页面。
 * 卡没人理时只是重新出现并短震一下，不再全屏、不再语音念题。拦截娱乐 App 是 Curbox 的事。
 * "显示了"只由卡片自己在真正可见时回报，这里发出去不算。
 */
object Presenter {
    fun show(context: Context, d: Directive) {
        when (d.type) {
            Directive.SHOW, Directive.PULLBACK, Directive.ESCALATE -> {
                val c = d.card ?: return
                if (d.type != Directive.SHOW || d.level >= 1) buzz(context)
                if (Overlay.canShow(context)) {
                    CardActivity.withdraw()
                    Overlay.show(context, c)
                } else {
                    CardActivity.start(context, c, 0, reflex = false)
                }
            }
            Directive.RELEASE, Directive.END -> {
                Overlay.hide()
                CardActivity.withdraw()
            }
        }
    }

    /** 这张卡已经结了（别的途径答掉、过期、被替换）：撤下。 */
    fun resolved(cardId: String) {
        Overlay.hideIf(cardId)
        CardActivity.withdrawIf(cardId)
    }

    private fun buzz(context: Context) {
        runCatching { context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }
}
