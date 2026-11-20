// dsh-go-companion · 五套任务队列桥接 & P0-P6 统一锁裁决器 (V5)
//
// 解决原 index.js 七重锁叠床架屋、五套任务队列一女五嫁的问题：
// 1. computeUnifiedLockArbiter: 严格 P0 -> P6 优先级，输出唯一可解释锁态与解锁出口；
// 2. computeUnifiedTodayTasks: 合并 plan.json / daily.json / bouts / plan-contract.json 为统一去重队列；
// 3. resolveActiveSisterSkin: 双生声画联动（鱼丸 yuwan-girl / 鲸澜 jinglan-girl）。

const num = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)

export const LOCK_PRIORITIES = Object.freeze([
  'EMERGENCY_SUSPEND',
  'GEMINI_LIVE_CELL',
  'CONTRACT_OVERDUE_STRICT',
  'TAKEOVER_STRICT',
  'BOUT_STRICT',
  'NIGHT_CURFEW',
  'CONTRACT_FLEX',
  'AUTONOMY_RELAXED',
])

export function computeUnifiedLockArbiter({
  emergencyPass = null,
  geminiSession = null,
  aiWorkSession = null,
  takeoverSession = null,
  contractSession = null,
  taskLease = null,
  presetStrict = null,
  wall = null,
  autonomy = null,
  lockRequests = [],
  now = Date.now(),
} = {}) {
  const reqs = (Array.isArray(lockRequests) ? lockRequests : []).filter((r) => r && (!r.until || num(r.until) > now))
  const reqOf = (src) => reqs.find((r) => r.source === src)

  const openReq = reqs.find((r) => r.kind === 'open')
  const emergencyActive = Boolean(
    openReq ||
    (emergencyPass && (num(emergencyPass.until) > now || (emergencyPass.active === true && !emergencyPass.until))),
  )
  if (emergencyActive) {
    return {
      active: false,
      priority: 'P0',
      source: 'EMERGENCY_SUSPEND',
      label: '紧急/刷题通行放行中',
      why: String(emergencyPass?.reason || openReq?.why || '已启用临时紧急通行，暂挂所有娱乐拦截'),
      allow: ['*'],
      unlockHow: String(openReq?.unlockHow || '通行证倒计时结束后自动恢复原护栏状态'),
    }
  }

  const gemReq = reqOf('gemini-cell')
  if ((geminiSession && (!geminiSession.until || num(geminiSession.until) > now)) || gemReq) {
    const src = geminiSession || gemReq
    return {
      active: true,
      priority: 'P1',
      source: 'GEMINI_LIVE_CELL',
      label: 'Gemini 共脑/审讯连麦舱',
      why: String(src?.topic ? `正在连麦「${src.topic}」` : (src?.why || 'Gemini Live 连麦中，只放行 Gemini 与急事底线')),
      allow: Array.isArray(src?.allow) ? src.allow : [],
      unlockHow: String(gemReq?.unlockHow || '完成连麦并提交【复盘】，或连麦到期后自动解除'),
    }
  }

  const contractStrictActive = Boolean(
    contractSession && contractSession.strict === true && (!contractSession.until || num(contractSession.until) > now),
  )
  const aiWorkIsTakeover = Boolean(aiWorkSession && aiWorkSession.by === 'takeover')
  const aiWorkStrictActive = Boolean(
    aiWorkSession && !aiWorkIsTakeover && aiWorkSession.strict !== false && (!aiWorkSession.until || num(aiWorkSession.until) > now),
  )
  const contractReq = reqOf('contract')
  if (contractStrictActive || aiWorkStrictActive || contractReq?.kind === 'allowlist') {
    const src = contractStrictActive ? contractSession : (aiWorkStrictActive ? aiWorkSession : contractReq)
    // 封印到点没过保底走同一档（P2），只是说法不同：解法是把保底交上。
    const bySeal = contractStrictActive && contractSession?.by === 'seal'
    return {
      active: true,
      priority: 'P2',
      source: 'CONTRACT_OVERDUE_STRICT',
      label: bySeal ? '封印没过保底' : (contractStrictActive ? '契约逾期严格锁定' : '专时严格锁定'),
      why: String(src?.why || '计划验收点已超 1 分钟缓冲未交付，已自动剥离娱乐/视频仅保留工作工具'),
      allow: Array.isArray(src?.allow) ? src.allow : [],
      unlockHow: bySeal
        ? '把保底交上就解（最长 60 分钟）；真有事可以写理由豁免，占一次紧急解除'
        : String(contractReq?.unlockHow || '提交结构化因果提炼并通过高强度验收即可立即解封'),
    }
  }

  // P2.5：接管强制回位（高于专注严格局 P3，低于契约逾期 P2）
  const takeoverReq = reqOf('takeover')
  const tkSrc = (takeoverSession && (!takeoverSession.until || num(takeoverSession.until) > now))
    ? takeoverSession
    : (aiWorkIsTakeover && (!aiWorkSession.until || num(aiWorkSession.until) > now) ? aiWorkSession : takeoverReq)
  if (tkSrc) {
    return {
      active: true,
      priority: 'P2.5',
      source: 'TAKEOVER_STRICT',
      label: '接管强制回位',
      why: String(tkSrc?.why || '系统已接管：请立即回到指定正事应用'),
      allow: Array.isArray(tkSrc?.allow) ? tkSrc.allow : [],
      unlockHow: String(tkSrc?.unlockHow || '回到指定正事应用即自动解除接管（或到期/使用紧急解除）'),
    }
  }

  const presetReq = reqOf('preset')
  const wallReq = reqOf('wall')
  const presetActive = Boolean((presetStrict && (!presetStrict.until || num(presetStrict.until) > now)) || presetReq)
  const dayWallLocked = Boolean((wall?.locked === true && wall?.kind !== 'night' && wall?.night !== true) || wallReq)
  if (presetActive || dayWallLocked) {
    const pSrc = presetStrict || presetReq
    const byVerify = presetActive && presetStrict?.by === 'verify'
    return {
      active: true,
      priority: 'P3',
      source: 'BOUT_STRICT',
      label: byVerify ? '默认验收（工作时段）' : presetActive ? '专注严格局护栏' : '时段防分心护栏',
      why: String(pSrc?.why || wall?.why || wallReq?.why || '当前处于严格专注锁定，仅放行声明白名单与急事底线'),
      allow: Array.isArray(pSrc?.allow) ? pSrc.allow : (Array.isArray(wallReq?.allow) ? wallReq.allow : []),
      unlockHow: byVerify
        ? '时段结束 / 吃饭时段自动松；要玩就跟鱼说一句理由，花挣来的余额'
        : presetActive
        ? '完成本局收工验收，或在锁屏答对 10 道真题换取 5 分钟通行'
        : '开启契约护航局或进入约定放风时段后自动解锁',
    }
  }

  const nightReq = reqOf('night')
  if ((wall?.locked === true && (wall?.kind === 'night' || wall?.night === true)) || nightReq) {
    return {
      active: true,
      priority: 'P4',
      source: 'NIGHT_CURFEW',
      label: '深夜睡眠护栏',
      why: String(wall?.why || nightReq?.why || '已进入深夜催睡时段，娱乐类应用全面锁定'),
      allow: Array.isArray(wall?.allow) ? wall.allow : (Array.isArray(nightReq?.allow) ? nightReq.allow : []),
      unlockHow: String(nightReq?.unlockHow || '次日晨间 07:00 自动解封（电话/短信/地图/支付始终放行）'),
    }
  }

  const flexContract = Boolean(
    contractSession && !contractSession.strict && (!contractSession.until || num(contractSession.until) > now),
  )
  const leaseReq = reqOf('lease')
  const leaseActive = Boolean((taskLease && (!taskLease.until || num(taskLease.until) > now)) || leaseReq)
  const flexAiWork = Boolean(aiWorkSession && aiWorkSession.strict === false && (!aiWorkSession.until || num(aiWorkSession.until) > now))
  if (flexContract || leaseActive || flexAiWork) {
    const src = flexContract ? contractSession : (leaseActive ? (taskLease || leaseReq) : aiWorkSession)
    return {
      active: false,
      priority: 'P5',
      source: 'CONTRACT_FLEX',
      label: '契约柔性护航期',
      why: String(src?.why || '契约护航中：学习白名单完整放行，到点前不打扰'),
      allow: Array.isArray(src?.allow) ? src.allow : [],
      unlockHow: '到点提交验收通过即可升级自律等级（L2/L3）',
    }
  }

  const relaxed = Boolean(autonomy?.unlockedRelaxation)
  return {
    active: false,
    priority: 'P6',
    source: 'AUTONOMY_RELAXED',
    label: relaxed ? `战绩松绑（${autonomy.level || 'L2'}）` : '自主推进态',
    why: relaxed
      ? String(autonomy.autoRewardLabel || '今日战绩已达标，常规巡检冷却自动放宽')
      : '当前未触发任何严格锁定，可随时开启专注局或契约计划',
    allow: ['*'],
    unlockHow: '当前无拦截，保持主线推进节奏即可',
  }
}

