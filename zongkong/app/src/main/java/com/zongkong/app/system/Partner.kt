package com.zongkong.app.system

import android.content.Context
import com.zongkong.app.zk
import com.zongkong.core.boss.Intensity
import com.zongkong.core.hq.Hq

/**
 * 战友模式里的“解锁就问”：手机刚解锁（30 秒到 3 分钟内），离上次说话超过 20 分钟，就开口问一句——
 * 刚才在做什么、接下来 25 分钟做哪一步。四十分钟内最多问一次，睡觉时段不问。
 */
object Partner {
    @Volatile private var lastGreet = 0L

    fun afterUnlock(context: Context, unlockedAt: Long, now: Long) {
        // 导演开着时，解锁问候由导演的议程统一决定；这里不再自己开口，避免两边各说各话
        if (com.zongkong.app.director.DirectorHost.enabled(context)) return
        if (unlockedAt <= 0 || now - unlockedAt !in 30_000L..180_000L) return
        val app = context.zk
        val store = app.store
        val cfg = store.config.value
        if (Intensity.level(cfg.boss, store.stance(now)) < Intensity.PARTNER) return
        if (Hq.quiet(cfg.hq, now, store.zone) || store.hq.value.active != null) return
        if (now - lastGreet < 40 * 60_000L) return
        val since = (now - store.lastContact) / 60_000
        if (since < 20) return
        if (!store.firstAlert("unlock:$unlockedAt")) return
        lastGreet = now
        val main = com.zongkong.core.plan.Plans.mainAction(store.plans.value, store.work.value, now, store.zone)
        app.chat.proactive(
            "unlock",
            "[战友·解锁] 他刚解锁手机，上一次和你说话是 $since 分钟前。你是和他并肩作战的战友：一两句话，先问刚才这段在做什么（必要时看 device_timeline），" +
                "再要他说出接下来 25 分钟做哪一步" + (main?.let { "；今天的主线是「${it.title}」，没动就直接点名" } ?: "") + "。最后给【选项】。",
            fallback = main?.let { "回来了。「${it.title}」现在到哪一步了？接下来 25 分钟做什么？" } ?: "回来了。刚才在做什么？接下来 25 分钟做哪一步？",
        )
    }
}
