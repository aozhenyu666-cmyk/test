import { randomUUID } from 'node:crypto'
import { stripInjection } from './budget.js'
import { readCommitments } from './context.js'
import { assistantText, DEFAULT_LIMITS, Desk } from './core.js'
import { dayString } from './daily.js'
import { readLedger } from './ledger.js'
import { passLedgerSummary } from './pass_ledger.js'
import { home, isArchived, liveAgent, mergeConfig, messageText, readState, requestZone, resolveAgent, stateFile, writeConfig, writeJson } from './runtime_io.js'
import { buildBossAuditBrief } from './wall_auditor.js'
import {
  appendWallBossMessage,
  buildWallBossLedgerText,
  isWallBossSession,
  normalizeWallBossHistory,
  validateWallSessionId,
  WALL_BOSS_AUDIT_TRIGGER_TEXT,
  WALL_BOSS_PERSONA_TEXT,
} from './wall_boss_session.js'

/**
 * 主管墙（端内第 3 独立冷血主管会话）运行态，从 index.js 拆出。
 * H1 硬边界：绝不 import 或触碰 emotion.js。
 */
export function createWallBossRuntime(deps) {
  const {
    ctx,
    getConfig,
    setConfig,
    getSayZone,
    getV4Hub,
    getBuildLocksOverview,
    record,
    intensityStateNow,
    loadPassLedger,
    loadSeals,
    usageReportView,
    sealHm,
    mountPersona,
    bumpDayPlanWatch,
    getToolOutput,
    buildLockTruthContext,
    vaultSearchTool,
    vaultRecentTool,
  } = deps

  const wallBossHistoryPath = () => stateFile('wall-boss-history.json')
  const loadWallBossHistory = () => normalizeWallBossHistory(readState(wallBossHistoryPath(), []))
  const saveWallBossHistory = (items) => {
    try {
      writeJson(wallBossHistoryPath(), normalizeWallBossHistory(items))
    } catch {}
  }
  const appendWallBossEntry = (entry) => {
    const next = appendWallBossMessage(loadWallBossHistory(), entry)
    saveWallBossHistory(next)
    return next
  }

  const wallBossDesk = new Desk({
    resolveAgent: (id) => resolveAgent(ctx, id, getConfig()),
    compose: async (req) => ({ text: req.payload.text, prompt: req.payload.text }),
    allowTools: () => true,
    makeMessage: (text) => ({
      id: randomUUID(),
      role: 'user',
      content: [{ type: 'text', text }],
      source: {
        kind: 'user',
        rpcId: 'dsh-go-companion-wallboss-' + randomUUID(),
        clientTimeZone: requestZone(getSayZone(), getConfig()),
      },
    }),
    isArchived: (id) => isArchived(ctx, id),
    defaultSession: () => getConfig().wallSessionId,
    limits: { ...DEFAULT_LIMITS, maxPayloadValue: 3_000, maxText: 4_000 },
    allowBusy: true,
    onChange: (r) => {
      const now = Date.now()
      if (r.state === 'generated') {
        const clean = String(r.text || '').trim()
        if (clean) {
          appendWallBossEntry({
            id: 'a-' + r.requestId,
            role: 'assistant',
            text: clean,
            at: now,
            requestId: r.requestId,
          })
          record({ state: 'wall-boss-reply', at: now, sessionId: r.sessionId, requestId: r.requestId })
        }
      } else if (r.state === 'failed') {
        appendWallBossEntry({
          id: 'e-' + r.requestId,
          role: 'system',
          text: '主管质询调用失败：' + String(r.failure?.detail || r.failure?.code || r.failure || 'agent-error'),
          at: now,
          requestId: r.requestId,
          error: true,
        })
      }
    },
  })

  function handleWallBossSessionEvent(session, event) {
    const config = getConfig()
    if (!isWallBossSession(session?.id, config)) return false
    try {
      if (event?.type === 'user/message') {
        const rpcId = String(event.data?.source?.rpcId || '')
        const kind = event.data?.source?.kind
        if (!rpcId.startsWith('dsh-go-companion-wallboss-') && (!kind || kind === 'user')) {
          const uText = stripInjection(messageText(event.data)).replace(/\s+/g, ' ').trim().slice(0, 2000)
          if (uText) {
            appendWallBossEntry({
              id: String(event.data?.id || ('u-ext-' + Date.now())),
              role: 'user',
              text: uText,
              at: Date.now(),
            })
          }
        }
      } else if (event?.type === 'assistant/message') {
        const ownedByDesk = [...wallBossDesk.records.values()].some(
          (r) => r.sessionId === session?.id && r.state === 'claimed' && r.turn === event.data?.turn,
        )
        if (!ownedByDesk) {
          const aText = (assistantText(event.data) || messageText(event.data)).trim().slice(0, 4000)
          if (aText) {
            const hist = loadWallBossHistory()
            const prev = hist[hist.length - 1]
            const now = Date.now()
            if (!(prev && prev.role === 'assistant' && prev.text === aText && now - Number(prev.at || 0) < 5000)) {
              appendWallBossEntry({
                id: String(event.data?.id || ('a-ext-' + now)),
                role: 'assistant',
                text: aText,
                at: now,
              })
            }
          }
        }
      }
    } catch {}
    return true
  }

  function wallBossBriefRoute(body = {}) {
    const config = getConfig()
    const v4Hub = getV4Hub()
    const buildLocksOverview = getBuildLocksOverview()
    const now = Date.now()
    const tz = requestZone(body?.timeZone, config)
    const today = dayString(now, tz)
    const todayV4 = (() => { try { return v4Hub?.todayViewV4?.(now) || null } catch { return null } })()
    const passSum = todayV4?.passLedger || (() => {
      try {
        const info = intensityStateNow(now, tz)
        return passLedgerSummary(loadPassLedger(now, tz), { day: today, cap: info.emergencyDisarmDailyCap, now })
      } catch {
        return null
      }
    })()
    const seals = (() => { try { return loadSeals(now, tz)?.items || [] } catch { return [] } })()
    const locksOverview = (() => { try { return buildLocksOverview(now, tz) } catch { return {} } })()
    const commitments = (() => { try { return readCommitments(home(), now, 10) } catch { return [] } })()
    const planBlocks = (() => { try { return v4Hub?.loadDayPlan?.(now)?.dayBlocks || [] } catch { return [] } })()
    const entries = readLedger(home(), 400)
    const recentBreaches = entries.filter((e) => {
      if (!e || now - Number(e.at || 0) > 7 * 86_400_000) return false
      return ['intercept-hit', 'blocked-open', 'disarm', 'emergency-pass', 'checkpoint-overdue-locked', 'takeover', 'breach', 'breakglass'].includes(String(e.state || ''))
    })
    const rep = (() => { try { return usageReportView(now, tz) } catch { return {} } })()
    const weekStats = (() => {
      try {
        const ov = v4Hub?.analyticsRoute?.({ action: 'overview', rangeDays: 7 })
        return ov?.summary || {}
      } catch {
        return {}
      }
    })()
    const funMinFallback = Number(rep?.funMinutes) || 0
    const focusMinFallback = Math.max(0, (Number(rep?.totalMinutes) || 0) - funMinFallback)
    return {
      v: 1,
      ...buildBossAuditBrief({
        now,
        tz,
        todayStats: {
          buckets: todayV4?.charts?.buckets || {},
          workMin: todayV4?.brief?.focusMin ?? focusMinFallback,
          funMin: todayV4?.brief?.funMin ?? funMinFallback,
          passLedger: passSum,
          disarms: passSum?.emergency?.used ?? 0,
        },
        weekStats,
        locksOverview,
        seals,
        commitments,
        planBlocks,
        recentBreaches,
      }),
      verifyLockCenter: locksOverview?.verifyLockCenter || null,
      winnerHand: (Array.isArray(locksOverview?.hands) ? locksOverview.hands : []).find((h) => h && h.isWinner) || null,
    }
  }

  function wallBossStatusSnapshot(tzBody = {}) {
    const config = getConfig()
    const rawSid = String(config.wallSessionId || '').trim()
    const valid = Boolean(
      rawSid &&
      rawSid !== String(config.sessionId || '').trim() &&
      (config.dual === false || rawSid !== String(config.strategistSessionId || '').trim()),
    )
    const sid = valid ? rawSid : ''
    const agent = sid ? liveAgent(ctx, sid) : null
    const bossBrief = (() => { try { return wallBossBriefRoute(tzBody) } catch { return { ok: false } } })()
    return {
      v: 1,
      ok: true,
      wallSessionId: sid || null,
      bound: Boolean(sid),
      agentOnline: Boolean(agent),
      agentStatus: agent?.status || null,
      inFlight: wallBossDesk.inFlight(),
      messages: loadWallBossHistory(),
      bossBrief,
      verifyLockCenter: bossBrief?.verifyLockCenter || null,
    }
  }

  async function wallBossChatRoute(body = {}, method = 'GET') {
    const config = getConfig()
    const v4Hub = getV4Hub()
    const b = body && typeof body === 'object' ? body : {}
    const action = String(b.action || (method === 'GET' ? 'status' : 'status')).trim()
    if (action === 'status') return wallBossStatusSnapshot(b)
    if (action === 'bind') {
      const check = validateWallSessionId(b.sessionId, {
        sessionId: config.sessionId || '',
        strategistSessionId: config.dual === false ? '' : (config.strategistSessionId || ''),
      })
      if (!check.ok) {
        return { ...wallBossStatusSnapshot(b), ok: false, error: check.error, text: check.text }
      }
      const next = mergeConfig(config, { wallSessionId: check.sessionId })
      setConfig(next)
      try { writeConfig(next) } catch {}
      record({ state: 'wall-boss-bind', at: Date.now(), sessionId: check.sessionId })
      try { mountPersona() } catch {}
      return { ...wallBossStatusSnapshot(b), text: '已绑定主管墙独立会话。' }
    }
    if (action === 'unbind') {
      const next = mergeConfig(config, { wallSessionId: null })
      setConfig(next)
      try { writeConfig(next) } catch {}
      record({ state: 'wall-boss-unbind', at: Date.now() })
      try { mountPersona() } catch {}
      return { ...wallBossStatusSnapshot(b), text: '已解绑主管墙独立会话。' }
    }
    if (action === 'clear') {
      saveWallBossHistory([])
      record({ state: 'wall-boss-clear', at: Date.now() })
      return { ...wallBossStatusSnapshot(b), text: '已清空主管质询室记录。' }
    }
    if (action === 'issue_directive') {
      const now = Date.now()
      const iss = v4Hub?.issueDirective?.(b, now)
      if (!iss || !iss.ok) {
        return { ...wallBossStatusSnapshot(b), ok: false, error: iss?.error || 'issue-failed', text: iss?.text || '下达主管硬指令失败' }
      }
      appendWallBossEntry({
        id: 'dir-' + now.toString(36),
        role: 'system',
        text: `已钉入主管止损硬指令 [${iss.block.id}]「${iss.block.title}」（截止 ${sealHm(iss.block.endMin)} · 交付：${iss.block.deliverable} · 绑定 P3 验收锁）`,
        at: now,
      })
      return {
        ...wallBossStatusSnapshot(b),
        ok: true,
        block: iss.block,
        text: `已下达止损硬指令「${iss.block.title}」，到点未交自动触发 P3 验收锁。`,
      }
    }
    if (action === 'judge_delivery') {
      const now = Date.now()
      const res = v4Hub?.judgeDelivery?.({
        blockId: b.blockId ?? b.id,
        verdict: b.verdict,
        note: b.note,
        judgedBy: 'wall_boss',
      }, now)
      if (!res || !res.ok) {
        return { ...wallBossStatusSnapshot(b), ok: false, error: res?.error || 'judge-failed', text: res?.text || '终审裁决失败' }
      }
      appendWallBossEntry({
        id: 'jdg-' + now.toString(36),
        role: 'system',
        text: res.verdict === 'pass'
          ? `主管终审 PASS「${res.block.title}」：已解锁${res.rewardMin > 0 ? `并入账 +${res.rewardMin}m 余额` : ''}（${res.block.judgeNote}）`
          : `主管终审 REJECT「${res.block.title}」：驳回（${res.block.judgeNote}），继续维持 P3 验收锁${res.adaptiveNote ? `；${res.adaptiveNote}` : ''}`,
        at: now,
      })
      return {
        ...wallBossStatusSnapshot(b),
        ok: true,
        verdict: res.verdict,
        block: res.block,
        rewardMin: res.rewardMin || 0,
        adapted: Boolean(res.adapted),
        adaptiveNote: res.adaptiveNote || '',
        text: res.verdict === 'pass'
          ? `终审 PASS：「${res.block.title}」已放行并解除验收锁`
          : `终审 REJECT：「${res.block.title}」已被驳回${res.adaptiveNote ? ` · ${res.adaptiveNote}` : ''}`,
      }
    }
    if (action === 'issue_sprint_custody') {
      const now = Date.now()
      const res = v4Hub?.issueSprintCustody?.({ ...b, issuedBy: 'wall_boss' }, now)
      if (!res || !res.ok) {
        return { ...wallBossStatusSnapshot(b), ok: false, error: res?.error || 'custody-failed', text: res?.text || '签发托管冲刺轨失败' }
      }
      bumpDayPlanWatch(now + 2 * 60_000)
      appendWallBossEntry({
        id: 'cus-' + now.toString(36),
        role: 'system',
        text: `已签发托管冲刺轨「${res.custody.title}」（${res.blocks.length} 段验收块 · 至 ${res.untilHm} · 托管期内禁自删降级）`,
        at: now,
      })
      return {
        ...wallBossStatusSnapshot(b),
        ok: true,
        custody: res.custody,
        blocks: res.blocks,
        text: `已签发「${res.custody.title}」（至 ${res.untilHm}，托管期锁定防自改）。`,
      }
    }
    if (action === 'rebrief_custody') {
      const now = Date.now()
      const res = v4Hub?.rebriefSprintCustody?.({ ...b, issuedBy: 'wall_boss' }, now)
      if (!res || !res.ok) {
        return { ...wallBossStatusSnapshot(b), ok: false, error: res?.error || 'rebrief-failed', text: res?.text || '托管 Re-brief 失败' }
      }
      bumpDayPlanWatch(now + 2 * 60_000)
      appendWallBossEntry({
        id: 'rbr-' + now.toString(36),
        role: 'system',
        text: `已执行托管 Re-brief 重签（理由：${String(b.reason || '').slice(0, 80)} · 至 ${res.untilHm}）`,
        at: now,
      })
      return {
        ...wallBossStatusSnapshot(b),
        ok: true,
        custody: res.custody,
        blocks: res.blocks,
        text: `已重签托管冲刺轨（至 ${res.untilHm}）。`,
      }
    }
    if (action === 'send' || action === 'audit_now' || action === 'request_judge') {
      const st = wallBossStatusSnapshot(b)
      if (!st.bound) {
        return { ...st, ok: false, error: 'no-wall-session', text: '尚未绑定主管墙独立会话（wallSessionId），请先一键新建或选择已有会话。' }
      }
      let rawText = ''
      if (action === 'audit_now') {
        rawText = String(b.text || '').trim() || WALL_BOSS_AUDIT_TRIGGER_TEXT
      } else if (action === 'request_judge') {
        const dp = v4Hub?.loadDayPlan?.(Date.now())
        const targetId = String(b.blockId ?? b.id ?? '').trim()
        const blk = (dp?.dayBlocks || []).find((x) => x.id === targetId) || null
        if (!blk) {
          return { ...st, ok: false, error: 'no-block', text: '未找到要终审的验收时间块' }
        }
        rawText = [
          `请对以下待终审验收块执行冷血终审，核实交付是否真实充足后调用 \`wall_judge_delivery(blockId="${blk.id}", verdict="pass"|"reject", note)\`：`,
          `- 块 ID：${blk.id} | 标题：${blk.title}（${sealHm(blk.startMin)}–${sealHm(blk.endMin)}）`,
          `- 约定物理交付：${blk.deliverable}`,
          `- 用户本次提交的交付内容：${blk.submissionText || '（用户尚未提交具体交付文本！）'}`,
          '要求：如无真产出、只有口号/凑字数或未对上约定交付，严禁放水，直接 verdict="reject" 并写明缺什么；若真实过关再 verdict="pass"。',
        ].join('\n')
      } else {
        rawText = String(b.text || '').trim()
      }
      if (!rawText) {
        return { ...st, ok: false, error: 'empty', text: '请输入要质询或回答的内容。' }
      }
      const requestId = 'wb-' + randomUUID().replace(/-/g, '').slice(0, 14)
      const now = Date.now()
      const text = rawText.slice(0, 2500)
      appendWallBossEntry({ id: 'u-' + requestId, role: 'user', text: text.slice(0, 2000), at: now, requestId })
      record({ state: 'wall-boss-send', at: now, sessionId: st.wallSessionId, requestId, action })
      const r = await wallBossDesk.request({
        v: 1,
        requestId,
        eventId: requestId,
        kind: 'wall_boss',
        payload: { text },
        deadlineMs: 120_000,
        sessionId: st.wallSessionId,
      })
      if (r.status >= 400) {
        const code = String(r.body?.error?.code || 'wall-boss-failed')
        const detail = String(r.body?.error?.message || code)
        appendWallBossEntry({
          id: 'e-' + requestId,
          role: 'system',
          text: `主管会话暂时不可用（${detail}）`,
          at: Date.now(),
          requestId,
          error: true,
        })
        return { ...wallBossStatusSnapshot(b), ok: false, error: code, text: '主管会话当前不可用（请检查会话是否存在或引擎已就绪）' }
      }
      return { ...wallBossStatusSnapshot(b), ok: true, pending: true, requestId }
    }
    return { ...wallBossStatusSnapshot(b), ok: false, error: 'bad-action', text: '不支持的操作：' + action }
  }

  let wallBossMount = { sid: '', agent: null, off: null, toolOff: null }

  function wallBossAuditTool() {
    return {
      name: 'wall_boss_brief',
      description: '只读获取近 7 天计划/封印兑现率、今日正事与娱乐耗时对比、撞锁与紧急放行次数、悬空承诺与结构性逃避缺口。',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: getToolOutput(),
      execute: () => {
        const b = wallBossBriefRoute()
        const snap = b.snapshot || {}
        return {
          ok: true,
          text: [
            `[老板全局账本 · ${b.day || '今日'}]`,
            `总览：${b.headline || ''}`,
            `今日：正事 ${snap.todayWorkMin ?? 0}m（主线 ${snap.todayMainlineMin ?? 0}m / 网课 ${snap.todayVideoStudyMin ?? 0}m / Gemini ${snap.todayGeminiMin ?? 0}m） vs 纯娱乐 ${snap.todayFunMin ?? 0}m vs 折腾工具 ${snap.todayTinkerMin ?? 0}m`,
            `缺口：${Array.isArray(b.keyGaps) && b.keyGaps.length ? b.keyGaps.join('；') : '无'}`,
            b.bossPrompt || '',
          ].filter(Boolean).join('\n'),
        }
      },
    }
  }

  function wallLocksTruthTool() {
    return {
      name: 'wall_locks_truth',
      description: '只读获取此刻锁机裁决真相（胜出主发力手、优先级、模式、剩余时长、放行 App 及最近一次拦截）。没有任何解锁或改锁权限。',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: getToolOutput(),
      execute: () => ({ ok: true, text: buildLockTruthContext() }),
    }
  }

  function wallIssueDirectiveTool() {
    return {
      name: 'wall_issue_directive',
      description: '主管墙止损硬指令：直接钉进今日计划并绑定 P3 验收锁（不可普通删除或跳过；到点缓冲 1 分钟未过验收即锁机）。严禁用于放水。',
      parameters: {
        type: 'object',
        properties: {
          title: { type: 'string', description: '硬指令任务名称（如：完成 30 道资料分析并复盘错因）' },
          deliverable: { type: 'string', description: '到点必须提交的可验物理证据/结论' },
          durationMin: { type: 'integer', minimum: 15, maximum: 180, description: '限定时长（分钟，默认 45）' },
          startMin: { type: 'integer', minimum: 0, maximum: 1425, description: '可选开始分钟（0–1425）；不传则从当前时刻起算' },
          allowedApps: { type: 'array', items: { type: 'string' }, description: '本硬指令允许放行的工作应用包名' },
        },
        required: ['title', 'deliverable'],
        additionalProperties: false,
      },
      output: getToolOutput(),
      execute: (args = {}) => {
        const res = getV4Hub()?.issueDirective?.(args, Date.now())
        if (!res || !res.ok) return { ok: false, text: res?.text || res?.error || '下达硬指令失败' }
        return {
          ok: true,
          text: `✓ 已钉入主管止损硬指令 [${res.block.id}]「${res.block.title}」（${sealHm(res.block.startMin)}–${sealHm(res.block.endMin)}，绑定 P3 验收锁 · 交付：${res.block.deliverable}）。`,
        }
      },
    }
  }

  function wallJudgeDeliveryTool() {
    return {
      name: 'wall_judge_delivery',
      description: '主管墙冷血终审：对用户已提交的验收块交付物裁定 pass 或 reject。红线：用户未提交真实有效交付物时代码会自动拒绝 pass；禁止放水、禁止加额度。',
      parameters: {
        type: 'object',
        properties: {
          blockId: { type: 'string', description: '待终审的时间块 ID（可从账本「验收锁与待终审交付」行查看）' },
          verdict: { type: 'string', enum: ['pass', 'reject'], description: '终审裁定：pass=真过关解锁发余额；reject=驳回并继续锁死' },
          note: { type: 'string', description: '冷血裁定依据（reject 时必填具体缺口/要求补交物）' },
        },
        required: ['blockId', 'verdict', 'note'],
        additionalProperties: false,
      },
      output: getToolOutput(),
      execute: (args = {}) => {
        const res = getV4Hub()?.judgeDelivery?.({ ...args, judgedBy: 'wall_boss' }, Date.now())
        if (!res || !res.ok) return { ok: false, text: res?.text || res?.error || '终审裁决失败' }
        if (res.verdict === 'pass') {
          return {
            ok: true,
            text: `✓ 终审 PASS [${res.block.id}]「${res.block.title}」：已标为完成并立即解除 P3 验收锁${res.rewardMin > 0 ? `，入账 +${res.rewardMin}m 娱乐余额` : ''}。评语：${res.block.judgeNote}`,
          }
        }
        return {
          ok: true,
          text: `✗ 终审 REJECT [${res.block.id}]「${res.block.title}」：驳回（${res.block.judgeNote}），继续维持 P3 验收锁直至补充真交付。`,
        }
      },
    }
  }

  function wallIssueSprintCustodyTool() {
    return {
      name: 'wall_issue_sprint_custody',
      description: '主管墙路径2强托管与 Re-brief 工具：高强度质询后一次签发未来 2-4 小时「串联托管冲刺轨」（1-4 个强制 verify 块，托管期内禁止用户自删或降级），或凭客观变动理由执行 rebrief_reason 重签。',
      parameters: {
        type: 'object',
        properties: {
          title: { type: 'string', description: '托管冲刺轨总标题（如：下午考公资料分析2连冲）' },
          blocks: {
            type: 'array',
            items: {
              type: 'object',
              properties: {
                title: { type: 'string' },
                deliverable: { type: 'string' },
                durationMin: { type: 'integer', minimum: 15, maximum: 120 },
                breakAfterMin: { type: 'integer', minimum: 0, maximum: 20 },
                allowedApps: { type: 'array', items: { type: 'string' } },
              },
              required: ['title', 'deliverable'],
            },
          },
          armAutoKickoff: { type: 'boolean', description: '是否顺带开启 09:15/14:45 无人破冰硬起手哨' },
          rebriefReason: { type: 'string', description: '若属突发变动 Re-brief 重签，填写 ≥6 字客观原因' },
          downgradeLowEnergy: { type: 'boolean', description: 'Re-brief 时是否降为 25m 低电量保底块' },
        },
      },
      output: getToolOutput(),
      execute: (args = {}) => {
        const now = Date.now()
        const rebrief = String(args.rebriefReason || '').trim()
        const res = rebrief
          ? getV4Hub()?.rebriefSprintCustody?.({ reason: rebrief, blocks: args.blocks, downgradeLowEnergy: Boolean(args.downgradeLowEnergy), issuedBy: 'wall_boss' }, now)
          : getV4Hub()?.issueSprintCustody?.({ title: args.title || '主管托管冲刺轨', blocks: args.blocks, armAutoKickoff: args.armAutoKickoff, issuedBy: 'wall_boss' }, now)
        if (!res || !res.ok) return { ok: false, text: res?.text || res?.error || '签发托管冲刺轨失败' }
        bumpDayPlanWatch(now + 2 * 60_000)
        return {
          ok: true,
          text: `✓ ${rebrief ? 'Re-brief 重签' : '签发'}托管冲刺轨「${res.custody.title}」（${res.blocks.length} 块强制 verify，至 ${res.untilHm}）。托管期内禁止自删降级。`,
        }
      },
    }
  }

  function mountWallBoss(rawSid) {
    const config = getConfig()
    let sid = typeof rawSid === 'string' ? rawSid.trim() : ''
    if (sid && (sid === String(config.sessionId || '') || (config.dual !== false && sid === String(config.strategistSessionId || '')))) {
      sid = ''
    }
    const agent = sid ? liveAgent(ctx, sid) : null
    if (wallBossMount.agent === (agent || null) && wallBossMount.sid === sid) return
    try { wallBossMount.off?.() } catch {}
    try { wallBossMount.toolOff?.() } catch {}
    wallBossMount = { sid, agent: agent || null, off: null, toolOff: null }
    if (!agent) return
    if (agent.ctx?.tools && typeof agent.ctx.tools.register === 'function') {
      const offs = []
      const roTools = [
        wallBossAuditTool(),
        wallLocksTruthTool(),
        { ...vaultSearchTool(), name: 'wall_vault_search' },
        { ...vaultRecentTool(), name: 'wall_vault_recent' },
        wallIssueDirectiveTool(),
        wallJudgeDeliveryTool(),
        wallIssueSprintCustodyTool(),
      ]
      for (const tool of roTools) {
        try {
          const off = agent.ctx.tools.register(tool)
          if (typeof off === 'function') offs.push(off)
        } catch (error) {
          console.error('[companion] 主管墙工具 ' + tool.name + ' 挂不上：' + String((error && error.message) || error))
        }
      }
      if (offs.length) wallBossMount.toolOff = () => offs.forEach((f) => f())
    }
    if (agent.ctx?.systemPrompt?.context) {
      const ctxOffs = []
      try {
        const offP = agent.ctx.systemPrompt.context({
          name: 'dafeiyu-wall-boss',
          order: 850,
          interpolate: false,
          text: () => WALL_BOSS_PERSONA_TEXT,
        })
        if (typeof offP === 'function') ctxOffs.push(offP)
      } catch (error) {
        console.error('[companion] 主管墙人设挂不上：' + String((error && error.message) || error))
      }
      try {
        const buildLocksOverview = getBuildLocksOverview()
        const offL = agent.ctx.systemPrompt.context({
          name: 'dafeiyu-wall-boss-ledger',
          order: 860,
          interpolate: false,
          text: () => buildWallBossLedgerText(wallBossBriefRoute(), buildLocksOverview()),
        })
        if (typeof offL === 'function') ctxOffs.push(offL)
      } catch (error) {
        console.error('[companion] 主管墙账本挂不上：' + String((error && error.message) || error))
      }
      if (ctxOffs.length) {
        wallBossMount.off = () => {
          for (const fn of ctxOffs) {
            try { fn() } catch {}
          }
        }
      }
    }
  }

  return {
    wallBossDesk,
    loadWallBossHistory,
    saveWallBossHistory,
    appendWallBossEntry,
    handleWallBossSessionEvent,
    wallBossBriefRoute,
    wallBossStatusSnapshot,
    wallBossChatRoute,
    mountWallBoss,
  }
}
