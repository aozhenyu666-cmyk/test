// dsh-go-companion · [Boss-Wall Path B] 端内第 3 独立冷血主管会话（wallSessionId）纯函数模块
//
// 设计原则：
// 1. 与两条鱼（鱼丸 companion / 鲸澜 strategist）物理隔离：不共享人设、不触发伴随说话、不联动静音/拒绝/做题/判球；
// 2. 只有 4 个只读审计工具（wall_boss_brief / wall_locks_truth / wall_vault_search / wall_vault_recent），
//    零解锁、零加额度、零豁免、零放松权限；
// 3. 如日后用户选定只留路径 A，可直接删除本文件并移除 index.js/runtime_io.js/UI 中标记 [Boss-Wall Path B] 的段落。

export const MAX_WALL_BOSS_HISTORY = 60

export const WALL_BOSS_AUDIT_TRIGGER_TEXT =
  '开始一次老板全局审视：不要客套，先核对今日与近 7 天客观事实账本，给出不超过 2 句的态势定性、3 个直击逃避模式的尖锐追问，以及 1 条未来 24 小时唯一可验的止损硬指令。'

export const WALL_BOSS_PERSONA_TEXT = [
  '【身份设定：端内「主管/老板型墙」（第 3 独立审计会话 · 非伴侣 · 非谋士姐妹）】',
  '你是用户的冷血直属主管与事实审计官。你独立于陪伴妹妹（鱼丸）与谋士姐姐（鲸澜）存在：',
  '- 严禁寒暄、安慰、共情、画饼或喊「加油/你可以的/状态会好的」等虚词。',
  '- 你没有任何解锁、加娱乐额度、豁免验收、减半保底或关闭默认防分心锁的权力；遇到求情直接指回手机正规交付/验收出口。',
  '- 先核事实、再下定性、最后追问与下令。严禁只盯着单一微小时段打转，必须以全局 7 天兑现率、今日正事/娱乐/折腾工具耗时比、撞锁与紧急放行次数、跳票计划块和悬空承诺为依据。',
  '- 拒收「明天一定」「差不多」「太累了」「状态不好」等无验证标准借口；直接追问卡点动作与可验收物理证据。',
  '',
  '【标准输出结构】',
  '1. 全局定性（≤2 句，只讲事实数字与代价）；',
  '2. 3 个直击逃避模式的尖锐追问（承诺跳票 / 娱乐与工具折腾替代硬骨头 / 多次撞锁与应急绕过）；',
  '3. 1 条未来 24 小时唯一可验、不可讨价还价的止损硬指令。',
].join('\n')

/**
 * 校验并标准化独立主管会话 ID：必须非空、<=256 字符、且不得与陪伴/谋士会话重合。
 */
export function validateWallSessionId(sid, { sessionId = '', strategistSessionId = '' } = {}) {
  const clean = typeof sid === 'string' ? sid.trim() : ''
  if (!clean) return { ok: false, error: 'empty-session-id', text: '会话 ID 不能为空' }
  if (clean.length > 256) return { ok: false, error: 'bad-session-id', text: '会话 ID 长度不合法' }
  if (sessionId && clean === String(sessionId).trim()) {
    return { ok: false, error: 'same-as-companion', text: '主管墙不能共用陪伴妹妹会话，请新建或选择第 3 个独立会话' }
  }
  if (strategistSessionId && clean === String(strategistSessionId).trim()) {
    return { ok: false, error: 'same-as-strategist', text: '主管墙不能共用谋士姐姐会话，请新建或选择第 3 个独立会话' }
  }
  return { ok: true, sessionId: clean }
}

export function isWallBossSession(sid, config) {
  const s = typeof sid === 'string' ? sid.trim() : String(sid || '').trim()
  const w = typeof config?.wallSessionId === 'string' ? config.wallSessionId.trim() : ''
  return Boolean(s && w && s === w)
}

function numOr(v, d = 0) {
  return Number.isFinite(Number(v)) ? Number(v) : d
}

/**
 * 拼接只读客观全局事实账本（挂载在主管会话 systemPrompt order:860，供每轮问答实时引用）。
 */
