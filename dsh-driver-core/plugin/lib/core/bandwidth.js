// 带宽水位与限速监测：把"回复耗时 + 回复信息量 + 切屏/Live 状态 + 沉默时长"压成四个状态之一。
// 纯函数，不读时钟、不碰 I/O。

export const COG = Object.freeze({
  ACTIVE_FLOW: 'ACTIVE_FLOW', // 有效推进
  HIGH_SPEED_SKIMMING: 'HIGH_SPEED_SKIMMING', // 虚假专注：回得快但没依据
  THROTTLED_EMPTY: 'THROTTLED_EMPTY', // 已限速：沉默、卡壳、机械敷衍
  ESCAPED: 'ESCAPED', // 切屏 / 退出 Live
})

export const DEFAULT_BANDWIDTH = Object.freeze({
  fastMs: 3_000, // 比这快、又没依据 → 滑过去了
  slowMs: 45_000, // 比这慢、内容又薄 → 卡住了
  silenceMs: 60_000, // 这么久没有任何对话迹象 → 脑子空了
})

/**
 * @param {object} o
 * @param {number} o.now
 * @param {boolean} o.inAnchor     前台是否在允许的应用里（Gemini / ChatGPT / DSH Go 等）
 * @param {boolean|null} o.liveActive Live 会话是否还开着；null = 不知道（信号没接上）
 * @param {number} o.lastSignalAt  最后一次"你在对话"的迹象：回复驾驶员，或 Live 那边有人在说话
 * @param {{latencyMs:number, features:object, expects:string}|null} o.lastReply 最近一次回复（只算还没被判过的）
 * @param {Array<{latencyMs:number, features:object}>} o.recent 最近几次回复，用来看趋势
 * @param {object} [cfg]
 * @returns {{state:string, reason:string}}
 */
export function classify(o, cfg = DEFAULT_BANDWIDTH) {
  if (!o.inAnchor) return { state: COG.ESCAPED, reason: 'left_anchor_app' }
  if (o.liveActive === false) return { state: COG.ESCAPED, reason: 'live_ended' }

  const r = o.lastReply
  if (r) {
    const f = r.features
    if (f.giveUp) return { state: COG.THROTTLED_EMPTY, reason: 'gave_up' }
    if (f.filler) return { state: COG.THROTTLED_EMPTY, reason: 'filler' }
    // 选择题式的原子动作，点了就算完成，不拿速度判它
    if (r.expects === 'choice' && f.choice) return { state: COG.ACTIVE_FLOW, reason: 'atomic_done' }
    if (f.evidence || f.anchorHit) {
      return { state: COG.ACTIVE_FLOW, reason: f.anchorHit ? 'anchor_hit' : 'has_basis' }
    }
    if (r.latencyMs <= cfg.fastMs) return { state: COG.HIGH_SPEED_SKIMMING, reason: 'fast_no_basis' }
    if (r.latencyMs >= cfg.slowMs && f.score < 0.4) return { state: COG.THROTTLED_EMPTY, reason: 'slow_thin' }
    // 不快不慢、也没依据：这句本身很短，且最近三次里两次没依据，就算在滑
    const thin = (o.recent ?? []).filter((x) => !x.features.evidence && !x.features.anchorHit && !x.features.choice).length
    if (f.chars < 6 && thin >= 2) return { state: COG.HIGH_SPEED_SKIMMING, reason: 'thin_trend' }
    return { state: COG.ACTIVE_FLOW, reason: 'plain_reply' }
  }

  if (o.now - o.lastSignalAt >= cfg.silenceMs) return { state: COG.THROTTLED_EMPTY, reason: 'silence' }
  return { state: COG.ACTIVE_FLOW, reason: 'talking' }
}
