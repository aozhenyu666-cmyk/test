import { createHash } from 'node:crypto'
import { addProposal } from './covenant.js'
import { dayString } from './daily.js'
import { inGemini } from './gemini.js'
import { readInboxEntries } from './inbox_dept.js'
import { home, readState, startOfDay, stateFile, writeJson } from './runtime_io.js'
import { COLD_CUTOFF_MINUTE, SEAL_CUTOFF_MINUTE, SEAL_KINDS, lockingSeal, nextYmd } from './seal.js'
import { hm as zoneHm, minuteOfDay as zoneMinuteOfDay, zoneOr } from './tz.js'
import { wallDecision } from './wall.js'

/**
 * Gemini 桥（手机端上行快照与下行命令同步运行态，从 index.js 拆出）。
 */
export function createBridgeRuntime(deps) {
  const {
    getConfig,
    getLastPhone,
    getLastRhythm,
    getLive,
    getV4Hub,
    commit,
    record,
    bumpDayPlanWatch,
    loadSeals,
    sealHm,
    runningBoutNow,
    timeLedgerNow,
    syncCovenantNow,
    saveCovenant,
    writeCovenantSide,
    workStartedToday,
    loadGeminiReceipts,
    geminiAccountingNow,
    recordGeminiReceipt,
    applyReviewedCards,
    ingestReviewCards,
    reviewSnapshot,
    healthNow,
    verifyView,
    wallCfgNow,
    isEmergencyPassOrDisarmActive,
    weekPackNow,
    vaultKb,
    wallBossBriefRoute,
    sisterNames,
    sealsRoute,
    startWork,
    spendBalance,
    receiveWeekReview,
    geminiPackages,
    liveCoreBridgeSnapshot,
    markLiveCoreReceipt,
    liveCoreRoute,
  } = deps

  const BRIDGE_TICK_MS = 10_000
  const BRIDGE_GEMINI_INTERVAL_MS = 10_000
  const BRIDGE_INTERVAL_MS = 20_000
  const BRIDGE_IDLE_INTERVAL_MS = 120_000
  const BRIDGE_FULL_EVERY_MS = 10 * 60_000
  const BRIDGE_TIMEOUT_MS = 10_000

  let bridgeSent = { core: '', covenant: '', week: '', bossBrief: '', fullAt: 0 }
  let bridgeInflight = null
  let bridgeTimer = null

  const bridgeConf = () => {
    const b = getConfig().bridge && typeof getConfig().bridge === 'object' ? getConfig().bridge : {}
    return {
      enabled: b.enabled !== false && Boolean(b.url && b.key),
      url: String(b.url || '').replace(/\/+$/, ''),
      key: String(b.key || ''),
    }
  }

  const loadBridgeState = () => {
    const raw = readState(stateFile('gemini-bridge.json'), null) || {}
    return {
      lastOkAt: Number(raw.lastOkAt) || 0,
      lastTryAt: Number(raw.lastTryAt) || 0,
      lastError: String(raw.lastError || ''),
      done: Array.isArray(raw.done) ? raw.done.slice(-200) : [],
      pendingResults: Array.isArray(raw.pendingResults) ? raw.pendingResults.slice(-50) : [],
      applied: Number(raw.applied) || 0,
      lastCommandAt: Number(raw.lastCommandAt) || 0,
    }
  }

  const saveBridgeState = (s) => writeJson(stateFile('gemini-bridge.json'), s)

  function bridgeHealthy(now = Date.now()) {
    if (!bridgeConf().enabled) return false
    try {
      return now - loadBridgeState().lastOkAt < 10 * 60_000
    } catch {
      return false
    }
  }

  const hmParse = (v) => {
    const m = /^(\d{1,2})[:：](\d{2})$/.exec(String(v || '').trim())
    return m ? Number(m[1]) * 60 + Number(m[2]) : null
  }

  function bridgeSnapshot(now = Date.now()) {
    const config = getConfig()
    const lastPhone = getLastPhone()
    const lastRhythm = getLastRhythm()
    const live = getLive()
    const v4Hub = getV4Hub()

    const tz = zoneOr(config.timeZone)
    const today = dayString(now, tz)
    const min = zoneMinuteOfDay(now, tz)
    const seals = loadSeals(now, tz)
    const mapSeal = (x) => ({
      id: x.id, title: x.title, kind: x.kind, kindLabel: SEAL_KINDS[x.kind]?.label || x.kind, unit: SEAL_KINDS[x.kind]?.unit || '',
      start: x.startMinute >= 0 ? sealHm(x.startMinute) : null, due: sealHm(x.dueMinute), stretch: x.stretch, floor: x.floor,
      actual: x.actual, status: x.status, source: x.source, sameDay: x.sameDay, paidMinutes: x.paidMinutes, opened: Boolean(x.openedAt),
    })
    const lock = lockingSeal(seals, { now, today })
    let dayTick = null
    try {
      dayTick = v4Hub?.dayBlockTickNow?.(now, seals.items) || null
    } catch {}
    const overBlk = Array.isArray(dayTick?.overdueVerifyBlocks) && dayTick.overdueVerifyBlocks.length ? dayTick.overdueVerifyBlocks[0] : null
    const snapLocking = lock
      ? { id: lock.id, title: lock.title, actual: lock.actual, floor: lock.floor, until: sealHm(Math.round((lock.lockUntil - startOfDay(now, tz)) / 60_000)) }
      : (overBlk
          ? {
              id: overBlk.id,
              title: overBlk.title,
              kind: 'dayblock',
              actual: 0,
              floor: 1,
              until: sealHm(Math.round(((Number(overBlk.overdueLockUntil) || (now + 3600_000)) - startOfDay(now, tz)) / 60_000)),
              lockedByBoss: Boolean(overBlk.lockedByBoss),
            }
          : null)
    const snapCustody = dayTick?.custody
      ? {
          active: Boolean(dayTick.custody.active),
          id: dayTick.custody.id,
          title: dayTick.custody.title,
          issuedBy: dayTick.custody.issuedBy,
          untilHm: sealHm(dayTick.custody.untilMin),
          untilAt: dayTick.custody.untilAt,
          rebriefCount: Number(dayTick.custody.rebriefCount) || 0,
        }
      : null
    const snapVerifyCenter = dayTick?.verifySummary
      ? {
          totalVerify: Number(dayTick.verifySummary.totalVerify) || 0,
          passedVerify: Number(dayTick.verifySummary.passedVerify) || 0,
          overdueCount: (dayTick.overdueVerifyBlocks || []).length,
          pendingReviewCount: Number(dayTick.verifySummary.pendingReviewCount) || 0,
          clearedToday: Boolean(dayTick.verifySummary.allVerifyCleared),
          overdueBlocks: (dayTick.overdueVerifyBlocks || []).slice(0, 4).map((b) => ({
            id: b.id,
            title: b.title,
            dueHm: sealHm(b.endMin),
            lockedByBoss: Boolean(b.lockedByBoss),
          })),
          pendingReviewBlocks: (dayTick.state?.dayBlocks || [])
            .filter((b) => !b.isBreak && b.status !== 'done' && (b.verifyStatus === 'submitted' || b.verifyStatus === 'rejected'))
            .slice(0, 4)
            .map((b) => ({ id: b.id, title: b.title, verifyStatus: b.verifyStatus })),
        }
      : null
    const run = runningBoutNow()
    let funLeft = null
    try {
      funLeft = timeLedgerNow(now, tz, lastPhone).funLeft
    } catch {}
    let cov = { rules: [] }
    try {
      cov = syncCovenantNow(now)
    } catch {}
    let deadlines = []
    try {
      deadlines = (v4Hub?.upcomingDeadlines ? v4Hub.upcomingDeadlines(now) : []).slice(0, 5).map((d) => ({ title: String(d.title || ''), daysLeft: Number(d.daysLeft) }))
    } catch {}
    const workStarted = workStartedToday(now, tz)
    const utcMin = Math.floor((now % 86_400_000) / 60_000)
    return {
      v: 1, at: now, day: today, hm: sealHm(min), tzOffsetMin: ((min - utcMin + 1440 + 720) % 1440) - 720,
      cold: !workStarted && min < COLD_CUTOFF_MINUTE, coldCutoff: sealHm(COLD_CUTOFF_MINUTE), workStarted, cutoffPassed: min >= SEAL_CUTOFF_MINUTE,
      timeline: seals.items.filter((x) => x.day === today).sort((a, b) => a.dueMinute - b.dueMinute).map(mapSeal),
      tomorrow: seals.items.filter((x) => x.day === nextYmd(today)).sort((a, b) => a.dueMinute - b.dueMinute).map(mapSeal),
      locking: snapLocking,
      custody: snapCustody,
      verifyLockCenter: snapVerifyCenter,
      bout: run ? { title: String(live?.goal || run.kind || ''), minutes: Math.round((now - Number(run.openedAt || now)) / 60_000), sealId: String(live?.sealId || '') } : null,
      balance: funLeft === null ? null : { funLeft },
      deadlines,
      receiptsToday: loadGeminiReceipts().items.filter((r) => r.day === today).length,
      geminiAccounting: geminiAccountingNow(now, tz, seals),
      review: (() => {
        try {
          return reviewSnapshot(now)
        } catch {
          return null
        }
      })(),
      health: (() => {
        try {
          const h = healthNow(now)
          return { line: h.summary.line, text: h.text }
        } catch {
          return null
        }
      })(),
      verify: (() => {
        try {
          const vv = verifyView(now, tz)
          const wdNight = wallDecision(wallCfgNow(), now, tz)
          return {
            active: vv.active,
            until: vv.untilMinute != null ? sealHm(vv.untilMinute) : null,
            recovery: vv.recovery,
            spend: vv.spend ? { until: zoneHm(vv.spend.until, tz), minutes: vv.spend.minutes } : null,
            evening: vv.evening?.active ? { used: vv.evening.used, cap: vv.evening.cap, hit: vv.evening.hit } : null,
            emergency: Boolean(isEmergencyPassOrDisarmActive(now, tz)),
            nightLock: Boolean(lastRhythm?.night || (wdNight.locked && wdNight.kind === 'night')),
          }
        } catch {
          return null
        }
      })(),
      weekText: (() => {
        try {
          return weekPackNow(now).text
        } catch {
          return ''
        }
      })(),
      covenant: {
        iron: cov.rules.filter((r) => r.hardness === '铁律').map((r) => ({ id: r.id, title: r.title })),
        rules: cov.rules.map((r) => ({ id: r.id, title: r.title, do: r.do, hardness: r.hardness, who: r.who })),
      },
      inbox: (() => {
        try {
          const kb = vaultKb() || home()
          const items = readInboxEntries(kb, { limit: 20 })
          const stuckItems = items.filter((e) => e.tag === '卡壳')
          return {
            count: items.length,
            recent: items.slice(-5),
            stuckCount: stuckItems.length,
            stuckItems: stuckItems.slice(-5),
          }
        } catch {
          return { count: 0, recent: [], stuckCount: 0, stuckItems: [] }
        }
      })(),
      bossBrief: (() => {
        try {
          const b = wallBossBriefRoute()
          if (!b || b.ok === false) return null
          return {
            day: b.day,
            headline: b.headline,
            keyGaps: b.keyGaps,
            snapshot: b.snapshot,
            bossPrompt: b.bossPrompt,
            winnerHand: b.winnerHand || null,
            verifyLockCenter: b.verifyLockCenter || null,
          }
        } catch {
          return null
        }
      })(),
      liveCore: (() => {
        try {
          return typeof liveCoreBridgeSnapshot === 'function' ? liveCoreBridgeSnapshot(now, tz) : null
        } catch {
          return null
        }
      })(),
      names: sisterNames(),
    }
  }

  async function applyBridgeCommand(cmd, now = Date.now()) {
    const config = getConfig()
    const v4Hub = getV4Hub()
    const p = cmd?.payload && typeof cmd.payload === 'object' ? cmd.payload : {}
    if (cmd?.type === 'receipt') {
      const r = recordGeminiReceipt({ ...p, id: cmd.id }, { via: 'gemini-mcp', now })
      const it = r.receipt
      const rated = applyReviewedCards(p.reviewedCards ?? p.reviewed_cards, 'gemini', now)
      try {
        markLiveCoreReceipt?.({ now, summary: it.did || it.topic || '', next: it.next || '' })
      } catch {}
      const word = { pass: '过', partial: '半过', fail: '没过' }[it.verdict]
      const tz = zoneOr(config.timeZone)
      const seal = it.sealId ? loadSeals(now, tz).items.find((x) => x.id === it.sealId) : null
      let blockNote = ''
      const targetBlockId = String(p.blockId || p.checkpointId || '').trim()
      if (!seal && it.verdict === 'pass' && targetBlockId) {
        try {
          const jr = v4Hub?.judgeDelivery?.({
            blockId: targetBlockId,
            verdict: 'pass',
            note: it.did || it.topic || 'Gemini 问答回执验收通过',
            judgedBy: 'gemini',
            allowAutoFromReceipt: true,
          }, now)
          if (jr?.ok && jr.block) {
            blockNote = `；计划验收块「${jr.block.title}」已同步解锁` + (jr.rewardMin > 0 ? `（+${jr.rewardMin}m 余额）` : '')
          }
        } catch {}
      } else if (!seal && (it.verdict === 'fail' || it.verdict === 'partial') && targetBlockId) {
        try {
          const jr = v4Hub?.judgeDelivery?.({
            blockId: targetBlockId,
            verdict: 'reject',
            note: it.did || it.topic || 'Gemini 问答未通过验收',
            judgedBy: 'gemini',
          }, now)
          if (jr?.ok && jr.block) {
            blockNote = `；计划验收块「${jr.block.title}」未达标，继续保持 P3 锁定` + (jr.adapted && jr.adaptiveNote ? `（${jr.adaptiveNote}）` : '')
          }
        } catch {}
      }
      return {
        ok: true,
        text: '回执收到：「' + (it.topic || '回执') + '」' + word +
          (p.verdict === 'pass' && it.verdict !== 'pass' ? '（「过」要 2 组像样的问答，这条按半过记）' : '') +
          (seal ? '；验收点「' + seal.title + '」现在 ' + seal.actual + '/' + seal.stretch + (SEAL_KINDS[seal.kind]?.unit || '') : (blockNote || (p.checkpointId ? '；没找到这个验收点，只记了回执' : ''))) +
          (r.cards ? '；问答收成 ' + r.cards + ' 张复习卡' : '') + (rated.text ? '；' + rated.text : '') + '。',
      }
    }
    if (cmd?.type === 'push_review_cards') {
      const src = p.source === 'brainos' || p.source === 'brainos_proposition' ? 'brainos' : 'gemini_mcp'
      const r = ingestReviewCards(p.items ?? p.cards, { source: src, sealId: p.checkpointId ?? p.checkpoint_id, now, max: 10 })
      if (p.alsoReceipt && typeof p.alsoReceipt === 'object') {
        try {
          recordGeminiReceipt({ ...p.alsoReceipt, id: cmd.id + '-r' }, { via: src === 'brainos' ? 'brainos' : 'gemini-mcp', now })
        } catch {}
      }
      return { ok: r.added > 0 || r.duplicate > 0, text: r.text }
    }
    if (cmd?.type === 'review_ratings') {
      const rated = applyReviewedCards(p.items ?? p.reviewedCards, p.channel === 'brainos' ? 'brainos' : 'gemini', now)
      return { ok: rated.n > 0, text: rated.text || '一张都没对上。' }
    }
    if (cmd?.type === 'import_questions') {
      const qs = (Array.isArray(p.questions) ? p.questions : []).slice(0, 200)
      const raw = typeof p.raw === 'string' && p.raw.trim() ? p.raw : JSON.stringify(qs.map((q) => ({
        stem: q?.stem, options: Array.isArray(q?.options) ? q.options : undefined, answer: q?.answer, analysis: q?.analysis, type: q?.type,
      })))
      const r = v4Hub.bankRoute({ action: 'import', raw, append: true, name: p.bankName ?? p.bank_name, id: p.bankId ?? p.bank_id })
      if (!r.ok) return { ok: false, text: '没导进去：' + (r.error || '格式不对') }
      if (!r.importedCount) return { ok: false, text: '一题都没进：' + (r.warnings?.[0] || '格式不对') + '。' }
      return { ok: true, text: '题库新增 ' + r.importedCount + ' 题（现在一共 ' + r.bankTotal + ' 题）' + (r.skippedCount ? '；没进的 ' + r.skippedCount + ' 题：' + (r.warnings?.[0] || '') : '') + '。' }
    }
    if (cmd?.type === 'add_checkpoints') {
      const seals = (Array.isArray(p.items) ? p.items : []).map((x) => ({
        title: x.title, kind: x.kind, stretch: x.stretch, floor: x.floor, path: x.path, app: x.app, estMinutes: x.est_minutes ?? x.estMinutes,
        dueMinute: hmParse(x.due), startMinute: hmParse(x.start),
      }))
      const r = sealsRoute({ action: 'plan-day', target: p.target === 'tomorrow' ? 'tomorrow' : 'today', seals, by: 'gemini' })
      const added = (r.added || []).map((x) => '「' + x.title + '」' + (x.startMinute >= 0 ? sealHm(x.startMinute) + '–' : '') + sealHm(x.dueMinute) + (x.sameDay ? '（现加，七折）' : ''))
      const skipped = (r.skipped || []).map((x) => '「' + x.title + '」' + (x.text || x.error))
      return { ok: added.length > 0, text: (added.length ? '加上了：' + added.join('、') + '。到开始时刻手机自动开局。' : '一条都没加上。') + (skipped.length ? ' 没加的：' + skipped.join('；') : '') }
    }
    if (cmd?.type === 'start_work') {
      return startWork({ hours: p.hours, sprint: p.sprint === true, by: 'gemini' }, now)
    }
    if (cmd?.type === 'spend_balance') {
      const r = spendBalance({ minutes: p.minutes, reason: p.reason, by: 'gemini' }, now)
      return { ok: r.ok === true, text: r.text }
    }
    if (cmd?.type === 'week_review') {
      const adjustments = (Array.isArray(p.adjustments) ? p.adjustments : []).map((x) => String(x || '').trim()).filter(Boolean).slice(0, 3)
      const r = receiveWeekReview('', { by: 'gemini', now, parsed: { ok: true, verdict: String(p.verdict || ''), adjustments, watch: String(p.watch || '') } })
      return r.ok ? { ok: true, text: '周验收收到了（' + r.review.adjustments.length + ' 条调整），等他在手机上逐条决定采不采纳。' } : { ok: false, text: r.text || '没收成' }
    }
    if (cmd?.type === 'propose_rule') {
      const st = syncCovenantNow(now)
      const r = addProposal(st.proposals, { ...p, by: 'gemini' }, now)
      if (!r.ok) return { ok: false, text: r.text }
      const next = { ...st, proposals: r.proposals }
      saveCovenant(next)
      writeCovenantSide(next, now)
      record({ state: 'covenant-propose', at: now, by: 'gemini', id: r.proposal.id, title: r.proposal.rule.title })
      return { ok: true, text: '提上去了，等他在手机「公约」页批；批之前不算数。' }
    }
    if (cmd?.type === 'issue_sprint_custody') {
      const rebriefReason = String(p.rebriefReason ?? p.rebrief_reason ?? '').trim()
      const parsedStart = Number.isFinite(Number(p.startMin))
        ? Number(p.startMin)
        : (typeof p.start === 'string' && p.start.trim() ? hmParse(p.start) : -1)
      const startMin = parsedStart >= 0 ? parsedStart : undefined
      const r = rebriefReason
        ? v4Hub?.rebriefSprintCustody?.({
            reason: rebriefReason,
            blocks: p.blocks,
            downgradeLowEnergy: Boolean(p.downgradeLowEnergy ?? p.downgrade_low_energy),
            issuedBy: 'gemini',
          }, now)
        : v4Hub?.issueSprintCustody?.({
            title: p.title || 'Gemini 托管冲刺轨',
            blocks: p.blocks,
            startMin,
            armAutoKickoff: p.armAutoKickoff ?? p.arm_auto_kickoff,
            issuedBy: 'gemini',
          }, now)
      if (!r || !r.ok) return { ok: false, text: r?.text || r?.error || '手机未收成托管冲刺轨' }
      bumpDayPlanWatch(now + 2 * 60_000)
      return {
        ok: true,
        text: `已签发「${r.custody.title}」（${r.blocks.length} 个强制验收块，托管锁定至 ${r.untilHm}；期内禁止自删降级）。`,
      }
    }
    if (cmd?.type === 'live_relay') {
      if (typeof liveCoreRoute !== 'function') {
        return { ok: false, text: '手机端未挂载 liveCoreRoute。' }
      }
      const r = await liveCoreRoute({
        action: 'commit_relay',
        mode: p.mode || 'checkpoint_25m',
        minutes: p.minutes,
        summary: p.summary || '',
        next: p.next || '',
      }, 'POST')
      return { ok: r?.ok === true, text: r?.toast || '已完成本轮 Live 接力交接。' }
    }
    return { ok: false, text: '手机不认识这种命令：' + String(cmd?.type || '') }
  }

  async function bridgeSync({ force = false, now = Date.now(), fetchFn = globalThis.fetch } = {}) {
    const conf = bridgeConf()
    if (!conf.enabled || typeof fetchFn !== 'function') return { ok: false, error: 'not-configured' }
    if (bridgeInflight) return bridgeInflight
    const lastPhone = getLastPhone()
    const st0 = loadBridgeState()
    const inGem = (() => {
      try {
        return inGemini(lastPhone, geminiPackages())
      } catch {
        return false
      }
    })()
    const gap = inGem
      ? BRIDGE_GEMINI_INTERVAL_MS
      : now - st0.lastCommandAt < 5 * 60_000 || lastPhone?.screenOn ? BRIDGE_INTERVAL_MS : BRIDGE_IDLE_INTERVAL_MS
    if (!force && now - st0.lastTryAt < gap - 1000) return { ok: true, skipped: true }
    bridgeInflight = (async () => {
      let st = { ...st0, lastTryAt: now }
      const post = async (body) => {
        const ctl = new AbortController()
        const timer = setTimeout(() => ctl.abort(), BRIDGE_TIMEOUT_MS)
        try {
          const res = await fetchFn(conf.url + '/bridge/sync', {
            method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + conf.key }, body: JSON.stringify(body), signal: ctl.signal,
          })
          const text = await res.text()
          if (!res.ok) throw new Error(res.status === 401 ? '手机密钥不对（401）' : 'HTTP ' + res.status)
          return JSON.parse(text || '{}')
        } finally {
          clearTimeout(timer)
        }
      }
      const delta = (snap, full) => {
        const h = (v) => createHash('sha256').update(JSON.stringify(v ?? null)).digest('hex').slice(0, 16)
        const { covenant, weekText, bossBrief, at, hm, ...core } = snap
        const hashes = { core: h(core), covenant: h(covenant), week: h(weekText), bossBrief: h(bossBrief) }
        const all = full || Date.now() - bridgeSent.fullAt >= BRIDGE_FULL_EVERY_MS
        const out = { v: snap.v, at: snap.at, hm: snap.hm, day: snap.day, tzOffsetMin: snap.tzOffsetMin }
        if (all || hashes.core !== bridgeSent.core) Object.assign(out, core)
        if (all || hashes.covenant !== bridgeSent.covenant) out.covenant = covenant
        if (all || hashes.week !== bridgeSent.week) out.weekText = weekText
        if (all || hashes.bossBrief !== bridgeSent.bossBrief) out.bossBrief = bossBrief
        return { out, hashes, all }
      }
      const sendSnap = async (results, full = false) => {
        const d = delta(bridgeSnapshot(Date.now()), full)
        const r = await post({ snapshot: d.out, results })
        bridgeSent = { ...d.hashes, fullAt: d.all ? Date.now() : bridgeSent.fullAt }
        return r
      }
      try {
        const first = await sendSnap(st.pendingResults, force)
        st = { ...st, pendingResults: [], lastOkAt: Date.now(), lastError: '' }
        const results = []
        for (const cmd of Array.isArray(first?.commands) ? first.commands : []) {
          if (!cmd?.id) continue
          if (st.done.includes(cmd.id)) {
            results.push({ id: cmd.id, ok: true, text: '已经处理过。' })
            continue
          }
          let r
          try {
            r = await applyBridgeCommand(cmd, Date.now())
          } catch (error) {
            r = { ok: false, text: '手机处理出错：' + String((error && error.message) || error).slice(0, 120) }
          }
          record({ state: 'bridge-command', at: Date.now(), type: cmd.type, id: cmd.id, ok: r.ok === true })
          st.done = [...st.done, cmd.id].slice(-200)
          st.applied += 1
          st.lastCommandAt = Date.now()
          results.push({ id: cmd.id, ok: r.ok === true, text: r.text })
        }
        if (results.length) {
          try {
            await sendSnap(results)
          } catch (error) {
            st.pendingResults = [...st.pendingResults, ...results].slice(-50)
            st.lastError = '回结果失败：' + String((error && error.message) || error).slice(0, 120)
          }
        }
        saveBridgeState(st)
        return { ok: true, commands: results.length }
      } catch (error) {
        const msg = error?.name === 'AbortError' ? '连不上（10 秒超时）' : String((error && error.message) || error).slice(0, 160)
        saveBridgeState({ ...st, lastError: msg })
        return { ok: false, error: msg }
      } finally {
        bridgeInflight = null
      }
    })()
    return bridgeInflight
  }

  function ensureBridgeTimer() {
    if (bridgeTimer || !bridgeConf().enabled) return
    bridgeTimer = setInterval(() => {
      if (!bridgeConf().enabled) {
        clearInterval(bridgeTimer)
        bridgeTimer = null
        return
      }
      bridgeSync().catch(() => {})
    }, BRIDGE_TICK_MS)
    bridgeTimer.unref?.()
  }

  function stopBridgeTimer() {
    if (bridgeTimer) {
      clearInterval(bridgeTimer)
      bridgeTimer = null
    }
  }

  async function bridgeRoute(body) {
    const config = getConfig()
    const b = body && typeof body === 'object' ? body : {}
    const action = String(b.action || 'status')
    const view = (extra = {}) => {
      const conf = bridgeConf()
      const st = loadBridgeState()
      return {
        v: 1, ok: true, configured: Boolean(conf.url && conf.key), enabled: conf.enabled, url: conf.url, keyTail: conf.key ? conf.key.slice(-4) : '',
        lastOkAt: st.lastOkAt, lastTryAt: st.lastTryAt, lastError: st.lastError, applied: st.applied, healthy: bridgeHealthy(),
        receiptsToday: loadGeminiReceipts().items.filter((r) => r.day === dayString(Date.now(), zoneOr(getConfig().timeZone))).length, ...extra,
      }
    }
    if (action === 'status') return view()
    if (action === 'config') {
      const url = String(b.url ?? config.bridge?.url ?? '').trim().replace(/\/+$/, '')
      if (url && !/^https:\/\/[^\s/]+(\/[^\s]*)?$/.test(url) && !/^http:\/\/(127\.0\.0\.1|localhost)(:\d+)?/.test(url)) {
        return { v: 1, ok: false, error: 'bad-url', text: '地址要是 https://…（Tailscale Funnel 给的那个）。' }
      }
      const key = b.key === undefined ? String(config.bridge?.key || '') : String(b.key).trim()
      commit({ bridge: { url, key, enabled: b.enabled === undefined ? true : b.enabled !== false } })
      bridgeSent = { core: '', covenant: '', week: '', bossBrief: '', fullAt: 0 }
      ensureBridgeTimer()
      const r = url && key ? await bridgeSync({ force: true }) : { ok: false }
      return view({ synced: r.ok === true, error: r.error || '' })
    }
    if (action === 'sync') {
      const r = await bridgeSync({ force: true })
      return view({ synced: r.ok === true, error: r.error || '' })
    }
    if (action === 'forget') {
      commit({ bridge: null })
      return view()
    }
    return { v: 1, ok: false, error: 'bad-action' }
  }

  return {
    bridgeConf,
    bridgeHealthy,
    bridgeSnapshot,
    bridgeSync,
    ensureBridgeTimer,
    stopBridgeTimer,
    bridgeRoute,
  }
}
