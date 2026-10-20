package com.moling.app

import com.moling.core.flow.Flow
import com.moling.core.mind.Facts
import com.moling.core.mind.FactsSource

/*
 * 手机这边能看到的事实：时间、静默。
 *
 * 主线、水道、总控状态这些由大脑（BrainFacts.merge）补上，自己不猜；
 * 拿不到的写进 unknown，导演就知道自己瞎了哪只眼。
 * 这一批不做后台定时、不读前台应用、不做无障碍。
 */
class PhoneFacts : FactsSource {

    override fun facts(now: Long): Facts {
        val minute = Flow.minuteOf(now)
        return Facts(
            now = now,
            minute = minute,
            // 23:30–07:00 算静默：导演夜里不弹卡。
            quiet = minute >= 23 * 60 + 30 || minute < 7 * 60,
            unknown = UNKNOWN,
        )
    }

    private companion object {
        val UNKNOWN = listOf("前台应用", "使用时长", "健康")
    }
}
