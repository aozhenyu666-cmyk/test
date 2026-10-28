package cn.companion.pet.core.life

/**
 * 「它自己的生活」的推进规则：纯函数，无 I/O、无随机、可 JVM 测。
 *
 * 规则是确定的：
 * - 进度按真实经过时间推进，30 分钟一档；
 * - 长时间没开 App 也只补算 [DEFAULT_ELAPSED_CAP_MILLIS] 的量，不会一口气读完三天的；
 * - 情绪不随机变化，只有“这次真的读完了一件”才转 [OwnMood.CONTENT]，并写下非空理由。
 */
object OwnLifePlanner {
    /** 一档进度对应的真实时间。 */
    const val STEP_MILLIS: Long = 30 * 60_000L

    /** 缺勤补算上限：一天。 */
    const val DEFAULT_ELAPSED_CAP_MILLIS: Long = 86_400_000L

    /**
     * 按真实经过时间推进一次。
     *
     * @param now 当前真实时间（毫秒）。
     * @param elapsedCapMillis 单次最多补算多少时间，避免长期缺勤后进度暴涨。
     */
    fun advance(
        state: OwnLifeState,
        now: Long,
        elapsedCapMillis: Long = DEFAULT_ELAPSED_CAP_MILLIS,
    ): OwnLifeState {
        // 时钟回拨或人为传旧时间时 elapsed 夹到 0；上限为负也按 0 处理。
        val elapsed = (now - state.lastAdvancedAt).coerceIn(0L, elapsedCapMillis.coerceAtLeast(0L))
        val steps = (elapsed / STEP_MILLIS).toInt()

        // Preserve partial intervals: a one-second tick must not erase the previous second.
        if (now < state.lastAdvancedAt) return state.copy(lastAdvancedAt = now)
        if (state.activities.isEmpty() || steps <= 0) return state

        var justFinished: OwnActivity? = null
        val advanced = state.activities.map { activity ->
            val limit = activity.totalHint
            val raw = activity.progress.coerceAtLeast(0) + steps
            // 不读过头：有总篇幅时停在 totalHint；已经超出的历史数据不往回缩。
            val progress = if (limit != null && raw > limit) maxOf(limit, activity.progress) else raw
            val crossed = limit != null && activity.progress < limit && progress >= limit
            if (crossed && justFinished == null) justFinished = activity
            // 已经读到头的不再刷新“最近碰到”时间，否则后续挑素材会一直挑到同一件读完的东西。
            val touchedAt = if (progress != activity.progress) now else activity.lastTouchedAt
            activity.copy(progress = progress, lastTouchedAt = touchedAt)
        }

        val finished = justFinished
        val mood = if (finished != null) OwnMood.CONTENT else state.mood
        val moodReason = if (finished != null) finishedReason(finished) else state.moodReason
        return state.copy(
            activities = advanced,
            mood = mood,
            moodReason = moodReason,
            moodSince = if (mood != state.mood) now else state.moodSince,
            lastAdvancedAt = now - elapsed % STEP_MILLIS,
        )
    }

    /** 情绪变化的理由一定非空，哪怕标题缺失；标题已自带书名号，不再补一层。 */
    private fun finishedReason(activity: OwnActivity): String {
        val title = activity.title.ifBlank { "手上那篇" }
        return "刚读完" + title
    }
}
