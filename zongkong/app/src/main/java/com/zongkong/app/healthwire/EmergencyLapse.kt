package com.zongkong.app.healthwire

/**
 * 紧急暂停「什么时候该失效」（P0-6a）。
 *
 * 修的旧行为（`docs/AUDIT-V2.md` §2 P0-6）：
 *   `ClientPolicyStore.emergencyStopped()` 原来只看「有没有一条 STOPPED 记录」，
 *   **不看窗口、不看时长**。于是客户端一旦被哨兵按下暂停，就永久停掉自己的自动锁屏，
 *   只有人手动点「恢复监管」才能解开 —— 哨兵那边有到期（`WatchService.emergencyActive()`），
 *   客户端这边没有，两侧口径不一致。哨兵失联时客户端无人来解，兜底能力永久归零。
 *
 * 现在的规矩（两条，缺一不可）：
 *   1. **窗口结束就失效**：暂停只管「按下时正在生效的那几个窗口」，窗口一到点，暂停自然解除；
 *   2. **设总上限**：[DEFAULT_MAX_HOURS]（默认 2 小时）。窗口很长（比如一整天）时封顶，
 *      免得一次紧急暂停变成整天不管。上限设 0 = 只按窗口判。
 *
 * 纯函数：不读磁盘、不碰 Android、不看时钟（时间一律入参），所以可以钉死到毫秒级做单测。
 * 「到期 ≠ 已恢复监管」——到期只是不再暂停，规则该执行的照执行。
 */
object EmergencyLapse {

    /** 默认上限：2 小时。 */
    const val DEFAULT_MAX_HOURS = 2

    val DEFAULT_MAX_MS: Long = DEFAULT_MAX_HOURS * 60L * 60_000L

    /** 上限最多 24 小时：再长就等于「整天不管」，不是紧急暂停了。 */
    private const val HARD_MAX_HOURS = 24

    /** 小时数 → 毫秒上限。0（或负数）= 不设上限，只按窗口结束。 */
    fun capMs(maxHours: Int): Long =
        if (maxHours <= 0) 0L else maxHours.coerceAtMost(HARD_MAX_HOURS) * 60L * 60_000L

    /**
     * 一次判定的结论。
     *
     * @param active       现在还需不需要「暂停自动锁屏」。
     * @param untilWall    这次暂停的到期时刻（墙钟）；0 = 没有暂停，或算不出来（老数据且不限时长）。
     * @param why          说人话：为什么还在暂停 / 为什么已经失效。
     * @param staleOnDisk  磁盘上还写着「已暂停」，但按时间早该失效了 —— 调用方应清一次并落盘，
     *                     否则每次启动都要重算一遍，也让人在状态页看到自相矛盾的「已暂停」。
     */
    data class Verdict(
        val active: Boolean,
        val untilWall: Long,
        val why: String,
        val staleOnDisk: Boolean,
    )

    /**
     * 按下暂停的那一刻，算出「这次暂停最晚到什么时候」。
     *
     * 取两者较小值：按下时生效窗口的 `endsAtWall` 与 `nowWall + capMs`。
     * 没有生效中的窗口（用户在监管空档按了暂停）时，只能按上限兜底 ——
     * 宁可 2 小时后自动恢复，也不要留一个没有尽头的暂停。
     */
    fun deadline(
        policies: List<LocalPolicy>,
        nowWall: Long,
        capMs: Long = DEFAULT_MAX_MS,
    ): Long {
        val windowEnd = policies.asSequence()
            .filter { it.effective(nowWall) }
            .map { it.endsAtWall }
            .filter { it > nowWall }
            .maxOrNull() ?: 0L
        val cap = if (capMs > 0) nowWall + capMs else 0L
        return when {
            windowEnd > 0L && cap > 0L -> minOf(windowEnd, cap)
            windowEnd > 0L -> windowEnd
            cap > 0L -> cap
            // 既没有窗口也不限时长：仍给一个默认上限，绝不留永久暂停。
            else -> nowWall + DEFAULT_MAX_MS
        }
    }

    /**
     * 判断这次紧急暂停是否还在生效。
     *
     * @param recordAtWall    按下暂停的墙钟时刻；0 = 没有记录。
     * @param recordUntilWall 记录里存的到期时刻；0 = 老版本数据（没有这个字段）。
     * @param policies        本地规则（看 STOPPED 标记与各自窗口）。
     * @param nowWall         现在。
     * @param capMs           上限；0 = 只按窗口。
     */
    fun eval(
        recordAtWall: Long,
        recordUntilWall: Long,
        policies: List<LocalPolicy>,
        nowWall: Long,
        capMs: Long = DEFAULT_MAX_MS,
    ): Verdict {
        val flagged = policies.filter { it.stopped() }
        val hasRecord = recordAtWall > 0L
        if (!hasRecord && flagged.isEmpty()) {
            return Verdict(active = false, untilWall = 0L, why = "没有紧急暂停", staleOnDisk = false)
        }

        // ---- 到期时刻：优先信记录里的 ----
        val until: Long
        val basis: String
        when {
            recordUntilWall > 0L -> {
                until = recordUntilWall
                basis = "记录里的到期时刻"
            }
            // 老数据（升级前按下的暂停）：绝不能当作「永久」，从按下时刻补算。
            // 一律以 recordAtWall 为基准，保证每次算出来的到期时刻是同一个（不能随 nowWindow 漂移）。
            hasRecord -> {
                val end = flagged.map { it.endsAtWall }.filter { it > recordAtWall }.maxOrNull()
                until = when {
                    capMs > 0L && end != null -> minOf(end, recordAtWall + capMs)
                    capMs > 0L -> recordAtWall + capMs
                    end != null -> end
                    else -> recordAtWall + DEFAULT_MAX_MS
                }
                basis = "老记录补算到期时刻"
            }
            // 只有 STOPPED 标记、连记录都没有：只能按窗口判；没有窗口就直接算过期。
            else -> {
                until = flagged.map { it.endsAtWall }.filter { it > 0L }.maxOrNull() ?: nowWall
                basis = "只有暂停标记，按窗口判"
            }
        }

        val active = nowWall < until
        val why = if (active) {
            "紧急暂停生效中（$basis，还剩 ${(until - nowWall) / 60_000} 分钟）"
        } else {
            "紧急暂停已到期（$basis，到期于 ${until}）—— 规则照旧执行"
        }
        // 磁盘写着暂停、实际已失效 → 需要清一次。
        val stale = !active && (hasRecord || flagged.isNotEmpty())
        return Verdict(active = active, untilWall = until, why = why, staleOnDisk = stale)
    }
}