export function buildWallBossLedgerText(bossBrief = null, locksOverview = null) {
  const b = bossBrief && typeof bossBrief === 'object' ? bossBrief : {}
  const snap = b.snapshot && typeof b.snapshot === 'object' ? b.snapshot : {}
  const rateTxt = snap.fulfillmentRatePct === null || snap.fulfillmentRatePct === undefined
    ? '暂无足够样本'
    : `${snap.fulfillmentRatePct}%（${numOr(snap.fulfillmentDone)}/${numOr(snap.fulfillmentTotal)}）`
  const sealStats = snap.sealStats7d && typeof snap.sealStats7d === 'object' ? snap.sealStats7d : {}
  const blockStats = snap.planBlocksSummary && typeof snap.planBlocksSummary === 'object' ? snap.planBlocksSummary : {}

  const ov = locksOverview && typeof locksOverview === 'object' ? locksOverview : {}
  const hands = Array.isArray(ov.hands) ? ov.hands : []
  const winner = hands.find((h) => h && h.isWinner) || null
  const ed = ov.effectiveDecision && typeof ov.effectiveDecision === 'object' ? ov.effectiveDecision : {}
  const winnerLine = winner
    ? `${winner.name}（${winner.id} / ${ed.mode || 'allowlist'} / 剩余 ${numOr(winner.remainingMin)}m）`
    : (ov.activeLock?.active ? `${ov.activeLock.label || ov.activeLock.source}（${ed.mode || 'allowlist'}）` : '无严格锁发力（自由态）')
  const li = ov.lastIntercept && typeof ov.lastIntercept === 'object' ? ov.lastIntercept : null
  const lastInterceptLine = li
    ? `${li.label || li.pkg}（被 ${li.handName || li.handId} 拦截：${li.reason || '策略限制'}）`
    : '暂无最近拦截'

  const commitments = Array.isArray(snap.pendingCommitments) ? snap.pendingCommitments : []
  const gaps = Array.isArray(b.keyGaps) ? b.keyGaps : []

  return [
    '[老板全局事实账本·实时只读]',
    `日期：${b.day || snap.day || '今日'} | 一句话态势：${b.headline || '暂无'}`,
    `近 7 天兑现率：${rateTxt}（封印过 ${numOr(sealStats.done)}/${numOr(sealStats.total)}，未过保底 ${numOr(sealStats.missed)}，豁免 ${numOr(sealStats.waived)}）`,
    `今日时间投向：正事 ${numOr(snap.todayWorkMin)}m（主线 ${numOr(snap.todayMainlineMin)}m / 网课 ${numOr(snap.todayVideoStudyMin)}m / Gemini ${numOr(snap.todayGeminiMin)}m） | 纯娱乐 ${numOr(snap.todayFunMin)}m | 折腾工具 ${numOr(snap.todayTinkerMin)}m`,
    `计划块执行：今日完成 ${numOr(blockStats.todayDone)}/${numOr(blockStats.todayTotal)}（跳过 ${numOr(blockStats.todaySkipped)}），昨日遗留未完 ${numOr(blockStats.yesterdayUnfinished)} 块`,
    `门禁与破戒：近 7 日被拦 ${numOr(snap.blockedCount)} 次 | 紧急/破窗 ${numOr(snap.emergencyPassCount)} 次 | 违约 ${numOr(snap.breachCount)} 条`,
    `此刻锁机真相：主发力手 ${winnerLine} | 最近拦截：${lastInterceptLine}`,
    `悬空承诺（${numOr(snap.pendingCommitmentsCount)} 项）：${commitments.length ? commitments.map((c) => c.title).filter(Boolean).join('；') : '无'}`,
    `系统识别结构缺口：${gaps.length ? gaps.join('；') : '暂无明显结构缺口'}`,
  ].join('\n')
}

const VALID_ROLES = new Set(['user', 'assistant', 'system'])

export function normalizeWallBossHistory(raw) {
  const list = Array.isArray(raw) ? raw : []
  const seen = new Set()
  const out = []
  for (const item of list) {
    if (!item || typeof item !== 'object') continue
    const role = VALID_ROLES.has(String(item.role || '')) ? String(item.role) : 'assistant'
    const text = String(item.text || '').trim().slice(0, 4000)
    if (!text) continue
    const id = String(item.id || `${role}-${numOr(item.at, out.length)}-${out.length}`).trim().slice(0, 96)
    if (seen.has(id)) continue
    seen.add(id)
    out.push({
      id,
      role,
      text,
      at: numOr(item.at, Date.now()),
      ...(item.requestId ? { requestId: String(item.requestId).slice(0, 80) } : {}),
      ...(item.error === true ? { error: true } : {}),
    })
  }
  return out.slice(-MAX_WALL_BOSS_HISTORY)
}

export function appendWallBossMessage(history, entry) {
  const base = normalizeWallBossHistory(history)
  if (!entry || typeof entry !== 'object') return base
  const role = VALID_ROLES.has(String(entry.role || '')) ? String(entry.role) : 'assistant'
  const text = String(entry.text || '').trim().slice(0, 4000)
  if (!text) return base
  const id = String(entry.id || `${role}-${Date.now()}-${base.length}`).trim().slice(0, 96)
  if (base.some((m) => m.id === id)) return base
  const next = [
    ...base,
    {
      id,
      role,
      text,
      at: numOr(entry.at, Date.now()),
      ...(entry.requestId ? { requestId: String(entry.requestId).slice(0, 80) } : {}),
      ...(entry.error === true ? { error: true } : {}),
    },
  ]
  return next.slice(-MAX_WALL_BOSS_HISTORY)
}
