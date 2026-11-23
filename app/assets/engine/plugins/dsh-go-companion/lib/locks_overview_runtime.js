import { TIER_LABEL } from './app_tiers.js'
import { dayString } from './daily.js'
import { activeAppLease } from './external_agents.js'
import { readLedger } from './ledger.js'
import { NIGHT_LOCK_TO } from './rhythm.js'
import { roleNames } from './roles.js'
import { home, readState, startOfDay, stateFile } from './runtime_io.js'
import { lockingSeal } from './seal.js'
import { hm as zoneHm, minuteOfDay as zoneMinuteOfDay, zoneOr } from './tz.js'
import { DAYTIME_SPEND_CAP_MIN, SPEND_MIN, classifySpendWindow } from './verifier_mode.js'
import { wallDecision } from './wall.js'

/**
 * 锁与余额大盘视图（从 index.js 拆出的独立只读运行态模块）。
 * 将 6 只手仲裁视图、钱包窗口与计划排程明细聚合给 Strategy / Locks UI 与主管墙消费。
 */
export function createLocksOverviewRuntime(deps) {
  const {
    getConfig,
    getLastRhythm,
    getLastPhone,
    getLive,
    getV4Hub,
    loadSeals,
    loadExternal,
    loadGemini,
    activeEmergencyInfo,
    wallCfgNow,
    eveningNow,
    eveningLockUntil,
    presetRt,
    verifySessionView,
    resolveWinnerHandMeta,
    timeLedgerNow,
    blockedNow,
    lockBus,
    refuseUntilAt,
    lockDecisionNow,
    appDisplayLabel,
    loadLastIntercept,
    daytimeSpentMinToday,
    sealHm,
    workStoreNow,
  } = deps

  function formatRecentActionEntry(e, tz) {
    if (!e || typeof e !== 'object') return null
    const st = String(e.state || '')
    const names = roleNames(getConfig())
    const actorOf = (by) => {
      const k = String(by || '').trim().toLowerCase()
      if (k === 'strategist' || k === 'big') return names.strategist || '鲸澜'
      if (k === 'companion' || k === 'sis') return names.companion || '鱼丸'
      if (k === 'sister') return '双鱼'
      if (k === 'radar' || k === 'behavior-radar') return '行为雷达'
      if (k === 'wall') return '墙'
      if (k === 'template') return '模板'
      if (k === 'gemini') return 'Gemini'
      if (k === 'ai') return 'AI'
      if (k === 'reflect') return '夜间反思'
      if (k === 'timeline') return '时间轴'
      if (k === 'panel' || k === 'user' || k === 'app') return '你'
      return by ? String(by) : '系统'
    }
    const targetApps = () => {
      if (Array.isArray(e.labels) && e.labels.length) return e.labels.join('、')
      if (Array.isArray(e.apps) && e.apps.length) return e.apps.map((p) => appDisplayLabel(p)).join('、')
      if (e.pkg) return appDisplayLabel(e.pkg, e.label || '')
      if (e.app) return appDisplayLabel(e.app, e.label || '')
      return ''
    }
    const at = Number(e.at) || 0
    const hm = at ? zoneHm(at, tz) : ''
    let actor = actorOf(e.by)
    let action = ''
    let target = ''
    let reason = String(e.reason || e.why || '').slice(0, 120)

    switch (st) {
      case 'block':
      case 'block_apps':
      case 'dispatch-block':
        action = '临时封禁'
        target = targetApps()
        break
      case 'app-tier-set':
        action = e.loosen ? '调松分层' : (e.to === 'fun' ? '改严为娱乐' : e.to === 'game' ? '改严为游戏' : `分层调为${TIER_LABEL[e.to] || e.to || ''}`)
        target = targetApps()
        break
      case 'app-tiers': {
        const list = [...(Array.isArray(e.tighten) ? e.tighten : []), ...(Array.isArray(e.loosen) ? e.loosen : [])]
        action = '同步分层'
        target = list.map((p) => appDisplayLabel(p)).join('、') || 'App分层.md'
        break
      }
      case 'dispatch-tag-app':
        action = `App打标(${e.roleTag || ''})`
        target = targetApps()
        break
      case 'dispatch-focus-lock':
        action = '开启专时锁'
        target = Array.isArray(e.allow) ? e.allow.map((p) => appDisplayLabel(p)).slice(0, 4).join('、') : ''
        break
      case 'dispatch-unlock-focus':
        action = '解除专时锁'
        target = '专时与临时锁'
        break
      case 'sister-tool':
        action = String(e.tool || e.action || '姐妹动作')
        target = String(e.target || targetApps() || '')
        break
      case 'bout-open':
      case 'bout-start':
      case 'ai-open-bout':
      case 'plan-open':
      case 'drive-auto-open':
      case 'ignite-open':
      case 'timeline-open':
        action = st === 'ai-open-bout' ? '姐妹开局' : st === 'drive-auto-open' || st === 'ignite-open' || st === 'timeline-open' ? '自动开局' : '开启一局'
        target = String(e.goal || e.protocolId || e.toast || e.id || '')
        break
      case 'seal-plan-day':
      case 'seal-add':
      case 'nod-card-confirm':
      case 'checkpoints-by-ai':
        action = st === 'nod-card-confirm' ? '点头确认今日菜单' : '立下验收点'
        target = e.title ? String(e.title) : `共 ${Number(e.n ?? e.added) || 1} 项`
        break
      case 'template-applied':
        actor = '模板'
        action = '自动套用模板'
        target = String(e.template || '今日模板') + (e.recovery ? '（恢复日）' : '')
        reason = e.added ? `新增 ${e.added} 条验收点` : (e.noBank ? '题库未导入跳过题库项' : reason)
        break
      case 'behavior-radar':
      case 'radar-lock':
        actor = '行为雷达'
        action = e.downgradedByHardCap ? '行为提醒(满额降级)' : '行为雷达触发'
        target = targetApps() || String(e.kind || '')
        break
      case 'balance-spend':
        actor = actorOf(e.by || 'panel')
        action = '花余额放松'
        target = `${Number(e.minutes) || 0} 分钟` + (e.window === 'lunch' ? '（午休窗）' : e.window === 'evening' ? '（晚间窗）' : e.window === 'daytime' ? '（白天槽）' : '')
        break
      case 'intensity-tighten':
      case 'intensity-change':
      case 'intensity-promoted':
        action = st === 'intensity-tighten' ? '收紧强度' : '强度调整'
        target = String(e.tier || e.intensity || e.target || '')
        break
      case 'seal-below':
        actor = '墙'
        action = '逾期未过保底严锁'
        target = String(e.id || '')
        reason = `${e.actual ?? 0}/${e.floor ?? 0}`
        break
      case 'seal-pay':
        actor = '墙'
        action = e.tier === 'stretch' ? '验收过拉伸奖余额' : '验收过保底奖余额'
        target = `+${Number(e.minutes) || 0} 分钟`
        break
      case 'seal-waive':
        action = '豁免封印'
        target = String(e.id || '')
        break
      case 'evening-cap-hit':
        actor = '墙'
        action = '晚间上限触顶锁定'
        target = `${e.used ?? 0}/${e.cap ?? 120} 分钟`
        break
      case 'emergency-pass-grant':
        action = '急事 3 分钟通行'
        target = '全应用放行'
        break
      case 'grant-task-lease':
        actor = names.strategist || '鲸澜'
        action = '批双用途通行证'
        target = targetApps() + (e.minutes ? ` (${e.minutes}m)` : '')
        reason = String(e.goal || reason || '')
        break
      case 'takeover-start':
        actor = '推进器'
        action = '强制接管回位'
        target = '回到正事应用'
        break
      case 'control':
      case 'task-start':
      case 'tighten':
        action = st === 'tighten' ? '今日收紧到零点' : String(e.action || e.mode || '管控动作')
        target = String(e.goal || (e.minutes ? `${e.minutes} 分钟` : ''))
        break
      default:
        return null
    }
    return { at, hm, actor, action, target, reason }
  }

  function buildLocksOverview(now = Date.now(), tz = zoneOr(getConfig().timeZone), sealsOpt = null) {
    const config = getConfig()
    const lastRhythm = getLastRhythm()
    const lastPhone = getLastPhone()
    const live = getLive()
    const v4Hub = getV4Hub()

    const today = dayString(now, tz)
    const min = zoneMinuteOfDay(now, tz)
    const sealsState = sealsOpt || loadSeals(now, tz)
    const todaySeals = (Array.isArray(sealsState?.items) ? sealsState.items : []).filter((x) => x.day === today)
    const overdueSeal = lockingSeal(sealsState, { now, today })
    const overdueContract = Boolean((() => { try { return v4Hub?.contractSessionView?.(now, { escalatedOnly: true }) } catch { return null } })())
    const dayBlockOverdue = (() => { try { return v4Hub?.dayBlockOverdueSessionView?.(now) } catch { return null } })()
    const dayBlockActive = (() => { try { return v4Hub?.dayBlockActiveSessionView?.(now) } catch { return null } })()
    const dayVerify = (() => {
      try {
        return v4Hub?.getDayVerifySummary?.(now, sealsState.items)
      } catch {
        return null
      }
    })() || {
      totalVerify: 0, passedVerify: 0, overdueCount: 0, pendingReviewCount: 0,
      allVerifyCleared: false, nextDueBlock: null, activeVerifyBlock: null, activeStrictBlock: null,
      overdueVerifyBlocks: [], dayBlocks: [],
    }
    const lockArbiter = (() => { try { return v4Hub?.computeLockArbiter?.(now) } catch { return null } })() || {
      active: false, priority: 'P6', source: 'AUTONOMY_RELAXED', label: '自主推进态', why: '', unlockHow: '',
    }
    const verifyOverdue = Boolean(overdueSeal || overdueContract || dayBlockOverdue || lockArbiter?.priority === 'P3' || lockArbiter?.source === 'CONTRACT_OVERDUE_STRICT')

    const activeLock = {
      active: Boolean(lockArbiter?.active),
      priority: String(lockArbiter?.priority || 'P6'),
      source: String(lockArbiter?.source || 'AUTONOMY_RELAXED'),
      label: String(lockArbiter?.label || '自主推进态'),
      why: String(lockArbiter?.why || ''),
      unlockHow: String(lockArbiter?.unlockHow || ''),
      verifyOverdue,
      triggerTitle: String(lockArbiter?.triggerTitle || dayBlockOverdue?.topic || dayBlockActive?.topic || ''),
    }

    const ext = loadExternal()
    const emInfo = activeEmergencyInfo(now, tz)
    const emActive = Boolean(emInfo)
    const wdRaw = wallDecision(wallCfgNow(), now, tz)
    const nightLocked = Boolean(lastRhythm?.night === 'lock' || (wdRaw.locked && wdRaw.kind === 'night'))
    const ev = eveningNow(now, tz)
    const evLockUntil = eveningLockUntil(now, tz)
    const evLock = evLockUntil > 0
    const gem = loadGemini()
    const gemSessionActive = Boolean((gem?.session && (!gem.session.until || Number(gem.session.until) > now)) || lockArbiter.priority === 'P2')
    const aiWorkStrict = Boolean(config.aiWorkSession && config.aiWorkSession.by !== 'takeover' && config.aiWorkSession.strict !== false && (!config.aiWorkSession.until || Number(config.aiWorkSession.until) > now))
    const drive = readState(stateFile('drive.json'), {}) || {}
    const tkActive = Boolean(drive?.takeover?.active || (config.aiWorkSession?.by === 'takeover' && (!config.aiWorkSession.until || Number(config.aiWorkSession.until) > now)) || lockArbiter.priority === 'P3.5')
    const presetSess = presetRt.sessionView(now)
    const dayWorkWallEnabled = config.dayWorkWallEnabled !== false && config.verifyMode?.enabled !== false
    const verifySess = dayWorkWallEnabled ? verifySessionView(now) : null
    const dayWallLocked = Boolean(dayWorkWallEnabled && wdRaw.locked && wdRaw.kind !== 'night')
    const contractView = (() => { try { return v4Hub?.contractSessionView?.(now) } catch { return null } })()
    const flexContract = Boolean(contractView && !contractView.strict)
    const taskLeaseActive = Boolean(config.taskLease && Number(config.taskLease.until) > now)
    const extLease = activeAppLease(ext, now)
    const flexAiWork = Boolean(config.aiWorkSession && config.aiWorkSession.strict === false && (!config.aiWorkSession.until || Number(config.aiWorkSession.until) > now))
    const remMinOf = (untilMs) => (Number(untilMs) > now ? Math.max(1, Math.ceil((Number(untilMs) - now) / 60_000)) : 0)
    const nightUntilFallback = (() => {
      if (!nightLocked && !evLock) return 0
      if (Number(lastRhythm?.nightUntil) > now) return Number(lastRhythm.nightUntil)
      if (Number(evLockUntil) > now) return Number(evLockUntil)
      const day0 = startOfDay(now, tz)
      const target = day0 + NIGHT_LOCK_TO * 60_000
      return target > now ? target : target + 86_400_000
    })()
    const manualBoutStrict = Boolean(presetSess && presetSess.by !== 'verify')
    const dayWallActive = Boolean(dayWorkWallEnabled && (verifySess || dayWallLocked))
    const overdueOrTakeover = Boolean(overdueSeal || overdueContract || dayBlockOverdue || aiWorkStrict || tkActive || ['P3', 'P3.5'].includes(lockArbiter.priority))
    const winnerMeta = resolveWinnerHandMeta({
      lockArbiter,
      emActive,
      nightLocked: nightLocked || evLock,
      gemSessionActive,
      overdueOrTakeover,
      manualBoutStrict,
      dayBlockActive: Boolean(dayBlockActive),
      dayWallActive,
    })
    const winnerId = winnerMeta.id
    const tl = (() => {
      try {
        return timeLedgerNow(now, tz, lastPhone)
      } catch {
        return { funLeft: 0, carry: 0, earnedBank: 0, earnedBout: 0, earnedSeal: 0, earnedBreak: 0, earned: 0, penalty: 0, funOut: true, gameOut: true }
      }
    })()
    const activeBlocks = blockedNow(now)
    const busDenies = lockBus.list(now).filter((r) => r.kind === 'deny')
    const refuseOn = refuseUntilAt(now) > now

    const allLocks = [
      {
        id: 'EMERGENCY_SUSPEND',
        priority: 'P0',
        name: '急事 3 分钟通行证',
        active: emActive,
        summary: emActive
          ? `通行放行中（${emInfo?.reason || '急事通行'}），其余拦截暂停`
          : '未开启 · 急事时可激活 3 分钟全放行（每日次数有限）',
        unlockHow: '倒计时 3 分钟结束自动恢复原护栏状态',
      },
      {
        id: 'NIGHT_CURFEW',
        priority: 'P1',
        name: '深夜睡眠 & 晚间上限锁',
        active: nightLocked || evLock || lockArbiter.priority === 'P1',
        summary: nightLocked
          ? '深夜睡眠护栏生效中（00:30–07:00 锁定娱乐）'
          : (evLock ? `晚间娱乐上限（${ev.cap} 分钟）已用完，锁到明早 07:00` : `未触发 · 18:00 起晚间上限 ${ev.cap}m，00:30–07:00 深夜锁`),
        unlockHow: '次日晨间 07:00 自动解封（电话/短信/地图/支付始终放行）',
      },
      {
        id: 'GEMINI_LIVE_CELL',
        priority: 'P2',
        name: 'Gemini 连麦舱',
        active: gemSessionActive,
        summary: gemSessionActive
          ? `连麦进行中${gem?.session?.topic ? '（' + gem.session.topic + '）' : ''}，仅放行 Gemini 与急事底线`
          : '未开启 · 进入 Gemini 共脑连麦时自动收拢',
        unlockHow: '完成连麦并提交复盘，或连麦到期后自动解除',
      },
      {
        id: 'CONTRACT_OVERDUE_STRICT',
        priority: 'P3',
        name: '验收锁（计划块/封印/主管指令）',
        active: Boolean(overdueSeal || overdueContract || dayBlockOverdue || aiWorkStrict || lockArbiter.priority === 'P3'),
        summary: dayBlockOverdue
          ? String(dayBlockOverdue.why)
          : (overdueSeal
              ? `封印「${overdueSeal.title}」逾期未过保底（${overdueSeal.actual}/${overdueSeal.floor}），严锁不可用零散余额绕过`
              : (overdueContract
                  ? '计划契约检查点逾期未交付，已进入严格锁定'
                  : (aiWorkStrict ? String(config.aiWorkSession?.why || '专时严格锁定中') : '未触发 · 验收块 / 封印 / 主管指令到点超 1m 未交货即严锁'))),
        unlockHow: dayBlockOverdue
          ? '提交真实交付物并通过主管墙 / 客观验收立即解封（最长 60m 封顶）'
          : (overdueSeal
              ? '交付证据达到保底线立即解封（或写理由豁免占 1 次紧急解除）'
              : '交付验收达标或完成本轮专时后立即解封'),
      },
      {
        id: 'TAKEOVER_STRICT',
        priority: 'P3.5',
        name: '强制接管回位锁',
        active: tkActive,
        summary: tkActive
          ? String(drive?.takeover?.reason || config.aiWorkSession?.why || '系统已接管，请回到正事应用')
          : '未触发 · 持续偏航失控时自动拉回正事应用',
        unlockHow: '回到指定正事应用（或提交 Gemini 复盘）后自动解除',
      },
      {
        id: 'BOUT_STRICT',
        priority: 'P4',
        name: '专心局 / 计划时间块 & 防分心锁',
        active: Boolean(manualBoutStrict || dayBlockActive || dayWallActive || lockArbiter.priority === 'P4'),
        summary: manualBoutStrict
          ? String(presetSess.why || '专注严格局进行中，仅放行工作白名单')
          : (dayBlockActive
              ? String(dayBlockActive.why)
              : (verifySess
                  ? '工作时段防分心锁生效中（放行全部工作 App，拦截 fun/game）'
                  : (dayWallLocked ? String(wdRaw.why || '防分心时段护栏生效中') : '未触发 · 到计划块时自动接管，或工作时段默认守护'))),
        unlockHow: manualBoutStrict
          ? '完成本局收工验收，或撤回手动严格锁 / 花可用余额'
          : (dayBlockActive
              ? '提交当前时间块交付物过关，或等待本块到达结束时刻'
              : '12:00–14:00 午休 / 饭点自动放风 / 一键关闭默认防分心锁'),
      },
      {
        id: 'CONTRACT_FLEX',
        priority: 'P5',
        name: '契约柔性护航 / 外挂租约',
        active: Boolean(flexContract || taskLeaseActive || extLease || flexAiWork || lockArbiter.priority === 'P5'),
        summary: taskLeaseActive
          ? `任务短租约放行中（${config.taskLease.goal || appDisplayLabel(config.taskLease.app)}）`
          : (flexContract
              ? `契约柔性护航期（${contractView.topic}），学习白名单不打扰`
              : (extLease ? `外脑租约放行中（${extLease.agentId || '外部工具'}）` : '未触发 · 签下契约或申请双用途 App 短租约时生效')),
        unlockHow: '到点交付验收或租约到期后自动结束',
      },
      {
        id: 'AUTONOMY_RELAXED',
        priority: 'P6',
        name: '娱乐余额门槛 & 行为雷达 / 双鱼临时封禁',
        active: Boolean(tl.funOut || tl.gameOut || activeBlocks.length > 0 || busDenies.length > 0 || refuseOn),
        summary: (activeBlocks.length || busDenies.length)
          ? `临时封禁 ${activeBlocks.length || busDenies.length} 个应用${tl.funOut ? ' · 今日娱乐余额已耗尽' : ''}`
          : (refuseOn
              ? '拒绝模式生效中：娱乐应用打开即锁'
              : (tl.funOut ? '当前可用娱乐余额为 0 分钟（刷题/交验收可挣额度）' : '自由余额门槛正常 · 行为雷达与双鱼临时封禁待命')),
        unlockHow: '过一局/验收/刷题挣余额，或待临时封禁倒计时到期',
      },
    ]

    const boutOrBlockActive = Boolean(manualBoutStrict || dayBlockActive)
    const p3UntilMs = Number(dayBlockOverdue?.until || overdueSeal?.lockUntil || config.aiWorkSession?.until || drive?.takeover?.until || 0)
    const p4UntilMs = Number(manualBoutStrict ? presetSess?.until : (dayBlockActive ? dayBlockActive.until : 0)) || 0

    const hands = [
      {
        id: 'EMERGENCY_SUSPEND',
        name: '急事 3 分钟通行证',
        priority: 'P0',
        active: emActive,
        isWinner: winnerId === 'EMERGENCY_SUSPEND',
        by: String(emInfo?.by || 'user'),
        until: Number(emInfo?.until) || 0,
        remainingMin: remMinOf(emInfo?.until),
        canToggle: false,
        duty: '只管突发急事 3 分钟全放行；每日限次，不管常规解锁',
        triggerTitle: emActive ? String(emInfo?.reason || '急事通行') : '',
        summary: emActive ? `放行中：${emInfo?.reason || '急事通行'}` : '待命 · 急事时申请 3 分钟全放行',
      },
      {
        id: 'NIGHT_CURFEW',
        name: '深夜睡眠 & 晚间上限锁',
        priority: 'P1',
        active: Boolean(nightLocked || evLock || lockArbiter.priority === 'P1'),
        isWinner: winnerId === 'NIGHT_CURFEW',
        by: evLock ? 'evening-cap' : 'rhythm',
        until: nightUntilFallback,
        remainingMin: remMinOf(nightUntilFallback),
        canToggle: false,
        duty: '只管 00:30–07:00 防熬夜与 18:00 后晚间娱乐封顶',
        triggerTitle: nightLocked ? '深夜睡眠保护' : (evLock ? `晚间 ${ev.cap}m 上限耗尽` : ''),
        summary: nightLocked
          ? '深夜睡眠锁生效中（00:30–07:00 锁定娱乐）'
          : (evLock ? `晚间娱乐上限（${ev.cap}m）已用完，锁至明早 07:00` : `未触发 · 00:30–07:00 深夜锁 / 晚间上限 ${ev.cap}m`),
      },
      {
        id: 'GEMINI_LIVE_CELL',
        name: 'Gemini 连麦舱',
        priority: 'P2',
        active: gemSessionActive,
        isWinner: winnerId === 'GEMINI_LIVE_CELL',
        by: String(gem?.session?.by || 'gemini'),
        until: Number(gem?.session?.until) || 0,
        remainingMin: remMinOf(gem?.session?.until),
        canToggle: false,
        duty: '只管 Gemini 连麦思考期间白名单收拢，结束即松',
        triggerTitle: gemSessionActive ? String(gem?.session?.topic || 'Gemini 连麦') : '',
        summary: gemSessionActive ? `连麦进行中${gem?.session?.topic ? '（' + gem.session.topic + '）' : ''}` : '未开启 · 仅连麦时收拢为连麦白名单',
      },
      {
        id: 'CONTRACT_OVERDUE_STRICT',
        name: '验收锁（计划块/封印/主管指令）',
        priority: tkActive && !overdueSeal && !overdueContract && !dayBlockOverdue && !aiWorkStrict ? 'P3.5' : 'P3',
        active: overdueOrTakeover,
        isWinner: winnerId === 'CONTRACT_OVERDUE_STRICT',
        by: dayBlockOverdue ? dayBlockOverdue.by : (overdueSeal ? 'seal' : (tkActive ? 'takeover' : 'contract')),
        until: p3UntilMs,
        remainingMin: remMinOf(p3UntilMs),
        canToggle: false,
        duty: '自律核心 · 只管计划验收块 / 封印 / 主管指令到点交货与逾期严锁',
        triggerTitle: String(dayBlockOverdue?.topic || overdueSeal?.title || contractView?.topic || drive?.takeover?.reason || ''),
        summary: dayBlockOverdue
          ? String(dayBlockOverdue.why)
          : (overdueSeal
              ? `封印「${overdueSeal.title}」逾期未过保底${tkActive ? ' · TAKEOVER_STRICT 接管生效中' : ' · TAKEOVER_STRICT 待命'}`
              : (tkActive
                  ? `TAKEOVER_STRICT 强制接管回位生效中（${drive?.takeover?.reason || '请回到正事'}）`
                  : (overdueContract ? '契约逾期严格锁生效中（TAKEOVER_STRICT 待命）' : '未触发 · 计划验收块 / 封印 / 主管指令到点未交即锁'))),
      },
      {
        id: 'BOUT_STRICT',
        name: '专注严格局（手动/计划块自动接管）',
        priority: 'P4',
        active: boutOrBlockActive,
        isWinner: winnerId === 'BOUT_STRICT',
        by: manualBoutStrict ? String(presetSess?.by || 'panel') : (dayBlockActive ? 'dayblock_active' : 'panel'),
        until: p4UntilMs,
        remainingMin: remMinOf(p4UntilMs),
        canToggle: Boolean(manualBoutStrict && !verifyOverdue && !(nightLocked || evLock)),
        duty: '只管当前进行中的专注块或手动专注局（到点即结束）',
        triggerTitle: String((manualBoutStrict ? presetSess?.topic : dayBlockActive?.topic) || ''),
        summary: manualBoutStrict
          ? String(presetSess?.why || '手动/预案严格局锁定中')
          : (dayBlockActive
              ? String(dayBlockActive.why)
              : '未开启 · 到计划时间块开始时刻自动接管，或在专注舱手动启动'),
      },
      {
        id: 'DAY_WORK_WALL',
        name: '工作时段默认防分心锁（Option A）',
        priority: 'P4',
        active: dayWallActive,
        isWinner: winnerId === 'DAY_WORK_WALL',
        by: 'option-a',
        until: Number(verifySess?.until) || (dayWallLocked ? Number(wdRaw.until) || 0 : 0),
        remainingMin: remMinOf(Number(verifySess?.until) || (dayWallLocked ? Number(wdRaw.until) || 0 : 0)),
        canToggle: true,
        duty: '底网 · 仅在无计划块且非午休工作时段拦纯娱乐，不抢专时与验收锁',
        triggerTitle: dayWallActive ? '工作时段自动防分心' : '',
        summary: !dayWorkWallEnabled
          ? '已手动关闭 · 放行自由推进'
          : (dayWallActive
              ? '生效中（07:00–23:00，12:00–14:00 午休放风）：放行全部工作 App，仅拦截 fun/game'
              : '已开启（当前处于午休 / 饭点 / 花余额或夜间窗口，自动休眠）'),
      },
    ]

    const effArb = emActive
      ? { priority: 'P0', source: 'EMERGENCY_SUSPEND', label: '紧急放行中', why: emInfo?.reason || '紧急放行中', unlockHow: '倒计时结束后恢复' }
      : (lockArbiter?.source === 'SISTER_BREAK'
          ? lockArbiter
          : (!dayWorkWallEnabled && lockArbiter.subSource === 'DAY_WORK_WALL'
              ? { priority: 'P6', source: 'AUTONOMY_RELAXED', label: '自主推进态', why: '工作时段默认防分心锁已关闭', allow: ['*'], unlockHow: '当前无拦截' }
              : lockArbiter))
    const effRaw = (() => {
      try {
        return lockDecisionNow(now, tz, lastPhone, effArb, ext)
      } catch {
        return { priority: effArb.priority || 'P6', source: effArb.source || 'AUTONOMY_RELAXED', mode: 'free', allow: [], deny: [], why: effArb.why || '', unlockHow: effArb.unlockHow || '' }
      }
    })()
    const winnerHandObj = hands.find((h) => h.isWinner) || null
    const effUntil = Number(winnerHandObj?.until) || 0
    const effectiveDecision = {
      priority: String(effRaw?.priority || effArb?.priority || 'P6'),
      source: String(effRaw?.source || effArb?.source || 'AUTONOMY_RELAXED'),
      mode: String(effRaw?.mode || 'free'),
      why: String(effRaw?.why || effArb?.why || ''),
      unlockHow: String(effRaw?.unlockHow || effArb?.unlockHow || ''),
      until: effUntil,
      remainingMin: remMinOf(effUntil),
      allow: (Array.isArray(effRaw?.allow) ? effRaw.allow : []).map((pkg) => ({
        pkg: String(pkg),
        label: appDisplayLabel(String(pkg)),
      })),
      deny: (Array.isArray(effRaw?.deny) ? effRaw.deny : []).map((d) => {
        const pkg = String(d?.app || d?.pkg || d || '')
        return {
          pkg,
          label: appDisplayLabel(pkg),
          why: String(d?.why || ''),
        }
      }).filter((d) => d.pkg),
    }
    const lastIntercept = loadLastIntercept()

    const floorPassed = todaySeals.some((x) => x.status === 'floor' || x.status === 'stretch') || Boolean(dayVerify?.allVerifyCleared)
    const daytimeSpentMin = daytimeSpentMinToday(now, tz)
    const daytimeRemainingMin = Math.max(0, DAYTIME_SPEND_CAP_MIN - daytimeSpentMin)
    const eveningRemainingMin = Math.max(0, ev.cap - ev.used)
    const currentWindow = classifySpendWindow(min)
    const currentWindowLabel = currentWindow === 'lunch'
      ? '午休自由时段（12:00–14:00，不占白天 30m）'
      : currentWindow === 'evening'
        ? `晚间自由时段（18:00 起，温和封顶 ${ev.cap}m）`
        : currentWindow === 'night'
          ? (nightLocked ? '深夜休整时段（00:30–07:00 护栏生效）' : '夜间收尾时段（23:00 起，受晚间封顶约束）')
          : (floorPassed ? '白天工作时段（今日保底已过，自由花余额）' : '白天工作时段（零散上限 30m）')

    let spendBlockReason = ''
    if (verifyOverdue) {
      spendBlockReason = '当前有已到点未过保底的验收点（严锁中），请先交付验收或保底再解锁，不可用零散余额绕过'
    } else if (evLock) {
      spendBlockReason = '今晚的娱乐上限已用完，锁到明早 07:00'
    } else if (nightLocked) {
      spendBlockReason = '已进入深夜睡眠护栏（锁到明早 07:00）'
    } else if (currentWindow === 'daytime' && !floorPassed && daytimeRemainingMin < SPEND_MIN) {
      spendBlockReason = '白天零散 30m 已用完（12:00–14:00 午休或 18:00 后可自由花，或先过今日保底解除）'
    } else if (tl.funLeft < SPEND_MIN) {
      spendBlockReason = `可用余额不足 ${SPEND_MIN} 分钟，先交一个验收点或刷题挣余额`
    }

    const nextFreeWindowText = currentWindow === 'daytime'
      ? (min >= 14 * 60 ? '18:00 后' : '12:00–14:00 午休或 18:00 后')
      : currentWindow === 'lunch'
        ? '当前午休自由窗（至 14:00），18:00 后再次自由'
        : currentWindow === 'evening'
          ? `当前晚间自由窗（封顶 ${ev.cap}m）`
          : '明早 07:00 解除深夜锁，12:00–14:00 午休可自由花'

    const sp = config.spendPass && Number(config.spendPass.until) > now ? config.spendPass : null
    const earnedBankMin = Number(tl.earnedBank) || 0
    const earnedTotalMin = Number(tl.earned) || 0
    const earnedWorkMin = Math.max(0, earnedTotalMin - earnedBankMin)

    const wallet = {
      availableMin: Number(tl.funLeft) || 0,
      carryInMin: Number(tl.carry) || 0,
      earnedBankMin,
      earnedBankCap: 60,
      earnedWorkMin,
      earnedTotalMin,
      penaltyMin: Number(tl.penalty) || 0,
      daytimeSpentMin,
      daytimeCapMin: DAYTIME_SPEND_CAP_MIN,
      daytimeRemainingMin,
      eveningSpentMin: Number(ev.used) || 0,
      eveningCapMin: Number(ev.cap) || 120,
      eveningRemainingMin,
      currentWindow,
      currentWindowLabel,
      floorPassed,
      canSpendNow: !spendBlockReason,
      spendBlockReason,
      nextFreeWindowText,
      activeSpendPass: sp
        ? { until: Number(sp.until), minutes: Number(sp.minutes) || 0, reason: String(sp.reason || ''), untilHm: zoneHm(Number(sp.until), tz) }
        : null,
      lockShadow: config.lockShadow !== false,
    }

    const scheduleSlots = todaySeals.map((s) => {
      const startMinute = s.startMinute >= 0 ? s.startMinute : null
      const effStart = startMinute !== null ? startMinute : Math.max(0, s.dueMinute - (Number(s.estMinutes) || 45))
      const isCurrentSlot = Boolean(
        (s.status === 'open' || s.status === 'below') &&
        (live?.sealId === s.id || (min >= effStart && min <= s.dueMinute) || Number(s.lockUntil) > now),
      )
      return {
        id: s.id,
        title: s.title,
        kind: s.kind,
        startMinute,
        dueMinute: s.dueMinute,
        startHm: startMinute !== null ? sealHm(startMinute) : '',
        dueHm: sealHm(s.dueMinute),
        stretch: s.stretch,
        floor: s.floor,
        claimed: s.claimed,
        progress: s.actual,
        status: s.status,
        isCurrentSlot,
      }
    })
    const seenSlotTitles = new Set(scheduleSlots.map((x) => x.title))
    try {
      const ws = workStoreNow(now, tz)
      for (const it of ws?.planItems || []) {
        const title = String(it?.title || it?.protocolId || '').trim()
        if (!title || seenSlotTitles.has(title) || !Number.isFinite(Number(it?.minute))) continue
        const startMinute = Math.max(0, Math.min(1439, Math.round(Number(it.minute))))
        const dur = Math.max(10, Math.round(Number(it.budget || it.minutes || 45)))
        const dueMinute = Math.min(1439, startMinute + dur)
        scheduleSlots.push({
          id: String(it.id || ''),
          title,
          kind: 'plan',
          startMinute,
          dueMinute,
          startHm: sealHm(startMinute),
          dueHm: sealHm(dueMinute),
          stretch: dur,
          floor: dur,
          claimed: 0,
          progress: it.openedAt ? 1 : 0,
          status: it.missed ? 'below' : (it.openedAt ? 'active' : 'open'),
          isCurrentSlot: Boolean(!it.missed && min >= startMinute && min <= dueMinute),
        })
      }
    } catch {}
    scheduleSlots.sort((a, b) => (a.startMinute ?? a.dueMinute) - (b.startMinute ?? b.dueMinute))

    const recentActions = readLedger(home(), 300)
      .map((e) => formatRecentActionEntry(e, tz))
      .filter(Boolean)
      .slice(-20)
      .reverse()

    const allDayBlocks = Array.isArray(dayVerify?.dayBlocks) ? dayVerify.dayBlocks : []
    const formatBlockCard = (b) => ({
      id: b.id,
      title: b.title,
      deliverable: b.deliverable,
      startMin: b.startMin,
      endMin: b.endMin,
      startHm: sealHm(b.startMin),
      dueHm: sealHm(b.endMin),
      lockMode: b.lockMode,
      lockedByBoss: Boolean(b.lockedByBoss),
      source: b.source || 'user',
      status: b.status,
      verifyStatus: b.verifyStatus || 'none',
      submissionText: b.submissionText || '',
      judgeVerdict: b.judgeVerdict || '',
      judgeNote: b.judgeNote || '',
      judgedBy: b.judgedBy || '',
      overdueLockUntil: Number(b.overdueLockUntil) || 0,
      remainingLockMin: remMinOf(b.overdueLockUntil),
      remainingMin: Math.max(0, b.endMin - min),
    })
    const verifyCandidates = allDayBlocks.filter((b) => !b.isBreak && b.status !== 'skipped' && (b.lockMode === 'verify' || b.lockedByBoss))
    const rawCustody = dayVerify?.custody && typeof dayVerify.custody === 'object' ? dayVerify.custody : null
    const verifyLockCenter = {
      totalVerify: Number(dayVerify?.totalVerify) || 0,
      passedVerify: Number(dayVerify?.passedVerify) || 0,
      overdueCount: (dayVerify?.overdueVerifyBlocks || []).length,
      pendingReviewCount: Number(dayVerify?.pendingReviewCount) || 0,
      clearedToday: Boolean(dayVerify?.allVerifyCleared),
      bossDirectiveCount: verifyCandidates.filter((b) => b.lockedByBoss && b.status !== 'done').length,
      custody: rawCustody ? { ...rawCustody, untilHm: sealHm(rawCustody.untilMin) } : null,
      custodyPolicy: dayVerify?.custodyPolicy || null,
      adaptiveSplitsToday: Array.isArray(dayVerify?.adaptiveLog) ? dayVerify.adaptiveLog.length : 0,
      overdueBlocks: (dayVerify?.overdueVerifyBlocks || []).map(formatBlockCard),
      activeVerifyBlock: dayVerify?.activeVerifyBlock ? formatBlockCard(dayVerify.activeVerifyBlock) : null,
      pendingReviewBlocks: verifyCandidates
        .filter((b) => b.status !== 'done' && (b.verifyStatus === 'submitted' || b.verifyStatus === 'rejected'))
        .map(formatBlockCard),
      upcomingVerifyBlocks: verifyCandidates
        .filter((b) => b.status === 'pending' && b.startMin > min && !['overdue_locked', 'submitted', 'rejected', 'expired_breach'].includes(b.verifyStatus))
        .map(formatBlockCard),
    }

    return {
      winnerId,
      dayWorkWallEnabled,
      verifyClearedToday: Boolean(dayVerify?.allVerifyCleared),
      effectiveDecision,
      hands,
      lastIntercept,
      activeLock,
      allLocks,
      wallet,
      verifyLockCenter,
      scheduleSlots,
      recentActions,
    }
  }

  return {
    formatRecentActionEntry,
    buildLocksOverview,
  }
}
