package com.zongkong.core

/**
 * 模式判定的**唯一入口**。
 *
 * 指令明确要求：「所有拦截消费方使用统一结果，禁止各自重新推导模式」。
 * 所以任何要决定「现在该不该强制拦截」的地方，都只能调用 [verdict]，
 * 不许自己写 `status.strict && ...` 或 `config.mode.tier == STRICT && ...`。
 *
 * ### 三个概念必须分开，这是本次改造最容易被搞混的地方
 *
 * 1. **模式**（用户选的）：普通 / 严格。只有用户能改。
 * 2. **窗口是否有效**：严格模式下还要看时间窗口。到期自动失效，绝不自动续期。
 * 3. **运行健康**（能不能真的拦）：
 *    无障碍服务是否真的在响应、一手是否可用、哨兵是否在岗。
 *
 * 这三者**互相独立**。指令特别强调过两个不能犯的错：
 *  - **不能**因为健康检测失败就自动把模式清回普通（那是「因为拦不住，所以假装不用拦」）；
 *  - **不能**因为模式是严格就虚报「一切正常」（那是「假装拦得住」）。
 *
 * 所以 [ModeVerdict] 同时带 `enforcing`（该不该约束）和 `healthy`/`problems`
 * （约束能力是否真的到位），调用方必须两个都看。
 */
object ModeGate {

    /**
     * 判定结果。
     *
     * @param enforcing 现在是否**应该**执行强制约束（模式 + 窗口 + 紧急暂停）。
     * @param blocked 拦截名单是否生效（[enforcing] 的另一个名字，语义更贴近消费方）。
     * @param healthy 约束能力是否**真的**到位（无障碍在响应、无故障）。
     * @param problems 能力不到位时的具体原因，界面必须如实显示，不能吞掉。
     * @param tier 当前档位，给界面显示用。
     * @param endsAt 严格窗口结束时刻；0 = 不在窗口内。
     * @param revision 策略版本，随心跳推给哨兵做对齐。
     */
    data class ModeVerdict(
        val enforcing: Boolean,
        val healthy: Boolean,
        val problems: List<String>,
        val tier: ModeConfig.Tier,
        val endsAt: Long,
        val revision: Int,
        val emergencyPaused: Boolean,
        val now: Long,
    ) {
        /** 窗口还剩多久（毫秒）；不在严格窗口内为 0。 */
        val remainingMs: Long get() = if (endsAt > now) endsAt - now else 0L

        /**
         * 严格窗口有效、但约束能力不到位。
         *
         * 这是**必须报警**的状态：用户以为在被管，实际管不住。
         * 界面要显示故障，哨兵要接手，绝不能显示成「正常」。
         */
        val effectiveButBroken: Boolean get() = enforcing && !healthy

        /** 一句话状态（界面/日志/回传共用，避免各处各说各话）。 */
        fun headline(): String = when {
            tier != ModeConfig.Tier.STRICT -> "普通模式"
            endsAt <= now -> "严格窗口已结束"
            emergencyPaused -> "严格窗口内·已紧急暂停"
            enforcing && !healthy -> "严格监管中（但拦截能力异常）"
            enforcing -> "严格监管中"
            else -> "严格窗口未生效"
        }
    }

    /**
     * 统一判定。
     *
     * @param mode 用户选择的模式（来自持久化配置）。
     * @param now 当前时刻。
     * @param healthy 约束能力是否真的到位。**注意默认 true 是错的**：
     *   调用方必须显式传入真实健康结论，不传就说明它没检查——
     *   那样严格模式会显示「监管中」却拦不住。所以这里**不给默认值**。
     * @param problems 能力不到位的原因。
     * @param paused 全局休假（用户的另一条退出通道，优先级高于模式）。
     * @param emergencyUntil 紧急放行的截止时刻（与模式里的紧急暂停是两回事：
     *   那个是「停止约束」，这个是「限时放行」，都由用户发起）。
     */
    fun verdict(
        mode: ModeConfig,
        now: Long,
        healthy: Boolean,
        problems: List<String> = emptyList(),
        paused: Boolean = false,
        emergencyUntil: Long? = null,
    ): ModeVerdict {
        // 严格窗口本身是否在有效期内（只看模式，不看健康）。
        val windowOn = mode.tier == ModeConfig.Tier.STRICT &&
            mode.startsAt > 0 &&
            mode.startsAt <= now &&
            now < mode.endsAt

        val emergencyPaused = windowOn && mode.emergencyAt > 0

        // 该不该约束：窗口有效 + 没紧急暂停 + 没休假 + 没在限时放行里。
        // 健康**不参与**这个判定——拦不住是拦不住，不能因此把模式改成普通。
        val enforcing = windowOn &&
            !emergencyPaused &&
            !paused &&
            (emergencyUntil == null || emergencyUntil <= now)

        return ModeVerdict(
            enforcing = enforcing,
            healthy = healthy,
            problems = problems,
            tier = mode.tier,
            endsAt = if (windowOn) mode.endsAt else 0L,
            revision = mode.revision,
            emergencyPaused = emergencyPaused,
            now = now,
        )
    }
}