function normTitleKey(title) {
  return String(title || '')
    .trim()
    .toLowerCase()
    .replace(/[\s\-_·.,，。！？!?:：()（）【】[\]]+/g, '')
}

const STATUS_RANK = {
  passed: 4,
  done: 4,
  running: 3,
  overdue_locked: 2,
  pending: 1,
}

export function computeUnifiedTodayTasks({
  planItems = [],
  dailyTasks = [],
  boutQueue = [],
  contract = null,
  horizons = null,
} = {}) {
  const horizonMap = new Map()
  for (const h of [...(horizons?.long || []), ...(horizons?.medium || [])]) {
    if (h?.id) horizonMap.set(String(h.id), String(h.title || ''))
  }

  const byKey = new Map()
  const addItem = (raw) => {
    const title = String(raw?.title || '').trim()
    if (!title) return
    const key = normTitleKey(title) || String(raw?.id || title)
    const existing = byKey.get(key)
    const status = String(raw?.status || 'pending')
    const allowApps = Array.isArray(raw?.allowApps) ? raw.allowApps.filter(Boolean) : []
    const parentHorizonId = String(raw?.parentHorizonId || '').trim()
    if (!existing) {
      byKey.set(key, {
        id: String(raw?.id || `ut-${byKey.size + 1}`),
        title,
        source: String(raw?.source || 'plan'),
        sources: [String(raw?.source || 'plan')],
        minutes: Math.max(0, Math.round(num(raw?.minutes, 30))),
        dept: String(raw?.dept || ''),
        protocolId: String(raw?.protocolId || 'execute'),
        parentHorizonId,
        horizonTitle: parentHorizonId ? (horizonMap.get(parentHorizonId) || '') : '',
        status,
        dueAt: num(raw?.dueAt, 0),
        allowApps,
      })
      return
    }
    if (!existing.sources.includes(raw.source)) existing.sources.push(raw.source)
    if (num(raw?.minutes) > existing.minutes) existing.minutes = Math.round(num(raw.minutes))
    if (!existing.dept && raw?.dept) existing.dept = String(raw.dept)
    if (!existing.parentHorizonId && parentHorizonId) {
      existing.parentHorizonId = parentHorizonId
      existing.horizonTitle = horizonMap.get(parentHorizonId) || existing.horizonTitle
    }
    if (!existing.dueAt && num(raw?.dueAt) > 0) existing.dueAt = num(raw.dueAt)
    if (allowApps.length) existing.allowApps = [...new Set([...existing.allowApps, ...allowApps])]
    if ((STATUS_RANK[status] || 0) > (STATUS_RANK[existing.status] || 0)) {
      existing.status = status
    }
  }

  for (const it of Array.isArray(planItems) ? planItems : []) {
    addItem({
      id: it?.id,
      title: it?.title || it?.protocolId,
      source: 'plan',
      minutes: it?.minutes ?? it?.budget ?? 45,
      dept: it?.department || it?.dept || '',
      protocolId: it?.protocolId || 'execute',
      parentHorizonId: it?.parentHorizonId || '',
      status: it?.openedAt ? 'running' : (it?.missed ? 'overdue_locked' : 'pending'),
      allowApps: [...(Array.isArray(it?.need) ? it.need : []), ...(Array.isArray(it?.allow) ? it.allow : [])],
    })
  }

  for (const it of Array.isArray(boutQueue) ? boutQueue : []) {
    addItem({
      id: it?.id,
      title: it?.title || it?.protocolId,
      source: 'bout',
      minutes: it?.minutes ?? it?.budget ?? 45,
      dept: it?.department || it?.dept || '',
      protocolId: it?.protocolId || 'execute',
      parentHorizonId: it?.parentHorizonId || '',
      status: it?.openedAt ? 'running' : 'pending',
      allowApps: [...(Array.isArray(it?.need) ? it.need : []), ...(Array.isArray(it?.allow) ? it.allow : [])],
    })
  }

  for (const t of Array.isArray(dailyTasks) ? dailyTasks : []) {
    addItem({
      id: t?.id,
      title: t?.task || t?.title,
      source: 'daily',
      minutes: t?.minutes ?? 30,
      dept: t?.dept || '',
      status: t?.done ? 'done' : 'pending',
    })
  }

  if (contract && typeof contract === 'object') {
    const cps = Array.isArray(contract.checkpoints) ? contract.checkpoints : []
    if (cps.length) {
      for (const cp of cps) {
        addItem({
          id: cp?.id || contract.id,
          title: cp?.title || contract.title,
          source: 'contract',
          minutes: contract.minutes || 45,
          protocolId: 'contract',
          parentHorizonId: contract.horizonId || '',
          status: cp?.status === 'passed' ? 'passed' : (cp?.status === 'overdue_locked' ? 'overdue_locked' : 'pending'),
          dueAt: num(cp?.dueAt),
          allowApps: Array.isArray(contract.allowedApps) ? contract.allowedApps : [],
        })
      }
    } else if (contract.title) {
      addItem({
        id: contract.id,
        title: contract.title,
        source: 'contract',
        minutes: contract.minutes || 45,
        protocolId: 'contract',
        parentHorizonId: contract.horizonId || '',
        status: 'running',
        allowApps: Array.isArray(contract.allowedApps) ? contract.allowedApps : [],
      })
    }
  }

  return [...byKey.values()]
}

export function resolveActiveSisterSkin({ speaker = 'companion', lockArbiter = null } = {}) {
  const prio = String(lockArbiter?.priority || '')
  const src = String(lockArbiter?.source || '')
  if (
    speaker === 'strategist' ||
    prio === 'P1' ||
    prio === 'P2' ||
    prio === 'P2.5' ||
    src === 'GEMINI_LIVE_CELL' ||
    src === 'CONTRACT_OVERDUE_STRICT' ||
    src === 'TAKEOVER_STRICT'
  ) {
    return 'jinglan-girl'
  }
  return 'yuwan-girl'
}
