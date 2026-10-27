package cn.companion.pet.core.life

import cn.companion.pet.net.SearchHit

/** 一次「找东西读」的结果。没有可溯源来源时只允许 [NoSource]，绝不产出可编造的活动。 */
sealed interface ReadCycleOutcome {
    data class Got(val activity: OwnActivity) : ReadCycleOutcome

    /** 没有任何可用来源（搜索失败/空/模型选了不存在的 url）。 */
    data object NoSource : ReadCycleOutcome
}

/**
 * 把「搜索 → 模型挑一条 → 变成活动 → 并入状态」串起来的纯逻辑层。
 *
 * 不碰网络、不碰文件、不碰模型：检索与挑选都由调用方注入，这里只按规则判定，
 * 所以能在 JVM 上直接测。硬门槛仍然只有一条 —— **选中的 url 必须真实存在于搜索结果里**。
 */
object OwnLifeCycle {

    /** 同时最多保留几件它在读的东西。多了就会变成「同时读五本书」，那不是生活，是表演。 */
    const val MAX_ACTIVITIES = 2

    /**
     * 从搜索结果里挑一条变成它正在读的东西。
     *
     * [pickUrl] 是「模型挑中的 url」这一步的结果，由调用方注入；这里**必须**经
     * [ReadingPicker.pick] 把关，不许自己构造 [OwnActivity]，否则「没读到就不算读过」这条线会在这里破掉。
     */
    fun fromSearch(
        hits: List<SearchHit>,
        pickUrl: String,
        angle: String,
        impression: String,
        now: Long,
    ): ReadCycleOutcome = when (val picked = ReadingPicker.pick(hits, pickUrl, angle, impression, now)) {
        is PickOutcome.Picked -> ReadCycleOutcome.Got(picked.activity)
        PickOutcome.NoSource -> ReadCycleOutcome.NoSource
    }

    /**
     * 把新活动并入现有状态：已有同一 id 的活动则更新，否则追加。
     *
     * 三条约束：
     * - 同 id 覆盖，不产生重复条目，进度只增不减（重挑同一篇不该把已读的页数吐回去）；
     * - 最多 [MAX_ACTIVITIES] 件，超过就丢 [OwnActivity.lastTouchedAt] 最小的；
     * - **不动** mood / moodReason / moodSince —— 情绪只由 [OwnLifePlanner.advance] 依据「真的读完了」改变。
     */
    fun adopt(state: OwnLifeState, activity: OwnActivity, now: Long): OwnLifeState {
        // 新碰到的这一件，最近触碰时间就是此刻；否则排序会把它当成"最久没碰"的第一名丢掉。
        val incoming = activity.copy(lastTouchedAt = now)
        val existing = state.activities.firstOrNull { it.id == incoming.id }
        val merged = if (existing == null) incoming else merge(existing, incoming)

        val activities = state.activities
            .map { if (it.id == incoming.id) merged else it }
            .let { list -> if (existing == null) list + merged else list }
            // 稳定排序：最近碰过的在前，同样的时间戳保持原顺序；超过上限的自然被截掉。
            .sortedByDescending { it.lastTouchedAt }
            .take(MAX_ACTIVITIES)

        return state.copy(activities = activities)
    }

    /** 重挑同一篇：元数据用新的一次（角度/感想可能更新），进度与开始时间不倒退，空字段不覆盖已有内容。 */
    private fun merge(existing: OwnActivity, incoming: OwnActivity): OwnActivity = incoming.copy(
        angleOfInterest = incoming.angleOfInterest.ifBlank { existing.angleOfInterest },
        impression = incoming.impression.ifBlank { existing.impression },
        title = incoming.title.ifBlank { existing.title },
        sourceSite = incoming.sourceSite.ifBlank { existing.sourceSite },
        sourceDate = incoming.sourceDate ?: existing.sourceDate,
        progress = maxOf(incoming.progress, existing.progress),
        totalHint = incoming.totalHint ?: existing.totalHint,
        startedAt = minOf(incoming.startedAt, existing.startedAt),
        lastTouchedAt = maxOf(incoming.lastTouchedAt, existing.lastTouchedAt),
    )

    /**
     * 拼成提示词里的一行：它自己在读什么、读到哪、什么感觉；情绪不平静时再补一句。
     *
     * 只陈述落盘过的事实，没有活动就返回空串（[cn.companion.pet.core.probe.ProbePrompt] 对空串不显示任何东西）。
     */
    fun promptLine(state: OwnLifeState): String {
        // 可溯源的才算读过；没有 url 的条目在这里也要被挡掉，否则提示词会替它编。
        val activity = state.activities
            .filter { it.hasTraceableSource() }
            .maxByOrNull { it.lastTouchedAt }
            ?: return moodLine(state.mood)

        val title = bookTitle(activity.title)
        val limit = activity.totalHint
        val readPart = when {
            limit != null && activity.progress >= limit ->
                "我刚读完" + title
            limit != null ->
                "正在读" + title + "，读到 " + activity.progress.coerceAtLeast(0) + "/" + limit
            else ->
                "正在读" + title + "，读到 " + activity.progress.coerceAtLeast(0) + " 页"
        }
        val parts = mutableListOf(readPart)
        activity.impression.trim().takeIf { it.isNotEmpty() }?.let { parts += "感想：" + it }
        moodLine(state.mood).takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts.joinToString("，")
    }

    /** 标题只是素材原文：已经自带书名号的不再补一层。 */
    private fun bookTitle(title: String): String {
        val t = title.trim().ifBlank { return "手上那篇" }
        return if (t.startsWith("《") || t.startsWith("「") || t.startsWith("\"")) t else "《" + t + "》"
    }

    /** 心情只影响语气；[OwnMood.CALM] 不加任何句子。 */
    private fun moodLine(mood: OwnMood): String = when (mood) {
        OwnMood.CALM -> ""
        OwnMood.TIRED -> "我现在有点累"
        OwnMood.CURIOUS -> "我现在有点好奇"
        OwnMood.DOWN -> "我有点低落"
        OwnMood.CONTENT -> "我心情不错"
    }
}
