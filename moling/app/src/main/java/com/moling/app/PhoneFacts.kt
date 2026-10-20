package com.moling.app

import com.moling.core.flow.Flow
import com.moling.core.flow.FlowPlan
import com.moling.core.mind.Facts
import com.moling.core.mind.FactsSource

/*
 * 施工队 A 的 FactsSource：这一批只填墨灵自己拿得到的几项。
 *
 * 拿不到的（总控数据、前台应用……）留默认值并写进 unknown —— 导演要知道自己瞎了哪只眼，
 * 而不是把“没数据”当成“没在做事”。等和总控接上了（下一批）再逐项填。
 */
class PhoneFacts(private val flow: () -> FlowPlan?) : FactsSource {

    override fun facts(now: Long): Facts {
        val minute = Flow.minuteOf(now)
        val plan = flow()
        return Facts(
            now = now,
            minute = minute,
            // 23:30–07:00 算静默：导演夜里不弹卡。
            quiet = minute >= 23 * 60 + 30 || minute < 7 * 60,
            // 今天的水道里此刻该在的那条；没有就空着，不拿昨天的主线充数。
            mainTitle = plan?.let { Flow.current(it, minute)?.title }.orEmpty(),
            unknown = UNKNOWN,
        )
    }

    private companion object {
        val UNKNOWN = listOf("总控数据", "前台应用", "使用时长", "信用", "消费", "长期目标", "本周要交", "健康")
    }
}
